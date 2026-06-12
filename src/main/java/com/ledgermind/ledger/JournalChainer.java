package com.ledgermind.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    public JournalChainer(PostingRepository postings, PostingHashRepository hashes) {
        this.postings = postings;
        this.hashes = hashes;
    }

    /** Encadena los asientos pendientes. Async (cada 5s); tambien se puede llamar directo (tests). */
    @Scheduled(fixedDelayString = "${ledgermind.journal.chain-delay-ms:5000}")
    @Transactional
    public void chainPendingPostings() {
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
    }

    /**
     * Recorre la cadena y recomputa cada hash desde el contenido ACTUAL del asiento. Pagina por seq
     * (keyset) y carga los asientos del lote en UNA query (sin N+1 ni full-load en memoria), para que
     * verificar no sea O(n) en round-trips ni reviente el heap. A escala real (millones de asientos) el
     * paso siguiente es Merkle + verificacion incremental desde el ultimo checkpoint.
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
