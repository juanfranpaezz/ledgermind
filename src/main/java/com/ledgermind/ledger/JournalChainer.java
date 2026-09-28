package com.ledgermind.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Journal hash-chain (tamper-evidence, AWS QLDB pattern).
 *
 * <p>Walks the new postings by id and chains them in {@code posting_hash} (append-only):
 * {@code entry_hash = SHA-256(prev_hash || canonical(posting))}. It runs ASYNCHRONOUSLY (outside the transfers'
 * hot path) so as not to serialize the concurrency already achieved. {@link #verify()}
 * recomputes the chain from the CURRENT content of the postings: if someone edits an old one, its
 * hash stops matching and the exact break point is detected.
 */
@Component
public class JournalChainer {

    private static final String GENESIS = "0".repeat(64);
    private static final int BATCH = 200;

    private final PostingRepository postings;
    private final PostingHashRepository hashes;

    private final JdbcTemplate jdbc;

    // Chainer state for the audit verdict. What counts is what is COMMITTED: every run writes
    // journal_chainer_state (start, snapshot, end, whether it filled the batch) in the SAME transaction as its links, so the
    // audit reads it in its own snapshot. (It used to be an in-memory stamp taken BEFORE the commit: during a slow commit
    // the audit saw "queue drained" with the links still invisible.) Only what cannot be committed stays in memory:
    // when this JVM started and whether a run is IN PROGRESS (so a slow run is not called DETENIDO).
    private final Instant bootedAt = Instant.now();
    private volatile Instant runningSince;
    private volatile Instant lastCommittedAt;

    /**
     * Life of the chainer in this JVM (only for DETENIDO vs ATRASADO, never for evidence): startup, run in progress
     * ({@code null} = none) and the last commit seen AFTER committing (the DB's run_finished_at is taken before the
     * flush of the links, which under load can take a while).
     */
    public record Liveness(Instant bootedAt, Instant runningSince, Instant lastCommittedAt, int batchSize) {
    }

    public Liveness liveness() {
        return new Liveness(bootedAt, runningSince, lastCommittedAt, BATCH);
    }

    public JournalChainer(PostingRepository postings, PostingHashRepository hashes, JdbcTemplate jdbc) {
        this.postings = postings;
        this.hashes = hashes;
        this.jdbc = jdbc;
    }

    /** Chains the pending postings. Async (every 5s); it can also be called directly (tests). */
    @Scheduled(fixedDelayString = "${ledgermind.journal.chain-delay-ms:5000}")
    @Transactional
    public void chainPendingPostings() {
        Instant started = Instant.now();
        runningSince = started;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        lastCommittedAt = Instant.now();
                    }
                    runningSince = null;                // after the commit (or rollback), not before
                }
            });
        }
        // xid of THIS pass, taken before reading the queue: any higher xid was assigned afterwards (the audit uses it). pg_snapshot_xmax
        // does not work: it is the last COMPLETED xid + 1, and an open transaction with a higher xid would count
        // as "later" even though it already existed.
        Long passXid = jdbc.queryForObject("SELECT pg_current_xact_id()::text::bigint", Long.class);
        PostingHash head = hashes.findTopByOrderBySeqDesc().orElse(null);
        long seq = head != null ? head.getSeq() : 0L;
        String prev = head != null ? head.getEntryHash() : GENESIS;
        // By ABSENCE from posting_hash (not by an id watermark): a posting with a lower id that commits
        // late is not left unchained (before, findByIdGreaterThan skipped it forever).
        List<Posting> pending = postings.findUnchainedOrderByIdAsc(Limit.of(BATCH));
        for (Posting p : pending) {
            String entry = entryHash(prev, p);
            hashes.save(new PostingHash(p.getId(), ++seq, prev, entry));
            prev = entry;
        }
        // COMMITTED state: in the SAME transaction as the links (visible to the audit only together with them).
        jdbc.update("INSERT INTO journal_chainer_state (id, run_started_at, pass_xid, run_finished_at, chained,"
                        + " hit_batch_limit) VALUES (1, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET"
                        + " run_started_at = excluded.run_started_at, pass_xid = excluded.pass_xid,"
                        + " run_finished_at = excluded.run_finished_at, chained = excluded.chained,"
                        + " hit_batch_limit = excluded.hit_batch_limit",
                started.atOffset(ZoneOffset.UTC), passXid, Instant.now().atOffset(ZoneOffset.UTC), pending.size(),
                pending.size() >= BATCH);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runningSince = null;
        }
    }

    /**
     * Walks the chain and recomputes every hash from the CURRENT content of each posting. It pages by seq (keyset,
     * 200 links per page) and loads each page's postings in ONE query, so there is no N+1; but it is still O(n): about
     * 2 x n/200 round-trips, and every loaded Posting and PostingHash stays in the persistence context until this
     * read-only transaction ends, so memory also grows with n. At real scale (millions of postings) the next step is
     * Merkle + incremental verification from the last checkpoint (ADR 0004, D4: proposed, not built).
     */
    @Transactional(readOnly = true)
    public VerifyResult verify() {
        String prev = GENESIS;
        long checked = 0;
        long lastSeq = 0;
        while (true) {
            List<PostingHash> batch = hashes.findBySeqGreaterThanOrderBySeqAsc(lastSeq, Limit.of(BATCH));
            if (batch.isEmpty()) {
                break;
            }
            List<Long> ids = batch.stream().map(PostingHash::getPostingId).toList();
            Map<Long, Posting> byId = postings.findAllById(ids).stream()
                    .collect(Collectors.toMap(Posting::getId, Function.identity()));
            for (PostingHash link : batch) {
                Posting p = byId.get(link.getPostingId());
                if (p == null) {
                    return new VerifyResult(false, checked, link.getSeq());       // posting deleted
                }
                if (!prev.equals(link.getPrevHash()) || !entryHash(prev, p).equals(link.getEntryHash())) {
                    return new VerifyResult(false, checked, link.getSeq());       // content altered / chain broken
                }
                prev = link.getEntryHash();
                checked++;
                lastSeq = link.getSeq();
            }
            if (batch.size() < BATCH) {
                break;
            }
        }
        return new VerifyResult(true, checked, null);
    }

    static String entryHash(String prevHash, Posting p) {
        String canonical = p.getId() + "|" + p.getDebitAccountId() + "|" + p.getCreditAccountId()
                + "|" + p.getAmount() + "|" + p.getAsset() + "|" + p.getIdempotencyKey()
                + "|" + p.getCreatedAt();
        return sha256Hex(prevHash + canonical);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Result of verifying the integrity of the chain. */
    public record VerifyResult(boolean intact, long chainedCount, Long brokenAtSeq) {
    }
}
