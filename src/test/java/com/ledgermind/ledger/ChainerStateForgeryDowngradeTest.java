package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.JournalCheckpointService.CoverageReason;
import com.ledgermind.ledger.JournalCheckpointService.JournalIntegrityReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins a DOCUMENTED LIMIT (README, "What the audit covers"): the out-of-band-insert evidence reads the committed
 * chainer state ({@code journal_chainer_state}: pass start and pass xid), and that row lives in the same database the
 * attacker writes. The same DB writer that inserts an old-dated posting (flagged: tamperDetected=true) can delete that
 * row, or raise its pass_xid, and the next audit DOWNGRADES the same evidence to coverageDegraded (ATRASADO/DETENIDO)
 * with tamperDetected=false. Each test first shows the flag firing, then the forged state silencing it. If a future
 * change anchors the chainer state outside the DB, these tests go red on purpose: update the README with them.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000"
})
@Testcontainers
class ChainerStateForgeryDowngradeTest {

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

    /** Legit traffic, one chainer pass, a signed checkpoint, then an out-of-band old-dated insert with counters. */
    private void flaggedOutOfBandInsert(String key) {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key, created_at)"
                + " VALUES (1, 3, 777000, 'ARS', ?, now() - interval '5 hours')", key);
        jdbc.update("UPDATE account SET posted_debits = posted_debits + 777000 WHERE id = 1");
        jdbc.update("UPDATE account SET posted_credits = posted_credits + 777000 WHERE id = 3");
        JournalIntegrityReport before = checkpoints.audit();
        System.out.println("[STATE-INTACT] tamper=" + before.tamperDetected() + " degraded=" + before.coverageDegraded());
        assertThat(before.tamperDetected()).isTrue();                 // the flag fires on the untouched state
        assertThat(before.verdict()).contains("insertado por fuera de la app");
        assertThat(before.coverageDegraded()).isFalse();
    }

    private static void assertDowngraded(JournalIntegrityReport after) {
        System.out.println("[FORGED-STATE] tamper=" + after.tamperDetected() + " degraded=" + after.coverageDegraded()
                + " reason=" + after.coverageReason() + " stale=" + after.staleUnchainedPostings());
        assertThat(after.tamperDetected()).isFalse();
        assertThat(after.coverageDegraded()).isTrue();
        assertThat(after.coverageReason()).isIn(CoverageReason.ATRASADO, CoverageReason.DETENIDO);
        assertThat(after.staleUnchainedPostings()).isEqualTo(1);    // the forged posting is still there, still counted
        assertThat(after.chainIntact()).isTrue();
        assertThat(after.balancesConsistent()).isTrue();
        assertThat(after.verdict()).doesNotContain("MANIPULACION DETECTADA");
    }

    @Test
    void deleting_the_chainer_state_row_downgrades_an_out_of_band_insert_to_degraded_coverage() {
        flaggedOutOfBandInsert("FORGED-STATE-DELETE");
        jdbc.update("DELETE FROM journal_chainer_state");
        assertDowngraded(checkpoints.audit());
    }

    @Test
    void raising_the_pass_xid_downgrades_an_out_of_band_insert_to_degraded_coverage() {
        flaggedOutOfBandInsert("FORGED-STATE-XID");
        // This UPDATE's own xid is newer than the forged INSERT's xid: the posting now looks written BEFORE the pass.
        jdbc.update("UPDATE journal_chainer_state SET pass_xid = pg_current_xact_id()::text::bigint");
        assertDowngraded(checkpoints.audit());
    }
}
