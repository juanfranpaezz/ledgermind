package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.JournalCheckpointService.Coverage;
import com.ledgermind.ledger.JournalCheckpointService.CoverageReason;
import com.ledgermind.ledger.JournalCheckpointService.JournalIntegrityReport;
import com.ledgermind.ledger.JournalCheckpointService.StaleCause;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * El verdict de cobertura tiene que decir la CAUSA verdadera de un asiento sin eslabon viejo: encadenador detenido,
 * encadenador atrasado por carga (transitorio), o un asiento que aparecio despues de que el encadenador vacio su cola
 * (la unica senal de insercion por fuera de la app). Antes el verdict decia "o se insertaron por fuera de la app o el
 * encadenador esta detenido" aun bajo una rafaga legitima sin ninguna manipulacion: las dos mitades eran falsas.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000"
})
@Testcontainers
class ChainerStateVerdictTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final long DELAY = 5_000L;
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalChainer chainer;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
    }

    private static Coverage cov(StaleCause cause, long unchained, long stale, long outside, long idleMs, long catchUpMs) {
        return new Coverage(unchained, stale, outside, 60_000L, cause, idleMs, catchUpMs);
    }

    private static String clause(Coverage cov) {
        StringBuilder sb = new StringBuilder();
        JournalCheckpointService.appendCoverageClause(sb, cov);
        return sb.toString();
    }

    // ------------------------------------------------------------ causas y motivo de cobertura, con reloj fijo

    @Test
    void estado_1_detenido_sin_actividad_hace_mas_de_3_ciclos() {
        assertThat(JournalCheckpointService.classifyStale(5, NOW, NOW.minusMillis(3 * DELAY + 1), DELAY))
                .isEqualTo(StaleCause.CHAINER_STOPPED);
        Coverage c = cov(StaleCause.CHAINER_STOPPED, 5, 5, 0, 16_000, 5_000);
        assertThat(JournalCheckpointService.coverageReason(c, true)).isEqualTo(CoverageReason.DETENIDO);
        assertThat(clause(c)).contains("DETENIDO").contains("16 s").doesNotContain("por fuera")
                .doesNotContain("senal de un asiento insertado");
    }

    @Test
    void estado_2_atrasado_es_transitorio_con_ventana_y_no_es_tamper() {
        assertThat(JournalCheckpointService.classifyStale(1170, NOW, NOW.minusMillis(DELAY), DELAY))
                .isEqualTo(StaleCause.CHAINER_BEHIND);
        Coverage c = cov(StaleCause.CHAINER_BEHIND, 3200, 1170, 0, 1_000, 80_000);
        assertThat(JournalCheckpointService.coverageReason(c, false)).isEqualTo(CoverageReason.ATRASADO);
        assertThat(clause(c)).contains("ATRASADO").contains("TRANSITORIO").contains("re-auditar en ~80 s")
                .contains("NO es evidencia de manipulacion").doesNotContain("por fuera").doesNotContain("DETENIDO");
    }

    @Test
    void estado_3_evidencia_de_escritura_posterior_a_la_pasada_y_sin_checkpoint() {
        assertThat(JournalCheckpointService.classifyStale(0, NOW, NOW.minusSeconds(3600), DELAY))
                .isEqualTo(StaleCause.NONE);
        Coverage c = cov(StaleCause.NONE, 1, 1, 1, 5_000, 5_000);
        assertThat(clause(c)).contains("insertado por fuera de la app").doesNotContain("DETENIDO")
                .doesNotContain("ATRASADO");
        assertThat(JournalCheckpointService.coverageReason(c, true)).isNull();
        assertThat(JournalCheckpointService.coverageReason(c, false)).isEqualTo(CoverageReason.SIN_CHECKPOINT);
        assertThat(JournalCheckpointService.seconds(900)).isEqualTo("0,9 s");      // antes "0 s"
    }

    // ------------------------------------------------------------ integracion sobre postgres real

    @Test
    void rafaga_legitima_sin_manipulacion_no_dice_insercion_por_fuera() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        int n = 450;
        for (int i = 0; i < n; i++) {
            ledger.transfer("external:funding", "wallet:a", 10, "burst-" + i);
        }
        // Simula el paso del tiempo (ventana efectiva de este test = 3 h) ANTES de la pasada: nadie toca montos,
        // cuentas ni contadores. (Un UPDATE de fecha DESPUES de la pasada es, correctamente, evidencia de escritura
        // por fuera: por eso la simulacion va antes.)
        int shifted = jdbc.update("UPDATE posting SET created_at = created_at - interval '4 hours'");
        assertThat(shifted).isEqualTo(n);
        chainer.chainPendingPostings();                   // encadena 200 de 450: lleno el lote -> backlog
        assertThat(jdbc.queryForObject("SELECT hit_batch_limit FROM journal_chainer_state WHERE id = 1",
                Boolean.class)).isTrue();

        JournalIntegrityReport r = checkpoints.audit();
        System.out.println("[VERDICT][burst] tamper=" + r.tamperDetected() + " verdict=" + r.verdict());
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.chainIntact()).isTrue();
        assertThat(r.staleUnchainedPostings()).isEqualTo(n - 200);
        assertThat(r.tamperDetected()).isFalse();         // decision del dueno: tamper = solo evidencia confirmada
        assertThat(r.coverageDegraded()).isTrue();
        assertThat(r.coverageReason()).isEqualTo(CoverageReason.ATRASADO);
        assertThat(r.verdict()).doesNotContain("por fuera").doesNotContain("senal de un asiento insertado")
                .doesNotContain("MANIPULACION DETECTADA").contains("ALERTA DE COBERTURA").contains("ATRASADO")
                .contains("TRANSITORIO").contains("SIN CHECKPOINT FIRMADO");

        chainer.chainPendingPostings();
        chainer.chainPendingPostings();                   // 200 + 50: vacia la cola
        JournalIntegrityReport after = checkpoints.audit();
        System.out.println("[VERDICT][burst-drained] tamper=" + after.tamperDetected() + " verdict=" + after.verdict());
        assertThat(after.tamperDetected()).isFalse();
        assertThat(after.unchainedPostings()).isZero();
        assertThat(after.coverageReason()).isEqualTo(CoverageReason.SIN_CHECKPOINT);   // ya no atrasado; sin firma aun
    }

    @Test
    void asiento_viejo_que_aparece_despues_de_que_el_encadenador_vacio_la_cola_es_senal_de_insercion() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        chainer.chainPendingPostings();                   // vacia la cola (1 < 200); aun sin checkpoint firmado
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key, created_at)"
                + " VALUES (1, 3, 777000, 'ARS', 'FORGED-OLD', now() - interval '1 day')");
        jdbc.update("UPDATE account SET posted_debits = posted_debits + 777000 WHERE id = 1");
        jdbc.update("UPDATE account SET posted_credits = posted_credits + 777000 WHERE id = 3");

        JournalIntegrityReport r = checkpoints.audit();
        System.out.println("[VERDICT][passed] tamper=" + r.tamperDetected() + " verdict=" + r.verdict());
        assertThat(r.tamperDetected()).isTrue();
        assertThat(r.checkpointPresent()).isFalse();
        assertThat(r.verdict()).contains("MANIPULACION DETECTADA").contains("insertado")
                .contains("por fuera de la app").contains("SIN CHECKPOINT FIRMADO TODAVIA").contains("no aplica")
                .doesNotContain("DETENIDO").doesNotContain("ATRASADO");
    }

    /**
     * Causa 4 del gate 2026-09-24 (la que dio 13 de 80 "por fuera" en una rafaga limpia): una transaccion LEGITIMA que
     * sigue abierta mientras pasa el encadenador (contencion, reintentos, esperas de deadlock) y confirma despues con un
     * created_at mas viejo que la ventana. No es evidencia de insercion por fuera: su xid ya existia cuando el
     * encadenador tomo su foto. Deterministico: la misma transaccion (mismo xid) envejece su created_at 5 h.
     */
    @Test
    void transaccion_legitima_abierta_durante_la_pasada_del_encadenador_no_es_insercion_por_fuera() throws Exception {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        chainer.chainPendingPostings();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate outer = new TransactionTemplate(txManager);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<?> slow = ex.submit(() -> outer.executeWithoutResult(s -> {
            Posting p = ledger.transfer("external:funding", "wallet:a", 5_000, "slow-legit");
            jdbc.update("UPDATE posting SET created_at = now() - interval '5 hours' WHERE id = ?", p.getId());
            inserted.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
        chainer.chainPendingPostings();                   // pasa con la transaccion legitima abierta: no la ve
        release.countDown();
        slow.get(30, TimeUnit.SECONDS);
        ex.shutdown();

        JournalIntegrityReport r = checkpoints.audit();
        System.out.println("[VERDICT][slow-legit] tamper=" + r.tamperDetected() + " verdict=" + r.verdict());
        assertThat(r.staleUnchainedPostings()).isEqualTo(1);
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.tamperDetected()).isFalse();
        assertThat(r.verdict()).doesNotContain("por fuera").doesNotContain("MANIPULACION DETECTADA");
        assertThat(r.coverageDegraded()).isTrue();
        assertThat(r.coverageReason()).isEqualTo(CoverageReason.ATRASADO);
    }

    /** Control positivo CON checkpoint firmado: la insercion por fuera con fecha vieja se sigue detectando. */
    @Test
    void insercion_por_fuera_con_fecha_vieja_despues_de_la_pasada_se_detecta_con_checkpoint_firmado() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key, created_at)"
                + " VALUES (1, 3, 777000, 'ARS', 'FORGED-OLD-CP', now() - interval '5 hours')");
        jdbc.update("UPDATE account SET posted_debits = posted_debits + 777000 WHERE id = 1");
        jdbc.update("UPDATE account SET posted_credits = posted_credits + 777000 WHERE id = 3");

        JournalIntegrityReport r = checkpoints.audit();
        System.out.println("[VERDICT][outside-cp] tamper=" + r.tamperDetected() + " verdict=" + r.verdict());
        assertThat(r.checkpointPresent()).isTrue();
        assertThat(r.chainIntact()).isTrue();
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.tamperDetected()).isTrue();
        assertThat(r.verdict()).contains("MANIPULACION DETECTADA").contains("insertado por fuera de la app");
        assertThat(r.coverageDegraded()).isFalse();                   // hay checkpoint y nada pendiente: no degradada
    }
}
