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
 * The coverage verdict has to state the TRUE cause of an old unlinked posting: chainer stopped,
 * chainer behind because of load (transient), or a posting that appeared after the chainer drained its queue
 * (the only sign of an insertion outside the app). The verdict used to say "either they were inserted outside the app or the
 * chainer is stopped" even under a legitimate burst with no tampering at all: both halves were false.
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

    // ------------------------------------------------------------ causes and coverage reason, with a fixed clock

    @Test
    void estado_1_detenido_sin_actividad_hace_mas_de_3_ciclos() {
        assertThat(JournalCheckpointService.classifyStale(5, NOW, NOW.minusMillis(3 * DELAY + 1), DELAY))
                .isEqualTo(StaleCause.CHAINER_STOPPED);
        Coverage c = cov(StaleCause.CHAINER_STOPPED, 5, 5, 0, 16_000, 5_000);
        assertThat(JournalCheckpointService.coverageReason(c, true)).isEqualTo(CoverageReason.DETENIDO);
        assertThat(clause(c)).contains("STOPPED").contains("16 s").doesNotContain("outside the app")
                .doesNotContain("sign of a posting inserted");
    }

    @Test
    void estado_2_atrasado_es_transitorio_con_ventana_y_no_es_tamper() {
        assertThat(JournalCheckpointService.classifyStale(1170, NOW, NOW.minusMillis(DELAY), DELAY))
                .isEqualTo(StaleCause.CHAINER_BEHIND);
        Coverage c = cov(StaleCause.CHAINER_BEHIND, 3200, 1170, 0, 1_000, 80_000);
        assertThat(JournalCheckpointService.coverageReason(c, false)).isEqualTo(CoverageReason.ATRASADO);
        assertThat(clause(c)).contains("BEHIND").contains("TRANSIENT").contains("re-audit in ~80 s")
                .contains("NOT evidence of tampering").doesNotContain("outside the app").doesNotContain("STOPPED");
    }

    @Test
    void estado_3_evidencia_de_escritura_posterior_a_la_pasada_y_sin_checkpoint() {
        assertThat(JournalCheckpointService.classifyStale(0, NOW, NOW.minusSeconds(3600), DELAY))
                .isEqualTo(StaleCause.NONE);
        Coverage c = cov(StaleCause.NONE, 1, 1, 1, 5_000, 5_000);
        assertThat(clause(c)).contains("inserted outside the app").doesNotContain("STOPPED")
                .doesNotContain("BEHIND");
        assertThat(JournalCheckpointService.coverageReason(c, true)).isNull();
        assertThat(JournalCheckpointService.coverageReason(c, false)).isEqualTo(CoverageReason.SIN_CHECKPOINT);
        assertThat(JournalCheckpointService.seconds(900)).isEqualTo("0,9 s");      // formerly "0 s"
    }

    // ------------------------------------------------------------ integration on real postgres

    @Test
    void rafaga_legitima_sin_manipulacion_no_dice_insercion_por_fuera() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        int n = 450;
        for (int i = 0; i < n; i++) {
            ledger.transfer("external:funding", "wallet:a", 10, "burst-" + i);
        }
        // Simulates the passage of time (this test's effective window = 3 h) BEFORE the pass: nobody touches amounts,
        // accounts or counters. (A date UPDATE AFTER the pass is, correctly, evidence of a write
        // outside the app: that is why the simulation goes before.)
        int shifted = jdbc.update("UPDATE posting SET created_at = created_at - interval '4 hours'");
        assertThat(shifted).isEqualTo(n);
        chainer.chainPendingPostings();                   // chains 200 of 450: it filled the batch -> backlog
        assertThat(jdbc.queryForObject("SELECT hit_batch_limit FROM journal_chainer_state WHERE id = 1",
                Boolean.class)).isTrue();

        JournalIntegrityReport r = checkpoints.audit();
        System.out.println("[VERDICT][burst] tamper=" + r.tamperDetected() + " verdict=" + r.verdict());
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.chainIntact()).isTrue();
        assertThat(r.staleUnchainedPostings()).isEqualTo(n - 200);
        assertThat(r.tamperDetected()).isFalse();         // tamper = only confirmed evidence
        assertThat(r.coverageDegraded()).isTrue();
        assertThat(r.coverageReason()).isEqualTo(CoverageReason.ATRASADO);
        assertThat(r.verdict()).doesNotContain("outside the app").doesNotContain("sign of a posting inserted")
                .doesNotContain("TAMPER DETECTED").contains("COVERAGE ALERT").contains("BEHIND")
                .contains("TRANSIENT").contains("NO SIGNED CHECKPOINT");

        chainer.chainPendingPostings();
        chainer.chainPendingPostings();                   // 200 + 50: drains the queue
        JournalIntegrityReport after = checkpoints.audit();
        System.out.println("[VERDICT][burst-drained] tamper=" + after.tamperDetected() + " verdict=" + after.verdict());
        assertThat(after.tamperDetected()).isFalse();
        assertThat(after.unchainedPostings()).isZero();
        assertThat(after.coverageReason()).isEqualTo(CoverageReason.SIN_CHECKPOINT);   // no longer behind; not signed yet
    }

    @Test
    void asiento_viejo_que_aparece_despues_de_que_el_encadenador_vacio_la_cola_es_senal_de_insercion() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        chainer.chainPendingPostings();                   // drains the queue (1 < 200); still no signed checkpoint
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key, created_at)"
                + " VALUES (1, 3, 777000, 'ARS', 'FORGED-OLD', now() - interval '1 day')");
        jdbc.update("UPDATE account SET posted_debits = posted_debits + 777000 WHERE id = 1");
        jdbc.update("UPDATE account SET posted_credits = posted_credits + 777000 WHERE id = 3");

        JournalIntegrityReport r = checkpoints.audit();
        System.out.println("[VERDICT][passed] tamper=" + r.tamperDetected() + " verdict=" + r.verdict());
        assertThat(r.tamperDetected()).isTrue();
        assertThat(r.checkpointPresent()).isFalse();
        assertThat(r.verdict()).contains("TAMPER DETECTED").contains("inserted")
                .contains("outside the app").contains("NO SIGNED CHECKPOINT YET").contains("not applicable")
                .doesNotContain("STOPPED").doesNotContain("BEHIND");
    }

    /**
     * Cause 4 (the one that produced 13 of 80 "outside the app" flags in a clean burst): a LEGITIMATE transaction that
     * stays open while the chainer passes (contention, retries, deadlock waits) and commits afterwards with a
     * created_at older than the window. It is not evidence of an insertion outside the app: its xid already existed when the
     * chainer took its snapshot. Deterministic: the same transaction (same xid) ages its created_at by 5 h.
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
        chainer.chainPendingPostings();                   // passes with the legitimate transaction open: it does not see it
        release.countDown();
        slow.get(30, TimeUnit.SECONDS);
        ex.shutdown();

        JournalIntegrityReport r = checkpoints.audit();
        System.out.println("[VERDICT][slow-legit] tamper=" + r.tamperDetected() + " verdict=" + r.verdict());
        assertThat(r.staleUnchainedPostings()).isEqualTo(1);
        assertThat(r.balancesConsistent()).isTrue();
        assertThat(r.tamperDetected()).isFalse();
        assertThat(r.verdict()).doesNotContain("outside the app").doesNotContain("TAMPER DETECTED");
        assertThat(r.coverageDegraded()).isTrue();
        assertThat(r.coverageReason()).isEqualTo(CoverageReason.ATRASADO);
    }

    /** Positive control WITH a signed checkpoint: the insertion outside the app with an old date is still detected. */
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
        assertThat(r.verdict()).contains("TAMPER DETECTED").contains("inserted outside the app");
        assertThat(r.coverageDegraded()).isFalse();                   // there is a checkpoint and nothing pending: not degraded
    }
}
