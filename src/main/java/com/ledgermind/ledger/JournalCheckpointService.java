package com.ledgermind.ledger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically signs the head of the hash-chain (Signed Tree Head). It reads the {@link PostingHash} with the
 * highest seq, and if the head changed since the last checkpoint, it signs a canonical message with the
 * ACTIVE signer (resolved by {@link JournalSignerRegistry}; default ML-DSA-65) and stores an immutable
 * {@link JournalCheckpoint} that persists the algorithm + the public key used.
 *
 * <p>Crypto-agility (signing + verification). SIGNING: the active scheme is configurable
 * ({@code ledgermind.journal.signer.algorithm}) and persisted per checkpoint. VERIFICATION: it is DISPATCHED by
 * the checkpoint's {@code algorithm} via {@link JournalSignerRegistry} -> each checkpoint is verified with
 * ITS scheme, supporting >1 algorithm in parallel and allowing rotation without blinding the old checkpoints.
 *
 * <p>Runs ASYNCHRONOUSLY, after the {@link JournalChainer}: the chainer links postings, this service
 * anchors the chain with a signature. Idempotent WITH A SINGLE WRITER (compares headHash before
 * signing); the default scheduler is single-threaded, so it does not overlap with itself. In HA (2+ replicas)
 * the table's {@code UNIQUE (chain_seq)} degrades the race to an INSERT that fails on the 2nd replica.
 */
@Service
public class JournalCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(JournalCheckpointService.class);

    private final PostingHashRepository hashes;
    private final JournalCheckpointRepository checkpoints;
    private final JournalChainer chainer;
    private final JournalSignerRegistry signers;
    private final String activeAlgorithm;
    private final AccountBalanceVerifier balances;
    private final PostingRepository postings;
    /** Window in which a posting without a link is legitimate; see {@link #effectiveUnchainedGraceMs}. */
    private final long unchainedGraceMs;
    private final long chainDelayMs;
    private final JdbcTemplate jdbc;

    public JournalCheckpointService(PostingHashRepository hashes,
                                    JournalCheckpointRepository checkpoints,
                                    JournalChainer chainer,
                                    JournalSignerRegistry signers,
                                    @Value("${ledgermind.journal.signer.algorithm:ML-DSA-65}") String activeAlgorithm,
                                    AccountBalanceVerifier balances,
                                    PostingRepository postings,
                                    JdbcTemplate jdbc,
                                    @Value("${ledgermind.journal.unchained-grace-ms:60000}") long unchainedGraceMs,
                                    @Value("${ledgermind.journal.chain-delay-ms:5000}") long chainDelayMs) {
        this.hashes = hashes;
        this.checkpoints = checkpoints;
        this.chainer = chainer;
        this.signers = signers;
        this.activeAlgorithm = activeAlgorithm;
        this.balances = balances;
        this.postings = postings;
        this.unchainedGraceMs = effectiveUnchainedGraceMs(unchainedGraceMs, chainDelayMs);
        this.chainDelayMs = chainDelayMs;
        this.jdbc = jdbc;
    }

    /**
     * Window in which a posting WITHOUT a link is legitimate (already posted, the chainer has not passed yet). The
     * chainer runs with fixedDelay = chain-delay-ms and chains 200 at a time by absence: a posting that commits
     * right after a run starts waits <= 1 cycle + however long that run takes. 3 cycles leave room
     * for a slow run or a backlog of ~2 batches; the floor (unchained-grace-ms, default 60 s) keeps a small
     * chain-delay from making the rule jumpy. It never goes below 3 cycles: if someone raises chain-delay without touching
     * the grace, the rule does not fire on legitimate postings.
     */
    static long effectiveUnchainedGraceMs(long configuredGraceMs, long chainDelayMs) {
        return Math.max(configuredGraceMs, 3 * chainDelayMs);
    }

    /** Signs the head if it advanced since the last checkpoint. Async (every 10s); also callable in tests. */
    @Scheduled(fixedDelayString = "${ledgermind.journal.checkpoint-delay-ms:10000}")
    @Transactional
    public Optional<JournalCheckpoint> checkpointIfHeadAdvanced() {
        PostingHash head = hashes.findTopByOrderBySeqDesc().orElse(null);
        if (head == null) {
            return Optional.empty();                                   // empty chain: nothing to sign
        }
        JournalCheckpoint last = checkpoints.findTopByOrderByIdDesc().orElse(null);
        if (last != null && last.getHeadHash().equals(head.getEntryHash())) {
            return Optional.empty();                                   // head unchanged: already signed
        }
        // Signs with the ACTIVE scheme (configurable; default ML-DSA-65). The algorithm and the public key
        // are persisted IN the checkpoint -> verification dispatches by that name, not by TODAY's signer.
        JournalSigner signer = signers.activeSigner(activeAlgorithm);
        byte[] message = checkpointMessage(head.getSeq(), head.getEntryHash());
        String signature = signer.sign(message);
        JournalCheckpoint cp = new JournalCheckpoint(head.getSeq(), head.getEntryHash(),
                signer.algorithm(), signer.publicKeyBase64(), signature);
        try {
            return Optional.of(checkpoints.save(cp));
        } catch (DataIntegrityViolationException raced) {
            // Expected ONLY if another writer (the @Scheduled job vs the demo's synchronous reset) signed this
            // same head first and hit UNIQUE(chain_seq): an idempotent no-op. But the catch is by TYPE:
            // it is logged so that an UNEXPECTED cause (another constraint) stays VISIBLE instead of being swallowed
            // silently while audit() would keep reporting the old checkpoint as valid.
            log.debug("checkpoint not inserted for seq {} (probably a benign race on UNIQUE(chain_seq)): {}",
                    head.getSeq(), raced.getMostSpecificCause().getMessage());
            return Optional.empty();
        }
    }

    /** The latest checkpoint (to expose it through the API). */
    @Transactional(readOnly = true)
    public Optional<JournalCheckpoint> latest() {
        return checkpoints.findTopByOrderByIdDesc();
    }

    /**
     * Verifies the latest checkpoint on INDEPENDENT planes, without conflating them:
     * <ul>
     *   <li>{@code signatureValid}: the ML-DSA signature checks out under the public key the checkpoint stores.
     *       NOTE: it proves message integrity (signature vs accompanying key), NOT the signer's authenticity;
     *       without an external trust anchor (pinned key/HSM/transparency log) it does NOT prove <i>who</i> signed.</li>
     *   <li>{@code chainIntact}: the hash-chain recomputes from the CURRENT content of the postings. THIS is
     *       the real tamper-evidence of the CONTENT; it comes from SHA-256, not from the signature.</li>
     *   <li>{@code signedHeadStillInChain}: the signed link (seq == chainSeq) is still present with the same
     *       entry_hash. It detects a rewrite/deletion of the hash table itself.</li>
     *   <li>{@code isLatestHead}: the signed head is also the live head. INFORMATIONAL: in normal operation
     *       it is false (the chain advances ~10s before re-signing); on its own it is NOT evidence of anything.</li>
     * </ul>
     * Key demo: after editing a historical posting, {@code signatureValid} stays true (the signature is over
     * the original head) but {@code chainIntact} drops to false. The signature ANCHORS the head in time; the
     * chained SHA-256 is what exposes the alteration of the content.
     */
    @Transactional(readOnly = true)
    public CheckpointVerification verifyLatest() {
        JournalCheckpoint cp = checkpoints.findTopByOrderByIdDesc().orElse(null);
        if (cp == null) {
            return CheckpointVerification.none();
        }
        Signals s = signalsFor(cp);
        boolean chainIntact = chainer.verify().intact();
        return new CheckpointVerification(true, cp.getAlgorithm(), cp.getChainSeq(), cp.getHeadHash(),
                s.signatureValid(), chainIntact, s.signedHeadStillInChain(), s.isLatestHead(), cp.getSignedAt());
    }

    /**
     * Consolidated journal audit for an agent (MCP tool / endpoint): it combines the integrity of the
     * hash-chain (recomputed SHA-256) with the validity of the latest checkpoint's post-quantum signature, and
     * summarizes a readable verdict. It walks the chain ONLY once.
     *
     * <p>SCOPE (the verdict states it): it detects EDITING/rewriting of already-chained postings and of the
     * signed link. On its own it does NOT detect: (1) TRUNCATION of the tail after the last checkpoint
     * (deleting the newest postings leaves a consistent prefix) — that requires a high-water-mark anchored
     * OUTSIDE the DB; (2) the signer's AUTHENTICITY — {@code signatureValid} is message integrity,
     * it does not prove WHO signed without an externally anchored key; (3) the INSERTION of a posting with a recent
     * created_at and adjusted counters: inside the chainer's window it is indistinguishable from a legitimate
     * posting just written, and afterwards the chainer chains it as legitimate. It DOES detect an unlinked posting
     * older than that window ({@code staleUnchainedPostings}) and counts it as tamper ONLY if it was written by
     * a transaction that started after the chainer's last confirmed pass (see {@link #coverage}); the
     * rest is {@code coverageDegraded}. It is tamper-EVIDENCE, not prevention.
     *
     * <p>Runs in ONE REPEATABLE READ snapshot: the hash-chain, the balance replay, the counters and the chainer's committed
     * state are read at the same instant, so a transfer that commits in the middle of the audit does not
     * unbalance the balances (under READ COMMITTED it did).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JournalIntegrityReport audit() {
        Instant auditStart = Instant.now();
        JournalChainer.VerifyResult chain = chainer.verify();
        // The balance counters are NEVER recomputed on the write path (they are advanced with +=), so
        // the read-model can disagree with the journal even if the hash-chain does not break.
        // They are re-derived HERE, inside the same verdict the API, the MCP tool and the demo already read: a
        // check the verdict does not look at is decorative.
        AccountBalanceVerifier.BalanceVerifyResult bal = balances.verify();
        // COVERAGE: the balance replay walks ALL postings; the hash-chain only the ones with a link, so
        // an unlinked posting is invisible to chainIntact. Inside the chainer's legitimate window that is
        // normal (it is REPORTED, it is not tamper); outside it, it is tamper ONLY with evidence (coverage), otherwise degraded coverage. It only
        // pushes towards tamper, never turns it off. Limit: a posting inserted with a recent created_at is indistinguishable
        // from a legitimate one, and the chainer chains it as legitimate (closing that requires provenance outside the DB).
        Coverage cov = coverage(auditStart);
        JournalCheckpoint cp = checkpoints.findTopByOrderByIdDesc().orElse(null);
        if (cp == null) {
            boolean tampered = !chain.intact() || !bal.consistent() || cov.outsideApp() > 0;
            String verdict;
            if (!tampered) {
                verdict = coverageAlert(cov) + "NO SIGNED CHECKPOINT: the present hash-chain recomputes consistently over "
                        + chain.chainedCount() + " postings and the balance counters of "
                        + bal.accountsChecked() + " account(s) balance against the journal replay, but without"
                        + " a signed checkpoint anchoring the head a previous truncation/rollback CANNOT be"
                        + " ruled out. There is no ML-DSA signature yet." + coverageNote(cov);
            } else {
                StringBuilder sb = new StringBuilder(TAMPER_HEADLINE);
                sb.append(" NO SIGNED CHECKPOINT YET: signatureValid and signedHeadStillInChain being false"
                        + " mean 'not applicable' (there is no signature to verify), NOT tampering;");
                if (!chain.intact()) {
                    sb.append(" the hash-chain breaks at seq ").append(chain.brokenAtSeq())
                            .append(" (still without a signed checkpoint);");
                }
                appendBalanceClause(sb, bal);
                appendCoverageClause(sb, cov);
                verdict = sb.toString();
            }
            CoverageReason reason = coverageReason(cov, false);
            return new JournalIntegrityReport(tampered, verdict, reason != null, reason, chain.intact(),
                    chain.chainedCount(), chain.brokenAtSeq(), false, null, 0L, null, false, false, false, null,
                    bal.consistent(), bal.accountsChecked(), bal.mismatches(),
                    cov.unchained(), cov.staleUnchained(), cov.graceMs());
        }
        Signals s = signalsFor(cp);
        boolean tampered = !chain.intact() || !s.signatureValid() || !s.signedHeadStillInChain()
                || !bal.consistent() || cov.outsideApp() > 0;
        CoverageReason reason = coverageReason(cov, true);
        return new JournalIntegrityReport(tampered, verdict(chain, cp, s, bal, cov, tampered),
                reason != null, reason, chain.intact(), chain.chainedCount(), chain.brokenAtSeq(),
                true, cp.getAlgorithm(), cp.getChainSeq(), cp.getHeadHash(),
                s.signatureValid(), s.signedHeadStillInChain(), s.isLatestHead(), cp.getSignedAt(),
                bal.consistent(), bal.accountsChecked(), bal.mismatches(),
                cov.unchained(), cov.staleUnchained(), cov.graceMs());
    }

    /** Headline of a verdict with CONFIRMED evidence of tampering. */
    static final String TAMPER_HEADLINE = "TAMPER DETECTED:";

    /**
     * How many postings the hash-chain does NOT cover, how many already exceed the chainer's legitimate window, and how many of those
     * are EVIDENCE of a write outside the app. All in the audit's snapshot (REPEATABLE READ).
     */
    private Coverage coverage(Instant now) {
        long unchained = postings.countUnchained();
        long stale = unchained == 0 ? 0L
                : postings.countUnchainedCreatedBefore(now.minusMillis(unchainedGraceMs));
        ChainerRun run = committedChainerRun();
        long outside = stale == 0 || run == null ? 0L : countWrittenAfterPassWithOldDate(run);
        JournalChainer.Liveness live = chainer.liveness();
        Instant lastActivity = latest(latest(live.bootedAt(), live.runningSince()),
                latest(live.lastCommittedAt(), run != null ? run.finishedAt() : null));
        StaleCause cause = classifyStale(stale - outside, now, lastActivity, chainDelayMs);
        long idleMs = Math.max(0L, Duration.between(lastActivity, now).toMillis());
        long catchUpMs = ((unchained + live.batchSize() - 1) / live.batchSize()) * chainDelayMs;
        return new Coverage(unchained, stale, outside, unchainedGraceMs, cause, idleMs, catchUpMs);
    }

    /** The chainer's last COMMITTED run, read in the audit's snapshot ({@code null} = none). */
    private ChainerRun committedChainerRun() {
        List<ChainerRun> rows = jdbc.query("SELECT run_started_at, pass_xid, run_finished_at"
                        + " FROM journal_chainer_state WHERE id = 1",
                (rs, n) -> new ChainerRun(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getLong(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * EVIDENCE of a write outside the app: unlinked postings (1) written by a transaction whose xid was
     * assigned AFTER the chainer's last committed pass (xmin greater than that pass's pass_xid) and
     * (2) with a created_at earlier than the start of that pass minus the window. A legitimate transfer sets created_at
     * in Java right before its INSERT (which assigns its xid), so (1) implies a created_at later than the start of the
     * pass and cannot meet (2). A SLOW legitimate one (contention, retries, deadlock) that was still open when
     * the chainer passed already had its xid: it does not meet (1). An UPDATE after the pass also changes xmin: an
     * outside edit that ages the date counts too. xmin is 32 bits: it is compared in circular arithmetic
     * against the reduced 64-bit pass_xid (the special xids 0-2, e.g. frozen ones, do not count).
     */
    private long countWrittenAfterPassWithOldDate(ChainerRun run) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM posting p WHERE p.created_at < ?"
                        + " AND NOT EXISTS (SELECT 1 FROM posting_hash h WHERE h.posting_id = p.id)"
                        + " AND p.xmin::text::bigint >= 3"
                        + " AND ((p.xmin::text::bigint - (?::bigint % 4294967296) + 4294967296) % 4294967296)"
                        + " BETWEEN 1 AND 2147483647",
                Long.class, run.startedAt().minusMillis(unchainedGraceMs).atOffset(ZoneOffset.UTC),
                run.passXid());
        return n == null ? 0L : n;
    }

    /** A committed chainer run: start (app clock), its xid and end. */
    record ChainerRun(Instant startedAt, long passXid, Instant finishedAt) {
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    /**
     * WHY there are unlinked postings older than the window that are NOT evidence of a write outside the app. DETENIDO (stopped) =
     * no chainer activity (committed run, run in progress or startup of this JVM) for more than 3
     * cycles. ATRASADO (behind) = alive, with a backlog or with legitimate transactions that committed after its last pass:
     * transient.
     */
    static StaleCause classifyStale(long pendingStale, Instant now, Instant lastActivity, long chainDelayMs) {
        if (pendingStale <= 0) {
            return StaleCause.NONE;
        }
        if (Duration.between(lastActivity, now).toMillis() > 3 * chainDelayMs) {
            return StaleCause.CHAINER_STOPPED;
        }
        return StaleCause.CHAINER_BEHIND;
    }

    enum StaleCause { NONE, CHAINER_STOPPED, CHAINER_BEHIND }

    /** Why the audit CANNOT confirm right now (it is not tamper). {@code null} = full coverage. */
    public enum CoverageReason { ATRASADO, DETENIDO, SIN_CHECKPOINT }

    static CoverageReason coverageReason(Coverage cov, boolean checkpointPresent) {
        if (cov.cause() == StaleCause.CHAINER_STOPPED) {
            return CoverageReason.DETENIDO;
        }
        if (cov.cause() == StaleCause.CHAINER_BEHIND) {
            return CoverageReason.ATRASADO;
        }
        return checkpointPresent ? null : CoverageReason.SIN_CHECKPOINT;
    }

    /**
     * Is the public key stored in the checkpoint from the scheme the checkpoint DECLARES? It compares the algorithm
     * OID of that key's SubjectPublicKeyInfo (X.509) against the one of the key of the signer registered for the
     * declared scheme. An algorithm that is NOT registered -> true here on purpose: the {@code signers.verify} that follows fails
     * LOUDLY (structural, not tamper). A stored key that does not parse as X.509 -> {@link IllegalStateException}
     * (structural), with the same discipline as the signers.
     */
    private boolean keyMatchesDeclaredAlgorithm(JournalCheckpoint cp) {
        if (!signers.supports(cp.getAlgorithm())) {
            return true;
        }
        // activeSigner(name) is the registry's lookup by name; only its public key is used here.
        String registeredKey = signers.activeSigner(cp.getAlgorithm()).publicKeyBase64();
        return keyAlgorithmOid(registeredKey).equals(keyAlgorithmOid(cp.getPublicKey()));
    }

    private static ASN1ObjectIdentifier keyAlgorithmOid(String publicKeyBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(publicKeyBase64 == null ? "" : publicKeyBase64);
            if (der.length == 0) {
                // An empty key is not rejected by BouncyCastle with IllegalArgumentException: for 0 bytes its
                // ASN1Sequence.getInstance throws NullPointerException (measured). Reject it here with the same
                // structural failure as any other key that is not X.509.
                throw new IllegalStateException("The checkpoint public key is empty: it is not a valid X.509"
                        + " SubjectPublicKeyInfo (structural cause, not tamper evidence)");
            }
            return SubjectPublicKeyInfo.getInstance(der).getAlgorithm().getAlgorithm();
        } catch (IllegalArgumentException structural) {
            throw new IllegalStateException("The checkpoint's public key is not a valid X.509 SubjectPublicKeyInfo"
                    + " (causa estructural, no evidencia de tamper)", structural);
        }
    }

    /** Checkpoint signals that do NOT require recomputing the whole chain (signature + presence + whether it is the head). */
    private Signals signalsFor(JournalCheckpoint cp) {
        // DISPATCH BY ALGORITHM (crypto-agility): verification uses the scheme the checkpoint ITSELF recorded
        // (cp.getAlgorithm()), NOT today's active signer; that way an old checkpoint is still verified after
        // a rotation. An algorithm that is NOT registered fails LOUDLY in the registry (structural, not tamper).
        // And the declared ALGORITHM stays INSIDE the verification loop: if a DB writer rewrites ONLY the
        // `algorithm` column towards ANOTHER registered scheme (signature and key intact), the dispatch would hand an
        // ML-DSA key to the Ed25519 verifier, which rejects it as a STRUCTURAL failure (exception) and not as tamper.
        // That is why, before dispatching, the stored public key has to belong to the declared scheme (see
        // keyMatchesDeclaredAlgorithm): if it does not, the checkpoint's metadata is lying -> signatureValid = false.
        boolean signatureValid = keyMatchesDeclaredAlgorithm(cp) && signers.verify(cp.getAlgorithm(),
                checkpointMessage(cp.getChainSeq(), cp.getHeadHash()), cp.getSignature(), cp.getPublicKey());
        boolean signedHeadStillInChain = hashes.findBySeq(cp.getChainSeq())
                .map(h -> h.getEntryHash().equals(cp.getHeadHash()))
                .orElse(false);
        PostingHash liveHead = hashes.findTopByOrderBySeqDesc().orElse(null);
        boolean isLatestHead = liveHead != null && liveHead.getEntryHash().equals(cp.getHeadHash());
        return new Signals(signatureValid, signedHeadStillInChain, isLatestHead);
    }

    private static String verdict(JournalChainer.VerifyResult chain, JournalCheckpoint cp,
                                  Signals s, AccountBalanceVerifier.BalanceVerifyResult bal,
                                  Coverage cov, boolean tampered) {
        if (!tampered) {
            return coverageAlert(cov) + "NO EVIDENCE OF EDITING: the balance counters of " + bal.accountsChecked()
                    + " account(s) recompute equal to the replay of " + bal.postingsReplayed()
                    + " posting(s), the hash-chain recomputes clean over " + chain.chainedCount()
                    + " postings and the signature of the latest checkpoint (" + cp.getAlgorithm() + ", seq "
                    + cp.getChainSeq() + ", signed " + cp.getSignedAt() + ") checks out under the key that the"
                    + " checkpoint itself stores (message integrity, NOT authenticity: proving WHO signed"
                    + " requires a key anchored outside the DB). It does not rule out truncation of the tail after"
                    + " the checkpoint nor the INSERTION of a posting with its counters adjusted (the chainer"
                    + " chains it as legitimate)." + coverageNote(cov) + " Tamper-EVIDENCE, not prevention.";
        }
        StringBuilder sb = new StringBuilder(TAMPER_HEADLINE);
        if (!chain.intact()) {
            sb.append(" the hash-chain breaks at seq ").append(chain.brokenAtSeq())
                    .append(" (a posting was edited or deleted after being chained);");
        }
        if (!s.signatureValid()) {
            sb.append(" the checkpoint signature does not verify under its key;");
        }
        if (!s.signedHeadStillInChain()) {
            sb.append(" the signed link (seq ").append(cp.getChainSeq()).append(") was rewritten;");
        }
        appendBalanceClause(sb, bal);
        appendCoverageClause(sb, cov);
        return sb.toString();
    }

    /**
     * Adds the counter-vs-journal mismatch to the verdict: the account, BOTH numbers and the difference. The
     * cached balance and the journal can disagree WITHOUT the chain breaking (e.g. if the edited posting
     * was not chained yet), so this clause is not redundant with the hash-chain one.
     */
    private static void appendBalanceClause(StringBuilder sb, AccountBalanceVerifier.BalanceVerifyResult bal) {
        if (bal.consistent()) {
            return;
        }
        sb.append(" the balance counters do NOT balance against the journal in ")
                .append(bal.mismatches().size()).append(" account(s) [")
                .append(bal.mismatches().stream()
                        .map(AccountBalanceVerifier.AccountBalanceMismatch::describe)
                        .collect(Collectors.joining("; ")))
                .append("];");
    }

    private record Signals(boolean signatureValid, boolean signedHeadStillInChain, boolean isLatestHead) {
    }

    record Coverage(long unchained, long staleUnchained, long outsideApp, long graceMs, StaleCause cause,
                    long chainerIdleMs, long catchUpMs) {
    }

    /** Sentence of the clean verdict when there are unlinked postings INSIDE the window (not tamper, but not covered). */
    private static String coverageNote(Coverage cov) {
        if (cov.unchained() == 0 || cov.staleUnchained() > 0) {       // degraded or tamper: another clause explains it
            return "";
        }
        return " NOTE: " + cov.unchained() + " posting(s) not chained yet, inside the chainer's normal"
                + " window (" + cov.graceMs() / 1000 + " s): their content is NOT yet covered by the"
                + " hash-chain, and inside that window an INSERTION made by a DB writer looks the same as a"
                + " legitimate posting.";
    }

    /**
     * Coverage clauses of the verdict: first the EVIDENCE (postings written after the chainer's last pass
     * with an old date), then the old unlinked postings that are NOT evidence (chainer stopped or
     * behind).
     */
    static void appendCoverageClause(StringBuilder sb, Coverage cov) {
        if (cov.outsideApp() > 0) {
            sb.append(" ").append(cov.outsideApp()).append(" unchained posting(s) were written by a transaction that")
                    .append(" started AFTER the chainer's last confirmed pass, with a date older")
                    .append(" than that pass minus the window (").append(seconds(cov.graceMs())).append("): a")
                    .append(" legitimate transaction sets the date when it writes, so this is a sign of a posting inserted")
                    .append(" outside the app with an old date (or edited outside after that pass). It is")
                    .append(" transient: the next run chains it as legitimate;");
        }
        long pending = cov.staleUnchained() - cov.outsideApp();
        if (pending <= 0) {
            return;
        }
        sb.append(" ").append(pending).append(" posting(s) have been unchained for more than ").append(seconds(cov.graceMs()))
                .append(" (the hash-chain does not vouch for them yet; this is NOT evidence of tampering): ");
        switch (cov.cause()) {
            case CHAINER_STOPPED -> sb.append("the chainer is STOPPED or blocked (no activity for ")
                    .append(seconds(cov.chainerIdleMs())).append(", more than 3 cycles). While it stays that way, a posting")
                    .append(" inserted by a DB writer cannot be told apart from a legitimate one: check the chainer")
                    .append(" and re-audit;");
            default -> sb.append("the chainer is BEHIND (backlog, or legitimate transactions that committed")
                    .append(" after its last pass; ").append(cov.unchained())
                    .append(" posting(s) queued). It is TRANSIENT: re-audit in ~")
                    .append(Math.max(1, cov.catchUpMs() / 1000)).append(" s, when the queue drains. While it lasts,")
                    .append(" an insertion by a DB writer whose transaction was already open when the")
                    .append(" chainer passed cannot be told apart from a legitimate one;");
        }
    }

    /** Headline + clause when coverage is degraded WITHOUT evidence of tampering ("" if it is not). */
    private static String coverageAlert(Coverage cov) {
        if (cov.staleUnchained() - cov.outsideApp() <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("COVERAGE ALERT (not evidence of tampering):");
        appendCoverageClause(sb, cov);
        return sb.append(" ").toString();
    }

    /** Readable seconds: "0,9 s" below 10 s (a 900 ms window used to print "0 s"), whole numbers from 10 s. */
    static String seconds(long ms) {
        return ms >= 10_000 ? (ms / 1000) + " s"
                : String.format(Locale.ROOT, "%.1f s", ms / 1000.0).replace('.', ',');
    }

    /** The EXACT bytes that are signed. */
    public static byte[] checkpointMessage(long chainSeq, String headHash) {
        return checkpointMessageString(chainSeq, headHash).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Canonical message that is signed, published so that an external verifier can reproduce it byte
     * for byte. The prefix is a domain separator (against signature reuse across protocols) + a version.
     */
    public static String checkpointMessageString(long chainSeq, String headHash) {
        return "ledgermind:journal-checkpoint:v1:" + chainSeq + ":" + headHash;
    }

    /** Result of verifying the latest signed checkpoint. See {@link #verifyLatest()} for the semantics of each field. */
    public record CheckpointVerification(boolean present, String algorithm, long chainSeq, String headHash,
                                         boolean signatureValid, boolean chainIntact,
                                         boolean signedHeadStillInChain, boolean isLatestHead,
                                         Instant signedAt) {
        static CheckpointVerification none() {
            return new CheckpointVerification(false, null, 0L, null, false, false, false, false, null);
        }
    }

    /**
     * Consolidated journal audit report (for the MCP tool / endpoint). {@code tamperDetected} = ONLY confirmed
     * evidence. {@code coverageDegraded} + {@code coverageReason} = 'cannot be confirmed right now' (ATRASADO,
     * DETENIDO, SIN_CHECKPOINT), which is NOT tamper. {@code verdict} explains it; the rest are the raw planes. See
     * {@link #audit()}.
     */
    public record JournalIntegrityReport(boolean tamperDetected, String verdict,
                                         boolean coverageDegraded, CoverageReason coverageReason,
                                         boolean chainIntact, long chainedCount, Long brokenAtSeq,
                                         boolean checkpointPresent, String signatureAlgorithm,
                                         long signedChainSeq, String signedHeadHash,
                                         boolean signatureValid, boolean signedHeadStillInChain,
                                         boolean signedHeadIsLatest, Instant signedAt,
                                         boolean balancesConsistent, long accountsChecked,
                                         List<AccountBalanceVerifier.AccountBalanceMismatch> balanceMismatches,
                                         long unchainedPostings, long staleUnchainedPostings,
                                         long unchainedGraceMs) {
    }
}
