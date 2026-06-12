package com.ledgermind.ledger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Firma periodicamente la cabeza de la hash-chain (Signed Tree Head). Lee el {@link PostingHash} de
 * mayor seq, y si la cabeza cambio desde el ultimo checkpoint, firma un mensaje canonico con
 * {@link JournalSigner} (ML-DSA) y guarda un {@link JournalCheckpoint} inmutable.
 *
 * <p>Corre ASINCRONO, despues del {@link JournalChainer}: la cadena encadena asientos, este servicio
 * la ancla con una firma post-cuantica. Idempotente CON UN UNICO ESCRITOR (compara headHash antes de
 * firmar); el scheduler default es single-thread, asi que no se solapa consigo mismo. En HA (2+ replicas)
 * el {@code UNIQUE (chain_seq)} de la tabla degrada la carrera a un INSERT que falla en la 2da replica.
 */
@Service
public class JournalCheckpointService {

    private final PostingHashRepository hashes;
    private final JournalCheckpointRepository checkpoints;
    private final JournalChainer chainer;
    private final JournalSigner signer;

    public JournalCheckpointService(PostingHashRepository hashes,
                                    JournalCheckpointRepository checkpoints,
                                    JournalChainer chainer,
                                    JournalSigner signer) {
        this.hashes = hashes;
        this.checkpoints = checkpoints;
        this.chainer = chainer;
        this.signer = signer;
    }

    /** Firma la cabeza si avanzo desde el ultimo checkpoint. Async (cada 10s); tambien llamable en tests. */
    @Scheduled(fixedDelayString = "${ledgermind.journal.checkpoint-delay-ms:10000}")
    @Transactional
    public Optional<JournalCheckpoint> checkpointIfHeadAdvanced() {
        PostingHash head = hashes.findTopByOrderBySeqDesc().orElse(null);
        if (head == null) {
            return Optional.empty();                                   // cadena vacia: nada que firmar
        }
        JournalCheckpoint last = checkpoints.findTopByOrderByIdDesc().orElse(null);
        if (last != null && last.getHeadHash().equals(head.getEntryHash())) {
            return Optional.empty();                                   // cabeza sin cambios: ya esta firmada
        }
        byte[] message = checkpointMessage(head.getSeq(), head.getEntryHash());
        String signature = signer.sign(message);
        JournalCheckpoint cp = new JournalCheckpoint(head.getSeq(), head.getEntryHash(),
                signer.algorithm(), signer.publicKeyBase64(), signature);
        try {
            return Optional.of(checkpoints.save(cp));
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // Otro escritor (p.ej. el job @Scheduled vs el reset sincrono de la demo) firmo esta misma
            // cabeza primero y choco con UNIQUE(chain_seq): no-op idempotente, no es un error.
            return Optional.empty();
        }
    }

    /** El ultimo checkpoint (para exponerlo en la API). */
    @Transactional(readOnly = true)
    public Optional<JournalCheckpoint> latest() {
        return checkpoints.findTopByOrderByIdDesc();
    }

    /**
     * Verifica el ultimo checkpoint en planos INDEPENDIENTES, sin conflacionarlos:
     * <ul>
     *   <li>{@code signatureValid}: la firma ML-DSA cierra bajo la clave publica que el checkpoint guarda.
     *       OJO: prueba integridad-de-mensaje (firma vs clave acompañante), NO autenticidad del firmante;
     *       sin un trust anchor externo (clave pinneada/HSM/log de transparencia) NO prueba <i>quien</i> firmo.</li>
     *   <li>{@code chainIntact}: la hash-chain recomputa desde el contenido ACTUAL de los asientos. ESTE es
     *       el tamper-evidence real del CONTENIDO; lo aporta SHA-256, no la firma.</li>
     *   <li>{@code signedHeadStillInChain}: el eslabon firmado (seq == chainSeq) sigue presente con su mismo
     *       entry_hash. Detecta reescritura/borrado de la propia tabla de hashes.</li>
     *   <li>{@code isLatestHead}: la cabeza firmada es ademas la cabeza viva. INFORMATIVO: en operacion normal
     *       es false (la cadena avanza ~10s antes de re-firmar); NO es evidencia de nada por si solo.</li>
     * </ul>
     * Demo clave: tras editar un asiento historico, {@code signatureValid} sigue en true (la firma es sobre
     * la cabeza original) pero {@code chainIntact} cae a false. La firma ANCLA la cabeza en el tiempo; el
     * SHA-256 encadenado es quien delata la alteracion del contenido.
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
     * Auditoria consolidada del journal para un agente (tool MCP / endpoint): combina la integridad de la
     * hash-chain (SHA-256 recomputado) con la validez de la firma post-cuantica del ultimo checkpoint, y
     * resume un veredicto legible. Recorre la cadena UNA sola vez.
     *
     * <p>ALCANCE (lo declara el verdict): detecta EDICION/reescritura de asientos ya encadenados y del
     * eslabon firmado. NO detecta por si solo: (1) el TRUNCADO de la cola posterior al ultimo checkpoint
     * (borrar los asientos mas nuevos deja un prefijo consistente) — eso exige un high-water-mark anclado
     * FUERA de la DB; (2) la AUTENTICIDAD del firmante — {@code signatureValid} es integridad-de-mensaje,
     * no prueba QUIEN firmo sin una clave anclada externamente. Es tamper-EVIDENCE, no prevencion.
     */
    @Transactional(readOnly = true)
    public JournalIntegrityReport audit() {
        JournalChainer.VerifyResult chain = chainer.verify();
        JournalCheckpoint cp = checkpoints.findTopByOrderByIdDesc().orElse(null);
        if (cp == null) {
            boolean tampered = !chain.intact();
            String verdict = tampered
                    ? "MANIPULACION DETECTADA: la hash-chain se rompe en seq " + chain.brokenAtSeq()
                            + " (aun sin checkpoint firmado)."
                    : "SIN CHECKPOINT FIRMADO: la hash-chain presente recomputa consistente sobre "
                            + chain.chainedCount() + " asientos, pero sin un checkpoint firmado que ancle la"
                            + " cabeza NO se puede descartar un truncado/rollback previo. Aun no hay firma ML-DSA.";
            return new JournalIntegrityReport(tampered, verdict, chain.intact(), chain.chainedCount(),
                    chain.brokenAtSeq(), false, null, 0L, null, false, false, false, null);
        }
        Signals s = signalsFor(cp);
        boolean tampered = !chain.intact() || !s.signatureValid() || !s.signedHeadStillInChain();
        return new JournalIntegrityReport(tampered, verdict(chain, cp, s, tampered),
                chain.intact(), chain.chainedCount(), chain.brokenAtSeq(),
                true, cp.getAlgorithm(), cp.getChainSeq(), cp.getHeadHash(),
                s.signatureValid(), s.signedHeadStillInChain(), s.isLatestHead(), cp.getSignedAt());
    }

    /** Señales del checkpoint que NO requieren recomputar toda la cadena (firma + presencia + si es la cabeza). */
    private Signals signalsFor(JournalCheckpoint cp) {
        boolean signatureValid = signer.verify(
                checkpointMessage(cp.getChainSeq(), cp.getHeadHash()), cp.getSignature(), cp.getPublicKey());
        boolean signedHeadStillInChain = hashes.findBySeq(cp.getChainSeq())
                .map(h -> h.getEntryHash().equals(cp.getHeadHash()))
                .orElse(false);
        PostingHash liveHead = hashes.findTopByOrderBySeqDesc().orElse(null);
        boolean isLatestHead = liveHead != null && liveHead.getEntryHash().equals(cp.getHeadHash());
        return new Signals(signatureValid, signedHeadStillInChain, isLatestHead);
    }

    private static String verdict(JournalChainer.VerifyResult chain, JournalCheckpoint cp,
                                  Signals s, boolean tampered) {
        if (!tampered) {
            return "SIN EVIDENCIA DE EDICION: la hash-chain recomputa limpia sobre " + chain.chainedCount()
                    + " asientos y la firma del ultimo checkpoint (" + cp.getAlgorithm() + ", seq "
                    + cp.getChainSeq() + ", firmado " + cp.getSignedAt() + ") cierra bajo la clave que el"
                    + " propio checkpoint guarda (integridad-de-mensaje, NO autenticidad: probar QUIEN firmo"
                    + " exige una clave anclada fuera de la DB). No descarta el truncado de la cola posterior"
                    + " al checkpoint. Tamper-EVIDENCE, no prevencion.";
        }
        StringBuilder sb = new StringBuilder("MANIPULACION DETECTADA:");
        if (!chain.intact()) {
            sb.append(" la hash-chain se rompe en seq ").append(chain.brokenAtSeq())
                    .append(" (un asiento fue editado o borrado tras encadenarse);");
        }
        if (!s.signatureValid()) {
            sb.append(" la firma del checkpoint no verifica bajo su clave;");
        }
        if (!s.signedHeadStillInChain()) {
            sb.append(" el eslabon firmado (seq ").append(cp.getChainSeq()).append(") fue reescrito;");
        }
        return sb.toString();
    }

    private record Signals(boolean signatureValid, boolean signedHeadStillInChain, boolean isLatestHead) {
    }

    /** Bytes EXACTOS que se firman. */
    public static byte[] checkpointMessage(long chainSeq, String headHash) {
        return checkpointMessageString(chainSeq, headHash).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Mensaje canonico que se firma, publicado para que un verificador externo lo reproduzca byte a
     * byte. El prefijo es un dominio de separacion (anti reuse de firma entre protocolos) + version.
     */
    public static String checkpointMessageString(long chainSeq, String headHash) {
        return "ledgermind:journal-checkpoint:v1:" + chainSeq + ":" + headHash;
    }

    /** Resultado de verificar el ultimo checkpoint firmado. Ver {@link #verifyLatest()} por la semantica de cada campo. */
    public record CheckpointVerification(boolean present, String algorithm, long chainSeq, String headHash,
                                         boolean signatureValid, boolean chainIntact,
                                         boolean signedHeadStillInChain, boolean isLatestHead,
                                         Instant signedAt) {
        static CheckpointVerification none() {
            return new CheckpointVerification(false, null, 0L, null, false, false, false, false, null);
        }
    }

    /**
     * Informe de auditoria consolidado del journal (para tool MCP / endpoint). {@code tamperDetected} y
     * {@code verdict} resumen el dictamen; el resto son los planos en crudo. Ver {@link #audit()}.
     */
    public record JournalIntegrityReport(boolean tamperDetected, String verdict,
                                         boolean chainIntact, long chainedCount, Long brokenAtSeq,
                                         boolean checkpointPresent, String signatureAlgorithm,
                                         long signedChainSeq, String signedHeadHash,
                                         boolean signatureValid, boolean signedHeadStillInChain,
                                         boolean signedHeadIsLatest, Instant signedAt) {
    }
}
