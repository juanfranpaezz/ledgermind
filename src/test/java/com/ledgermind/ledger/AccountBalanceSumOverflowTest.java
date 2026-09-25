package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.AccountBalanceVerifier.AccountBalanceMismatch;
import com.ledgermind.ledger.AccountBalanceVerifier.BalanceVerifyResult;
import com.ledgermind.ledger.JournalCheckpointService.JournalIntegrityReport;
import java.math.BigInteger;
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
 * A journal sum above {@code Long.MAX_VALUE} must come out as a balance mismatch (tamper), not as an exception.
 * The stored counters are BIGINT, so no legitimate write can reach such a sum; two out-of-band postings of 5e18 on
 * the same account do. Gate finding 2026-09-25: reading the SQL {@code sum()} (numeric) with {@code getLong} threw
 * "Bad value for type long", surfaced as a DataIntegrityViolationException and an HTTP 409 on the audit.
 *
 * <p>Both outcomes: 2 x 5e18 with the counters untouched fires (mismatch, tamper); 2 x 4e18 with the counters bumped
 * to match (sum 8e18, still inside the long range) does not fire (consistent).
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000"
})
@Testcontainers
class AccountBalanceSumOverflowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private AccountBalanceVerifier balances;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);   // id 1
        ledger.createAccount("wallet:a", "ARS", false);          // id 2
        ledger.createAccount("wallet:b", "ARS", false);          // id 3
    }

    private void insertPosting(long credit, long amount, String key) {
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key)"
                + " VALUES (1, ?, ?, 'ARS', ?)", credit, amount, key);
    }

    @Test
    void journalSumAboveLongMaxIsAMismatchNotAnException() {
        insertPosting(2, 5_000_000_000_000_000_000L, "oob-1");
        insertPosting(3, 5_000_000_000_000_000_000L, "oob-2");

        BalanceVerifyResult result = balances.verify();
        System.out.println("[SUM-OVERFLOW] consistent=" + result.consistent() + " mismatches=" + result.mismatches());
        assertThat(result.consistent()).isFalse();
        assertThat(result.postingsReplayed()).isEqualTo(2);
        AccountBalanceMismatch funding = result.mismatches().stream()
                .filter(m -> m.address().equals("external:funding")).findFirst().orElseThrow();
        assertThat(funding.journalPostedDebits()).isEqualTo(new BigInteger("10000000000000000000"));
        assertThat(funding.postedDebitsDifference()).isEqualTo(new BigInteger("-10000000000000000000"));
        assertThat(funding.describe()).contains("10000000000000000000");

        JournalIntegrityReport report = checkpoints.audit();
        System.out.println("[SUM-OVERFLOW] audit tamper=" + report.tamperDetected() + " verdict=" + report.verdict());
        assertThat(report.balancesConsistent()).isFalse();
        assertThat(report.tamperDetected()).isTrue();
    }

    @Test
    void largeButInRangeSumWithMatchingCountersStaysConsistent() {
        insertPosting(2, 4_000_000_000_000_000_000L, "big-1");
        insertPosting(3, 4_000_000_000_000_000_000L, "big-2");
        jdbc.update("UPDATE account SET posted_debits = posted_debits + 8000000000000000000 WHERE id = 1");
        jdbc.update("UPDATE account SET posted_credits = posted_credits + 4000000000000000000 WHERE id IN (2, 3)");

        BalanceVerifyResult result = balances.verify();
        System.out.println("[SUM-IN-RANGE] consistent=" + result.consistent() + " mismatches=" + result.mismatches());
        assertThat(result.consistent()).isTrue();
        assertThat(result.mismatches()).isEmpty();
        assertThat(checkpoints.audit().balancesConsistent()).isTrue();
    }
}
