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
 * Coverage of the hash-chain vs the journal: the "mint hole".
 *
 * <p>Attack: a DB writer INSERTS a fake posting and adjusts the two cached counters in the same
 * move. Replay == counters, the chain does not visit it (it has no link) and the signature stays valid.
 *
 * <p>Rule (STALE-UNCHAINED): an unlinked posting whose created_at is older than the chainer's legitimate
 * window (max(unchained-grace-ms, 3 x chain-delay-ms)) is tamper. With the jobs switched off in this test
 * (chain-delay 1 h) the effective window is 3 h. An unlinked posting INSIDE the window is reported but is not
 * tamper: it is indistinguishable from a legitimate posting just written. The KNOWN_GAP_* tests pin what the
 * rule can NOT see, so that the MCP tool description cannot claim more than the code does.
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

    /** funding(1, allow_negative) -> a(2) 100000 and -> b(3) 100000; chained and with a signed ML-DSA checkpoint. */
    private void seedChainedAndSigned() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        ledger.transfer("external:funding", "wallet:b", 100_000, "seed-b");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();
    }

    /** The 3-statement attack: INSERT of the fake posting + the two adjusted counters. */
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

    /** Reads a record component by reflection: the test also compiles against the PRE-fix code (a real red). */
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
        throw new AssertionError("JournalIntegrityReport has no component '" + name + "' (pre-fix build)");
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
        ledger.transfer("wallet:a", "wallet:b", 30_000, "fresh-legit");      // the chainer has not run yet
        var r = checkpoints.audit();
        log("fresh-legit", r);
        assertThat(r.tamperDetected()).isFalse();
        assertThat(component(r, "unchainedPostings")).isEqualTo(1);
        assertThat(component(r, "staleUnchainedPostings")).isZero();
        assertThat(r.verdict()).contains("NO EVIDENCE OF EDITING").contains("not chained yet");
    }

    @Test
    void borde_de_la_ventana_dentro_verde_fuera_rojo() {
        seedChainedAndSigned();
        Posting p = ledger.transfer("wallet:a", "wallet:b", 30_000, "edge");
        // effective window in this test = 3 h (3 x chain-delay 1 h). 170 min: inside.
        jdbc.update("UPDATE posting SET created_at = now() - interval '170 minutes' WHERE id = ?", p.getId());
        var inside = checkpoints.audit();
        log("edge-inside", inside);
        assertThat(inside.tamperDetected()).isFalse();
        // 190 min: outside -> the chainer should already have covered it; the audit cannot vouch for it.
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
        assertThat((long) m.invoke(null, 60_000L, 3_600_000L)).isEqualTo(10_800_000L);  // slow chainer
    }

    // ---------------------------------------------------------------- RED: what the rule DOES see

    @Test
    void acunacion_con_asiento_viejo_sin_encadenar_se_detecta() {
        seedChainedAndSigned();
        mint(777_000, "FORGED-STALE", "now() - interval '1 day'");
        assertThat(ledger.getByAddress("wallet:b").availableBalance()).isEqualTo(877_000);
        var r = checkpoints.audit();
        log("stale-mint", r);
        // the three old planes are still clean: what exposes it is the new clause, not another one
        assertThat(r.chainIntact()).isTrue();
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.signatureValid()).isTrue();
        assertThat(r.tamperDetected()).isTrue();
        assertThat(component(r, "staleUnchainedPostings")).isEqualTo(1);
        assertThat(r.verdict()).contains("TAMPER DETECTED").contains("unchained");
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
        assertThat(r.balancesConsistent()).isTrue();          // +10000 / -10000: the net does not move
        assertThat(r.tamperDetected()).isTrue();              // it sees it ONLY through the age without a link
    }

    @Test
    void acunacion_vieja_sin_checkpoint_firmado_tambien_se_detecta() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:b", 100_000, "seed-b");
        chainer.chainPendingPostings();                       // chain linked, NO signed checkpoint
        mint(777_000, "FORGED-STALE-NOCP", "now() - interval '1 day'");
        var r = checkpoints.audit();
        log("stale-mint-no-checkpoint", r);
        assertThat(r.checkpointPresent()).isFalse();
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.tamperDetected()).isTrue();
        assertThat(r.verdict()).contains("TAMPER DETECTED").contains("unchained");
    }

    // ---------------------------------------------------------------- KNOWN GAP: what the rule does NOT see

    @Test
    void KNOWN_GAP_acunacion_reciente_es_indistinguible_de_un_asiento_legitimo_en_ventana() {
        seedChainedAndSigned();
        mint(777_000, "FORGED-FRESH", "now()");
        var r = checkpoints.audit();
        log("fresh-mint", r);
        assertThat(r.tamperDetected()).isFalse();
        assertThat(component(r, "unchainedPostings")).isEqualTo(1);      // at least it stays REPORTED, not silent
        assertThat(r.verdict()).contains("not chained yet").contains("INSERTION");
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

    // ---------------------------------------------------------------- the contract the agent reads

    @Test
    void la_descripcion_del_tool_mcp_nombra_insercion_truncado_y_ventana() throws Exception {
        String d = LedgerMcpTools.class.getMethod("verifyJournalIntegrity").getAnnotation(Tool.class).description();
        assertThat(d).contains("INSERTION").contains("truncation").contains("unchained")
                .contains("unchainedPostings").contains("staleUnchainedPostings");
    }
}
