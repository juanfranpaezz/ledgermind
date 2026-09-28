package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OVF-3: the overdraft sweep must survive a journal whose per-account sums leave the 64-bit range. Before the fix it
 * read NUMERIC {@code sum()} with {@code rs.getLong} and threw on every pass, so the overdraft freeze silently
 * stopped. Invariant: the pass completes; a non-negative account whose debit side overflows is flagged (frozen); the
 * account on the credit side is not.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000",
        "ledgermind.overdraft.sweep-initial-delay-ms=3600000",
        "ledgermind.overdraft.watermark-lag-ms=0"
})
@Testcontainers
class OverdraftSweepOverflowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private OverdraftSweeper sweeper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    private void freshLedger() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE overdraft_sweep_state SET watermark_posting_id = 0 WHERE id = 1");
        ledger.createAccount("external:funding", "ARS", true);   // id 1
        ledger.createAccount("wallet:a", "ARS", false);          // id 2
        ledger.createAccount("wallet:b", "ARS", false);          // id 3
    }

    /** Out-of-band posting a to b with the counters untouched: the shape of a DB-level edit. */
    private void insertAtoB(long amount, String key) {
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key)"
                + " VALUES (2, 3, ?, 'ARS', ?)", amount, key);
    }

    private int activeFlags(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM overdraft_flag WHERE account_id = ? AND cleared_at IS NULL",
                Integer.class, accountId);
    }

    @Test
    void debitSideSumAboveLongMaxIsFlaggedAndFrozenAndThePassCompletes() {
        freshLedger();
        insertAtoB(5_000_000_000_000_000_000L, "ovf-1");
        insertAtoB(5_000_000_000_000_000_000L, "ovf-2");

        assertThatCode(sweeper::sweep).as("sweep over a 1e19 debit sum").doesNotThrowAnyException();

        assertThat(activeFlags(2)).as("wallet:a (debit side, 1e19) flagged").isEqualTo(1);
        assertThat(activeFlags(3)).as("wallet:b (credit side) not flagged").isZero();
        Long derivedAvailable = jdbc.queryForObject(
                "SELECT derived_available FROM overdraft_flag WHERE account_id = 2", Long.class);
        assertThat(derivedAvailable).as("saturated evidence is negative").isNegative();
        assertThatThrownBy(() -> ledger.transfer("external:funding", "wallet:a", 1, "after-a"))
                .isInstanceOf(AccountFrozenException.class);
        assertThatCode(() -> ledger.transfer("external:funding", "wallet:b", 1, "after-b"))
                .doesNotThrowAnyException();
    }

    @Test
    void seededGeneratorNeverThrowsAndFlagsOnlyTheDebitSide() {
        long seed = System.nanoTime();
        System.out.println("[OVF-3] seed=" + seed);
        Random rnd = new Random(seed);
        List<long[]> pairs = new ArrayList<>(List.of(
                new long[] {5_000_000_000_000_000_000L, 5_000_000_000_000_000_000L},
                new long[] {Long.MAX_VALUE, 1},
                new long[] {1, 1}));
        for (int i = 0; i < 17; i++) {
            pairs.add(new long[] {rnd.nextLong(1, Long.MAX_VALUE), rnd.nextLong(1, Long.MAX_VALUE)});
        }
        int overflowing = 0;
        for (long[] p : pairs) {
            freshLedger();
            insertAtoB(p[0], "g-1");
            insertAtoB(p[1], "g-2");
            boolean overflows = BigInteger.valueOf(p[0]).add(BigInteger.valueOf(p[1]))
                    .compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0;
            overflowing += overflows ? 1 : 0;
            assertThatCode(sweeper::sweep).as("seed=%d pair=(%d,%d)", seed, p[0], p[1]).doesNotThrowAnyException();
            assertThat(activeFlags(2)).as("seed=%d pair=(%d,%d) debit side", seed, p[0], p[1]).isEqualTo(1);
            assertThat(activeFlags(3)).as("seed=%d pair=(%d,%d) credit side", seed, p[0], p[1]).isZero();
        }
        System.out.println("[OVF-3] pairs=" + pairs.size() + " overflowing=" + overflowing);
        assertThat(overflowing).as("the generator exercised the overflow branch").isPositive();
    }

    /**
     * Regression: with only {@code sweep-delay-ms} set high, the scheduled sweep still ran once at context
     * start and raced this class's manual {@code sweep()} on the {@code FOR UPDATE} row (a serialization error, seen in
     * a clean-clone verify). The registered task must carry the configured initial delay, so no startup pass exists
     * inside this test's window.
     */
    @Test
    void theScheduledStartupSweepIsDeferredByTheInitialDelayProperty() {
        List<FixedDelayTask> sweeps = scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(t -> t.toString().contains("OverdraftSweeper.sweep"))
                .filter(FixedDelayTask.class::isInstance).map(FixedDelayTask.class::cast)
                .toList();
        assertThat(sweeps).as("exactly one scheduled OverdraftSweeper.sweep task").hasSize(1);
        assertThat(sweeps.get(0).getInitialDelayDuration()).as("initial delay of the scheduled sweep")
                .isEqualTo(Duration.ofMillis(3_600_000));
    }
}
