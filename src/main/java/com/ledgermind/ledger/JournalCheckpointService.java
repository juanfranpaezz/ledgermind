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
 * Firma periodicamente la cabeza de la hash-chain (Signed Tree Head). Lee el {@link PostingHash} de
 * mayor seq, y si la cabeza cambio desde el ultimo checkpoint, firma un mensaje canonico con el firmante
 * ACTIVO (resuelto por {@link JournalSignerRegistry}; default ML-DSA-65) y guarda un {@link JournalCheckpoint}
 * inmutable que persiste el algoritmo + la clave publica usados.
 *
 * <p>Crypto-agility (firma + verificacion). FIRMA: el esquema activo es configurable
 * ({@code ledgermind.journal.signer.algorithm}) y se persiste por checkpoint. VERIFICACION: se DESPACHA por
 * el {@code algorithm} del checkpoint via {@link JournalSignerRegistry} -> cada checkpoint se verifica con
 * SU esquema, soportando >1 algoritmo en paralelo y permitiendo rotar sin dejar ciegos los checkpoints viejos.
 *
 * <p>Corre ASINCRONO, despues del {@link JournalChainer}: la cadena encadena asientos, este servicio
 * la ancla con una firma. Idempotente CON UN UNICO ESCRITOR (compara headHash antes de
 * firmar); el scheduler default es single-thread, asi que no se solapa consigo mismo. En HA (2+ replicas)
 * el {@code UNIQUE (chain_seq)} de la tabla degrada la carrera a un INSERT que falla en la 2da replica.
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
    /** Ventana en la que un asiento sin eslabon es legitimo; ver {@link #effectiveUnchainedGraceMs}. */
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
     * Ventana en la que un asiento SIN eslabon es legitimo (ya posteado, el encadenador aun no paso). El
     * encadenador corre con fixedDelay = chain-delay-ms y encadena de a 200 por ausencia: un asiento que commitea
     * justo despues de que arranca una corrida espera <= 1 ciclo + lo que dure esa corrida. 3 ciclos dan margen
     * para una corrida lenta o un backlog de ~2 lotes; el piso (unchained-grace-ms, default 60 s) evita que un
     * chain-delay chico vuelva nerviosa la regla. Nunca baja de 3 ciclos: si alguien sube chain-delay sin tocar
     * el grace, la regla no dispara sobre asientos legitimos.
     */
    static long effectiveUnchainedGraceMs(long configuredGraceMs, long chainDelayMs) {
        return Math.max(configuredGraceMs, 3 * chainDelayMs);
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
        // Firma con el esquema ACTIVO (configurable; default ML-DSA-65). El algoritmo y la clave publica
        // se persisten EN el checkpoint -> la verificacion despacha por ese nombre, no por el firmante de HOY.
        JournalSigner signer = signers.activeSigner(activeAlgorithm);
        byte[] message = checkpointMessage(head.getSeq(), head.getEntryHash());
        String signature = signer.sign(message);
        JournalCheckpoint cp = new JournalCheckpoint(head.getSeq(), head.getEntryHash(),
                signer.algorithm(), signer.publicKeyBase64(), signature);
        try {
            return Optional.of(checkpoints.save(cp));
        } catch (DataIntegrityViolationException raced) {
            // Esperado SOLO si otro escritor (el job @Scheduled vs el reset sincrono de la demo) firmo esta
            // misma cabeza primero y choco con UNIQUE(chain_seq): no-op idempotente. Pero el catch es por TIPO:
            // se logea para que una causa INESPERADA (otra constraint) quede VISIBLE en vez de tragarse en
            // silencio mientras audit() seguiria reportando el checkpoint viejo como valido.
            log.debug("checkpoint no insertado para seq {} (probable carrera benigna por UNIQUE(chain_seq)): {}",
                    head.getSeq(), raced.getMostSpecificCause().getMessage());
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
     * no prueba QUIEN firmo sin una clave anclada externamente; (3) la INSERCION de un asiento con created_at
     * reciente y los contadores ajustados: dentro de la ventana del encadenador es indistinguible de un asiento
     * legitimo recien posteado, y despues el encadenador lo encadena como legitimo. SI detecta un asiento sin
     * eslabon mas viejo que esa ventana ({@code staleUnchainedPostings}) y lo cuenta como tamper SOLO si lo escribio
     * una transaccion que empezo despues de la ultima pasada confirmada del encadenador (ver {@link #coverage}); el
     * resto es {@code coverageDegraded}. Es tamper-EVIDENCE, no prevencion.
     *
     * <p>Corre en UNA foto REPEATABLE READ: la hash-chain, el replay de saldos, los contadores y el estado commiteado
     * del encadenador se leen en el mismo instante, asi una transferencia que confirma a mitad de la auditoria no
     * descuadra saldos (bajo READ COMMITTED si los descuadraba).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JournalIntegrityReport audit() {
        Instant auditStart = Instant.now();
        JournalChainer.VerifyResult chain = chainer.verify();
        // Los contadores de saldo NUNCA se recomputan en el camino de escritura (se adelantan con +=), asi
        // que el read-model puede quedar en desacuerdo con el journal aunque la hash-chain no se rompa.
        // Se re-derivan ACA, dentro del mismo veredicto que ya leen la API, el tool MCP y la demo: un
        // chequeo que el verdict no mira es decorativo.
        AccountBalanceVerifier.BalanceVerifyResult bal = balances.verify();
        // COBERTURA: el replay de saldos recorre TODOS los asientos; la hash-chain solo los que tienen eslabon, asi
        // que un asiento sin eslabon es invisible para chainIntact. Dentro de la ventana legitima del encadenador es
        // normal (se REPORTA, no es tamper); fuera de ella es tamper SOLO con evidencia (coverage), si no cobertura degradada. Solo
        // empuja hacia tamper, nunca lo apaga. Limite: un asiento insertado con created_at reciente es indistinguible
        // de uno legitimo, y el encadenador lo encadena como legitimo (cerrarlo exige procedencia fuera de la DB).
        Coverage cov = coverage(auditStart);
        JournalCheckpoint cp = checkpoints.findTopByOrderByIdDesc().orElse(null);
        if (cp == null) {
            boolean tampered = !chain.intact() || !bal.consistent() || cov.outsideApp() > 0;
            String verdict;
            if (!tampered) {
                verdict = coverageAlert(cov) + "SIN CHECKPOINT FIRMADO: la hash-chain presente recomputa consistente sobre "
                        + chain.chainedCount() + " asientos y los contadores de saldo de "
                        + bal.accountsChecked() + " cuenta(s) cierran contra el replay del journal, pero sin"
                        + " un checkpoint firmado que ancle la cabeza NO se puede descartar un"
                        + " truncado/rollback previo. Aun no hay firma ML-DSA." + coverageNote(cov);
            } else {
                StringBuilder sb = new StringBuilder(TAMPER_HEADLINE);
                sb.append(" SIN CHECKPOINT FIRMADO TODAVIA: signatureValid y signedHeadStillInChain en false"
                        + " significan 'no aplica' (no hay firma que verificar), NO manipulacion;");
                if (!chain.intact()) {
                    sb.append(" la hash-chain se rompe en seq ").append(chain.brokenAtSeq())
                            .append(" (aun sin checkpoint firmado);");
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

    /** Titular de un verdict con evidencia CONFIRMADA de manipulacion. */
    static final String TAMPER_HEADLINE = "MANIPULACION DETECTADA:";

    /**
     * Cuantos asientos NO cubre la hash-chain, cuantos ya exceden la ventana legitima del encadenador, y cuantos de esos
     * son EVIDENCIA de escritura por fuera de la app. Todo en la foto de la auditoria (REPEATABLE READ).
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

    /** Ultima corrida COMMITEADA del encadenador, leida en la foto de la auditoria ({@code null} = ninguna). */
    private ChainerRun committedChainerRun() {
        List<ChainerRun> rows = jdbc.query("SELECT run_started_at, pass_xid, run_finished_at"
                        + " FROM journal_chainer_state WHERE id = 1",
                (rs, n) -> new ChainerRun(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getLong(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant()));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * EVIDENCIA de escritura por fuera de la app: asientos sin eslabon (1) escritos por una transaccion cuyo xid se
     * asigno DESPUES de la ultima pasada commiteada del encadenador (xmin mayor que el pass_xid de esa pasada) y
     * (2) con created_at anterior al inicio de esa pasada menos la ventana. Una transferencia legitima fija created_at
     * en Java justo antes de su INSERT (que le asigna el xid), asi que (1) implica created_at posterior al inicio de la
     * pasada y no puede cumplir (2). Una legitima LENTA (contencion, reintentos, deadlock) que seguia abierta cuando
     * paso el encadenador ya tenia su xid: no cumple (1). Un UPDATE posterior a la pasada tambien cambia xmin: una
     * edicion por fuera que envejece la fecha cuenta igual. xmin es de 32 bits: se compara en aritmetica circular
     * contra el pass_xid de 64 bits reducido (los xid especiales 0-2, p.ej. congelados, no cuentan).
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

    /** Corrida commiteada del encadenador: inicio (reloj de la app), su xid y fin. */
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
     * POR QUE hay asientos sin eslabon mas viejos que la ventana que NO son evidencia de escritura por fuera. DETENIDO =
     * ninguna actividad del encadenador (corrida commiteada, corrida en curso o arranque de esta JVM) hace mas de 3
     * ciclos. ATRASADO = vivo, con backlog o con transacciones legitimas que confirmaron despues de su ultima pasada:
     * transitorio.
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

    /** Por que la auditoria NO puede confirmar ahora (no es tamper). {@code null} = cobertura completa. */
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
     * ¿La clave publica guardada en el checkpoint es del esquema que el checkpoint DECLARA? Compara el OID de
     * algoritmo del SubjectPublicKeyInfo (X.509) de esa clave contra el de la clave del firmante registrado para el
     * esquema declarado. Algoritmo NO registrado -> true aca a proposito: el {@code signers.verify} que sigue falla
     * RUIDOSO (estructural, no tamper). Clave guardada que no parsea como X.509 -> {@link IllegalStateException}
     * (estructural), con la misma disciplina que los firmantes.
     */
    private boolean keyMatchesDeclaredAlgorithm(JournalCheckpoint cp) {
        if (!signers.supports(cp.getAlgorithm())) {
            return true;
        }
        // activeSigner(nombre) es la busqueda por nombre del registry; aca solo se usa su clave publica.
        String registeredKey = signers.activeSigner(cp.getAlgorithm()).publicKeyBase64();
        return keyAlgorithmOid(registeredKey).equals(keyAlgorithmOid(cp.getPublicKey()));
    }

    private static ASN1ObjectIdentifier keyAlgorithmOid(String publicKeyBase64) {
        try {
            return SubjectPublicKeyInfo.getInstance(Base64.getDecoder().decode(publicKeyBase64))
                    .getAlgorithm().getAlgorithm();
        } catch (IllegalArgumentException structural) {
            throw new IllegalStateException("La clave publica del checkpoint no es un SubjectPublicKeyInfo X.509 valido"
                    + " (causa estructural, no evidencia de tamper)", structural);
        }
    }

    /** Señales del checkpoint que NO requieren recomputar toda la cadena (firma + presencia + si es la cabeza). */
    private Signals signalsFor(JournalCheckpoint cp) {
        // DISPATCH POR ALGORITMO (crypto-agility): se verifica con el esquema que el PROPIO checkpoint registro
        // (cp.getAlgorithm()), NO con el firmante activo de hoy; asi un checkpoint viejo se sigue verificando tras
        // una rotacion. Un algoritmo NO registrado falla RUIDOSO en el registry (estructural, no tamper).
        // Y el ALGORITMO declarado sigue DENTRO del lazo de verificacion: si un escritor de DB reescribe SOLO la
        // columna `algorithm` hacia OTRO esquema registrado (firma y clave intactas), el dispatch le pasaria una
        // clave ML-DSA al verificador Ed25519, que la rechaza como falla ESTRUCTURAL (excepcion) y no como tamper.
        // Por eso, antes de despachar, la clave publica guardada tiene que ser del esquema declarado (ver
        // keyMatchesDeclaredAlgorithm): si no lo es, la metadata del checkpoint miente -> signatureValid = false.
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
            return coverageAlert(cov) + "SIN EVIDENCIA DE EDICION: los contadores de saldo de " + bal.accountsChecked()
                    + " cuenta(s) recomputan iguales al replay de " + bal.postingsReplayed()
                    + " asiento(s), la hash-chain recomputa limpia sobre " + chain.chainedCount()
                    + " asientos y la firma del ultimo checkpoint (" + cp.getAlgorithm() + ", seq "
                    + cp.getChainSeq() + ", firmado " + cp.getSignedAt() + ") cierra bajo la clave que el"
                    + " propio checkpoint guarda (integridad-de-mensaje, NO autenticidad: probar QUIEN firmo"
                    + " exige una clave anclada fuera de la DB). No descarta el truncado de la cola posterior"
                    + " al checkpoint ni la INSERCION de un asiento con sus contadores ajustados (el encadenador"
                    + " lo encadena como legitimo)." + coverageNote(cov) + " Tamper-EVIDENCE, no prevencion.";
        }
        StringBuilder sb = new StringBuilder(TAMPER_HEADLINE);
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
        appendBalanceClause(sb, bal);
        appendCoverageClause(sb, cov);
        return sb.toString();
    }

    /**
     * Agrega al verdict el descuadre contador-vs-journal: la cuenta, los DOS numeros y la diferencia. El
     * saldo cacheado y el journal pueden discrepar SIN que la cadena se rompa (p.ej. si el asiento editado
     * todavia no estaba encadenado), asi que esta clausula no es redundante con la de la hash-chain.
     */
    private static void appendBalanceClause(StringBuilder sb, AccountBalanceVerifier.BalanceVerifyResult bal) {
        if (bal.consistent()) {
            return;
        }
        sb.append(" los contadores de saldo NO cierran contra el journal en ")
                .append(bal.mismatches().size()).append(" cuenta(s) [")
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

    /** Frase del verdict limpio cuando hay asientos sin eslabon DENTRO de la ventana (no tamper, pero no cubiertos). */
    private static String coverageNote(Coverage cov) {
        if (cov.unchained() == 0 || cov.staleUnchained() > 0) {       // degradada o tamper: lo explica otra clausula
            return "";
        }
        return " OJO: " + cov.unchained() + " asiento(s) aun sin encadenar, dentro de la ventana normal del"
                + " encadenador (" + cov.graceMs() / 1000 + " s): su contenido todavia NO esta cubierto por la"
                + " hash-chain, y en esa ventana una INSERCION hecha por un escritor de DB se ve igual que un"
                + " asiento legitimo.";
    }

    /**
     * Clausulas de cobertura del verdict: primero la EVIDENCIA (asientos escritos despues de la ultima pasada del
     * encadenador con fecha vieja), despues los asientos viejos sin eslabon que NO son evidencia (encadenador detenido o
     * atrasado).
     */
    static void appendCoverageClause(StringBuilder sb, Coverage cov) {
        if (cov.outsideApp() > 0) {
            sb.append(" ").append(cov.outsideApp()).append(" asiento(s) sin encadenar los escribio una transaccion que")
                    .append(" empezo DESPUES de la ultima pasada confirmada del encadenador, con una fecha mas vieja")
                    .append(" que esa pasada menos la ventana (").append(seconds(cov.graceMs())).append("): una")
                    .append(" transaccion legitima pone la fecha al escribir, asi que es senal de un asiento insertado")
                    .append(" por fuera de la app con fecha vieja (o editado por fuera despues de esa pasada). Es")
                    .append(" transitoria: la proxima corrida lo encadena como legitimo;");
        }
        long pending = cov.staleUnchained() - cov.outsideApp();
        if (pending <= 0) {
            return;
        }
        sb.append(" ").append(pending).append(" asiento(s) llevan mas de ").append(seconds(cov.graceMs()))
                .append(" sin encadenar (la hash-chain todavia no los avala; NO es evidencia de manipulacion): ");
        switch (cov.cause()) {
            case CHAINER_STOPPED -> sb.append("el encadenador esta DETENIDO o bloqueado (sin actividad hace ")
                    .append(seconds(cov.chainerIdleMs())).append(", mas de 3 ciclos). Mientras siga asi, un asiento")
                    .append(" insertado por un escritor de DB no se distingue de uno legitimo: revisar el encadenador")
                    .append(" y re-auditar;");
            default -> sb.append("el encadenador esta ATRASADO (backlog, o transacciones legitimas que confirmaron")
                    .append(" despues de su ultima pasada; ").append(cov.unchained())
                    .append(" asiento(s) en cola). Es TRANSITORIO: re-auditar en ~")
                    .append(Math.max(1, cov.catchUpMs() / 1000)).append(" s, cuando vacie la cola. Mientras dure,")
                    .append(" una insercion de un escritor de DB cuya transaccion ya estaba abierta cuando paso el")
                    .append(" encadenador no se distingue de una legitima;");
        }
    }

    /** Titular + clausula cuando la cobertura esta degradada SIN evidencia de manipulacion ("" si no lo esta). */
    private static String coverageAlert(Coverage cov) {
        if (cov.staleUnchained() - cov.outsideApp() <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("ALERTA DE COBERTURA (no es evidencia de manipulacion):");
        appendCoverageClause(sb, cov);
        return sb.append(" ").toString();
    }

    /** Segundos legibles: "0,9 s" bajo 10 s (una ventana de 900 ms se imprimia "0 s"), enteros desde 10 s. */
    static String seconds(long ms) {
        return ms >= 10_000 ? (ms / 1000) + " s"
                : String.format(Locale.ROOT, "%.1f s", ms / 1000.0).replace('.', ',');
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
     * Informe de auditoria consolidado del journal (para tool MCP / endpoint). {@code tamperDetected} = SOLO evidencia
     * confirmada. {@code coverageDegraded} + {@code coverageReason} = 'ahora no se puede confirmar' (ATRASADO,
     * DETENIDO, SIN_CHECKPOINT), que NO es tamper. {@code verdict} lo explica; el resto son los planos en crudo. Ver
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
