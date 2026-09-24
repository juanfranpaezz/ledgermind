package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.JournalCheckpointService.JournalIntegrityReport;
import com.ledgermind.ledger.mcp.LedgerMcpTools;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cobertura de la hash-chain vs el journal: el "agujero de acunacion" (mint hole).
 *
 * <p>Ataque: un escritor de DB INSERTA un asiento falso y ajusta en el mismo movimiento los dos contadores
 * cacheados. Replay == contadores, la cadena no lo visita (no tiene eslabon) y la firma sigue valida.
 *
 * <p>Regla (STALE-UNCHAINED): un asiento sin eslabon cuyo created_at es mas viejo que la ventana legitima del
 * encadenador (max(unchained-grace-ms, 3 x chain-delay-ms)) es tamper. Con los jobs apagados en este test
 * (chain-delay 1 h) la ventana efectiva es 3 h. Un asiento sin eslabon DENTRO de la ventana se reporta pero no es
 * tamper: es indistinguible de un asiento legitimo recien posteado. Los tests KNOWN_GAP_* fijan lo que la
 * regla NO puede ver, para que la descripcion del tool MCP no pueda afirmar mas de lo que el codigo hace.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@Testcontainers
class UnchainedPostingCoverageTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalChainer chainer;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
    }

    /** funding(1, allow_negative) -> a(2) 100000 y -> b(3) 100000; encadenado y con checkpoint ML-DSA firmado. */
    private void seedChainedAndSigned() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        ledger.transfer("external:funding", "wallet:b", 100_000, "seed-b");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();
    }

    /** El ataque de 3 sentencias: INSERT del asiento falso + los dos contadores ajustados. */
    private void mint(long amount, String key, String createdAtSql) {
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key, created_at)"
                + " VALUES (1, 3, ?, 'ARS', ?, " + createdAtSql + ")", amount, key);
        jdbc.update("UPDATE account SET posted_debits = posted_debits + ? WHERE id = 1", amount);
        jdbc.update("UPDATE account SET posted_credits = posted_credits + ? WHERE id = 3", amount);
    }

    private static void log(String tag, JournalIntegrityReport r) {
        System.out.println("[COVERAGE][" + tag + "] tamperDetected=" + r.tamperDetected() + " chainIntact="
                + r.chainIntact() + " chainedCount=" + r.chainedCount() + " balancesConsistent="
                + r.balancesConsistent() + " sigValid=" + r.signatureValid());
        System.out.println("[COVERAGE][" + tag + "] verdict=" + r.verdict());
    }

    /** Lee un componente del record por reflexion: el test compila tambien contra el codigo PRE-fix (rojo real). */
    private static long component(Object rec, String name) {
        try {
            for (RecordComponent rc : rec.getClass().getRecordComponents()) {
                if (rc.getName().equals(name)) {
                    return ((Number) rc.getAccessor().invoke(rec)).longValue();
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("JournalIntegrityReport no tiene el componente '" + name + "' (build pre-fix)");
    }

    // ---------------------------------------------------------------- VERDE: sin falsas alarmas

    @Test
    void ledger_limpio_encadenado_y_firmado_queda_verde() {
        seedChainedAndSigned();
        var r = checkpoints.audit();
        log("clean", r);
        assertThat(r.tamperDetected()).isFalse();
        assertThat(component(r, "unchainedPostings")).isZero();
        assertThat(component(r, "staleUnchainedPostings")).isZero();
    }

    @Test
    void asiento_legitimo_recien_posteado_sin_encadenar_queda_verde_pero_se_reporta() {
        seedChainedAndSigned();
        ledger.transfer("wallet:a", "wallet:b", 30_000, "fresh-legit");      // el encadenador aun no corrio
        var r = checkpoints.audit();
        log("fresh-legit", r);
        assertThat(r.tamperDetected()).isFalse();
        assertThat(component(r, "unchainedPostings")).isEqualTo(1);
        assertThat(component(r, "staleUnchainedPostings")).isZero();
        assertThat(r.verdict()).contains("SIN EVIDENCIA DE EDICION").contains("aun sin encadenar");
    }

    @Test
    void borde_de_la_ventana_dentro_verde_fuera_rojo() {
        seedChainedAndSigned();
        Posting p = ledger.transfer("wallet:a", "wallet:b", 30_000, "edge");
        // ventana efectiva en este test = 3 h (3 x chain-delay 1 h). 170 min: adentro.
        jdbc.update("UPDATE posting SET created_at = now() - interval '170 minutes' WHERE id = ?", p.getId());
        var inside = checkpoints.audit();
        log("edge-inside", inside);
        assertThat(inside.tamperDetected()).isFalse();
        // 190 min: afuera -> el encadenador ya deberia haberlo cubierto; la auditoria no lo puede avalar.
        jdbc.update("UPDATE posting SET created_at = now() - interval '190 minutes' WHERE id = ?", p.getId());
        var outside = checkpoints.audit();
        log("edge-outside", outside);
        assertThat(outside.tamperDetected()).isTrue();
        assertThat(component(outside, "staleUnchainedPostings")).isEqualTo(1);
    }

    @Test
    void ventana_efectiva_en_produccion_es_60s_y_nunca_menor_que_3_ciclos_del_encadenador() throws Exception {
        Method m = JournalCheckpointService.class.getDeclaredMethod("effectiveUnchainedGraceMs", long.class, long.class);
        m.setAccessible(true);
        assertThat((long) m.invoke(null, 60_000L, 5_000L)).isEqualTo(60_000L);          // defaults de application
        assertThat((long) m.invoke(null, 60_000L, 3_600_000L)).isEqualTo(10_800_000L);  // encadenador lento
    }

    // ---------------------------------------------------------------- ROJO: lo que la regla SI ve

    @Test
    void acunacion_con_asiento_viejo_sin_encadenar_se_detecta() {
        seedChainedAndSigned();
        mint(777_000, "FORGED-STALE", "now() - interval '1 day'");
        assertThat(ledger.getByAddress("wallet:b").availableBalance()).isEqualTo(877_000);
        var r = checkpoints.audit();
        log("stale-mint", r);
        // los tres planos viejos siguen limpios: lo que lo delata es la clausula nueva, no otra
        assertThat(r.chainIntact()).isTrue();
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.signatureValid()).isTrue();
        assertThat(r.tamperDetected()).isTrue();
        assertThat(component(r, "staleUnchainedPostings")).isEqualTo(1);
        assertThat(r.verdict()).contains("MANIPULACION DETECTADA").contains("sin encadenar");
    }

    @Test
    void hermano_insercion_sin_ajustar_contadores_ya_se_detectaba() {
        seedChainedAndSigned();
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key)"
                + " VALUES (1, 3, 555000, 'ARS', 'FORGED-NOBUMP')");
        var r = checkpoints.audit();
        log("append-no-bump", r);
        assertThat(r.tamperDetected()).isTrue();
        assertThat(r.balancesConsistent()).isFalse();
    }

    @Test
    void edicion_compensada_sobre_dos_asientos_viejos_sin_encadenar_se_detecta() {
        seedChainedAndSigned();
        Posting x = ledger.transfer("wallet:a", "wallet:b", 30_000, "comp-x");
        Posting y = ledger.transfer("wallet:a", "wallet:b", 20_000, "comp-y");
        jdbc.update("UPDATE posting SET amount = 40000, created_at = now() - interval '1 day' WHERE id = ?", x.getId());
        jdbc.update("UPDATE posting SET amount = 10000, created_at = now() - interval '1 day' WHERE id = ?", y.getId());
        var r = checkpoints.audit();
        log("compensating-stale", r);
        assertThat(r.balancesConsistent()).isTrue();          // +10000 / -10000: el neto no se mueve
        assertThat(r.tamperDetected()).isTrue();              // lo ve SOLO por la antiguedad sin eslabon
    }

    @Test
    void acunacion_vieja_sin_checkpoint_firmado_tambien_se_detecta() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:b", 100_000, "seed-b");
        chainer.chainPendingPostings();                       // cadena encadenada, SIN checkpoint firmado
        mint(777_000, "FORGED-STALE-NOCP", "now() - interval '1 day'");
        var r = checkpoints.audit();
        log("stale-mint-no-checkpoint", r);
        assertThat(r.checkpointPresent()).isFalse();
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.tamperDetected()).isTrue();
        assertThat(r.verdict()).contains("MANIPULACION DETECTADA").contains("sin encadenar");
    }

    // ---------------------------------------------------------------- KNOWN GAP: lo que la regla NO ve

    @Test
    void KNOWN_GAP_acunacion_reciente_es_indistinguible_de_un_asiento_legitimo_en_ventana() {
        seedChainedAndSigned();
        mint(777_000, "FORGED-FRESH", "now()");
        var r = checkpoints.audit();
        log("fresh-mint", r);
        assertThat(r.tamperDetected()).isFalse();
        assertThat(component(r, "unchainedPostings")).isEqualTo(1);      // al menos queda REPORTADO, no callado
        assertThat(r.verdict()).contains("aun sin encadenar").contains("INSERCION");
    }

    @Test
    void KNOWN_GAP_acunacion_encadenada_por_el_job_queda_legitimada() {
        seedChainedAndSigned();
        mint(777_000, "FORGED-CHAINED", "now()");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();
        var r = checkpoints.audit();
        log("mint-after-chainer", r);
        assertThat(r.tamperDetected()).isFalse();
        assertThat(component(r, "unchainedPostings")).isZero();
        assertThat(ledger.getByAddress("wallet:b").availableBalance()).isEqualTo(877_000);
    }

    @Test
    void KNOWN_GAP_edicion_compensada_sobre_dos_asientos_recientes_sin_encadenar() {
        seedChainedAndSigned();
        Posting x = ledger.transfer("wallet:a", "wallet:b", 30_000, "comp-fx");
        Posting y = ledger.transfer("wallet:a", "wallet:b", 20_000, "comp-fy");
        jdbc.update("UPDATE posting SET amount = 40000 WHERE id = ?", x.getId());
        jdbc.update("UPDATE posting SET amount = 10000 WHERE id = ?", y.getId());
        var r = checkpoints.audit();
        log("compensating-fresh", r);
        assertThat(r.tamperDetected()).isFalse();
    }

    // ---------------------------------------------------------------- el contrato que lee el agente

    @Test
    void la_descripcion_del_tool_mcp_nombra_insercion_truncado_y_ventana() throws Exception {
        String d = LedgerMcpTools.class.getMethod("verifyJournalIntegrity").getAnnotation(Tool.class).description();
        assertThat(d).contains("INSERCION").contains("truncado").contains("sin encadenar")
                .contains("unchainedPostings").contains("staleUnchainedPostings");
    }
}
