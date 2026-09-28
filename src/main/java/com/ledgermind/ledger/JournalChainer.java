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
 * Hash-chain del journal (tamper-evidence, patron AWS QLDB).
 *
 * <p>Recorre los asientos nuevos por id y los encadena en {@code posting_hash} (append-only):
 * {@code entry_hash = SHA-256(prev_hash || canonical(posting))}. Corre ASINCRONO (fuera del hot
 * path de las transferencias) para no serializar la concurrencia ya lograda. {@link #verify()}
 * recomputa la cadena desde el contenido ACTUAL de los asientos: si alguien edita uno viejo, su
 * hash deja de cuadrar y se detecta el punto exacto de la ruptura.
 */
@Component
public class JournalChainer {

    private static final String GENESIS = "0".repeat(64);
    private static final int BATCH = 200;

    private final PostingRepository postings;
    private final PostingHashRepository hashes;

    private final JdbcTemplate jdbc;

    // Estado del encadenador para el verdict de la auditoria. Lo que vale es lo COMMITEADO: cada corrida escribe
    // journal_chainer_state (inicio, foto, fin, si lleno el lote) en la MISMA transaccion que sus eslabones, asi la
    // auditoria lo lee en su misma foto. (Antes era un sello en memoria tomado ANTES del commit: durante un commit
    // lento la auditoria veia "cola vaciada" con los eslabones todavia invisibles.) En memoria queda solo lo que no se
    // puede commitear: cuando arranco esta JVM y si hay una corrida EN CURSO (para no llamar DETENIDO a una lenta).
    private final Instant bootedAt = Instant.now();
    private volatile Instant runningSince;
    private volatile Instant lastCommittedAt;

    /**
     * Vida del encadenador en esta JVM (solo para DETENIDO vs ATRASADO, nunca para evidencia): arranque, corrida en curso
     * ({@code null} = ninguna) y ultimo commit visto DESPUES de commitear (el run_finished_at de la DB se toma antes del
     * flush de los eslabones, que bajo carga puede tardar).
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

    /** Encadena los asientos pendientes. Async (cada 5s); tambien se puede llamar directo (tests). */
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
                    runningSince = null;                // despues del commit (o rollback), no antes
                }
            });
        }
        // xid de ESTA pasada, tomado antes de leer la cola: todo xid mayor se asigno despues (lo usa la auditoria). No
        // sirve pg_snapshot_xmax: es el ultimo xid COMPLETADO + 1, y una transaccion abierta con xid mayor quedaria
        // como "posterior" aunque ya existiera.
        Long passXid = jdbc.queryForObject("SELECT pg_current_xact_id()::text::bigint", Long.class);
        PostingHash head = hashes.findTopByOrderBySeqDesc().orElse(null);
        long seq = head != null ? head.getSeq() : 0L;
        String prev = head != null ? head.getEntryHash() : GENESIS;
        // Por AUSENCIA en posting_hash (no por watermark de id): un asiento con id menor que commitea
        // tarde no queda sin encadenar (antes, findByIdGreaterThan lo salteaba para siempre).
        List<Posting> pending = postings.findUnchainedOrderByIdAsc(Limit.of(BATCH));
        for (Posting p : pending) {
            String entry = entryHash(prev, p);
            hashes.save(new PostingHash(p.getId(), ++seq, prev, entry));
            prev = entry;
        }
        // Estado COMMITEADO: en la MISMA transaccion que los eslabones (visible para la auditoria solo junto con ellos).
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
                    return new VerifyResult(false, checked, link.getSeq());       // asiento borrado
                }
                if (!prev.equals(link.getPrevHash()) || !entryHash(prev, p).equals(link.getEntryHash())) {
                    return new VerifyResult(false, checked, link.getSeq());       // contenido alterado / cadena rota
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
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Resultado de verificar la integridad de la cadena. */
    public record VerifyResult(boolean intact, long chainedCount, Long brokenAtSeq) {
    }
}
