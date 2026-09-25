package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Pins two README statements about the counters. (1) Holds are not implemented: nothing writes pending_debits or
 * pending_credits, so both stay 0 and availableBalance() equals posted_credits - posted_debits. (2) Conservation
 * (sum of all posted_debits == sum of all posted_credits) is not a separate check because it is implied whenever the
 * balance replay is consistent; a one-sided counter edit breaks both at once.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000"
})
@Testcontainers
class PendingCountersAndConservationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private AccountBalanceVerifier balances;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        ledger.transfer("wallet:a", "wallet:b", 30_000, "a-to-b");
        ledger.transfer("wallet:b", "wallet:a", 5_000, "b-to-a");
    }

    private long sum(String column) {
        return jdbc.queryForObject("SELECT coalesce(sum(" + column + "), 0) FROM account", Long.class);
    }

    @Test
    void legitimate_transfers_never_write_the_pending_counters() {
        long nonZero = jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE pending_debits <> 0 OR pending_credits <> 0", Long.class);
        System.out.println("[PENDING] accounts with a non-zero pending counter=" + nonZero);
        assertThat(nonZero).isZero();
        for (Account a : accounts.findAll()) {
            assertThat(a.availableBalance()).isEqualTo(a.getPostedCredits() - a.getPostedDebits());
        }
    }

    @Test
    void conservation_holds_with_consistent_balances_and_breaks_with_them() {
        System.out.println("[CONSERVATION][legit] debits=" + sum("posted_debits") + " credits=" + sum("posted_credits"));
        assertThat(sum("posted_debits")).isEqualTo(sum("posted_credits"));
        assertThat(balances.verify().consistent()).isTrue();

        jdbc.update("UPDATE account SET posted_credits = posted_credits + 1 WHERE address = 'wallet:b'");

        System.out.println("[CONSERVATION][edited] debits=" + sum("posted_debits") + " credits=" + sum("posted_credits"));
        assertThat(sum("posted_debits")).isNotEqualTo(sum("posted_credits"));
        assertThat(balances.verify().consistent()).isFalse();
    }
}
