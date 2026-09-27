package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ledgermind.ledger.JournalCheckpointService.JournalIntegrityReport;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OVF-1 (plan v1): the balance replay sums the journal exactly, so no pair of out-of-band postings, however large,
 * makes the audit throw or lie. Seeded generator (the seed is printed; set {@code -Dovf1.seed=<n>} to replay a run):
 * 200 random pairs (a, b), each in [1, Long.MAX_VALUE], plus the fixed pairs (5e18, 5e18) and (Long.MAX_VALUE, 1),
 * inserted out-of-band from account 1 to account 2. For every other in-range pair the stored counters are set to the
 * exact sum, so the replay has to answer "consistent" too.
 *
 * <p>Invariants per pair: {@code audit()} never throws; {@code balancesConsistent == (stored == exact BigInteger sum)};
 * whenever a + b > Long.MAX_VALUE, {@code tamperDetected=true}. Both outcomes are asserted to occur in every run.
 * Red on the 1719705 shape (the SQL sum read with {@code getLong}): the first overflowing pair throws.
 * Not an exhaustive proof: a seeded sample.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000",
        "ledgermind.overdraft.sweep-initial-delay-ms=3600000"
})
@Testcontainers
class AccountBalanceSumOverflowPropertyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private JdbcTemplate jdbc;

    private void freshLedger() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);   // id 1
        ledger.createAccount("wallet:a", "ARS", false);          // id 2
    }

    private void insertPosting(long amount, String key) {
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key)"
                + " VALUES (1, 2, ?, 'ARS', ?)", amount, key);
    }

    /** Uniform in [1, Long.MAX_VALUE - 1] (nextLong's bound is exclusive); MAX itself is the fixed pair (MAX, 1). */
    private static long amount(Random random) {
        return random.nextLong(1, Long.MAX_VALUE);
    }

    @Test
    void anyPairOfOutOfBandPostingsIsReportedExactlyAndNeverThrows() {
        long seed = Long.getLong("ovf1.seed", System.nanoTime());
        System.out.println("[OVF-1] seed=" + seed);
        Random random = new Random(seed);
        List<long[]> pairs = new ArrayList<>();
        pairs.add(new long[] {5_000_000_000_000_000_000L, 5_000_000_000_000_000_000L});
        pairs.add(new long[] {Long.MAX_VALUE, 1L});
        for (int i = 0; i < 200; i++) {
            pairs.add(new long[] {amount(random), amount(random)});
        }

        int overflowing = 0;
        int consistent = 0;
        int inRangeMismatch = 0;
        for (int i = 0; i < pairs.size(); i++) {
            long a = pairs.get(i)[0];
            long b = pairs.get(i)[1];
            BigInteger exact = BigInteger.valueOf(a).add(BigInteger.valueOf(b));
            boolean overflows = exact.compareTo(LONG_MAX) > 0;
            boolean countersMatch = !overflows && i % 2 == 0;

            freshLedger();
            insertPosting(a, "ovf1-" + i + "-a");
            insertPosting(b, "ovf1-" + i + "-b");
            if (countersMatch) {
                jdbc.update("UPDATE account SET posted_debits = ? WHERE id = 1", exact.longValueExact());
                jdbc.update("UPDATE account SET posted_credits = ? WHERE id = 2", exact.longValueExact());
            }

            JournalIntegrityReport[] report = new JournalIntegrityReport[1];
            String where = "pair " + i + " (" + a + ", " + b + "), seed " + seed;
            assertThatCode(() -> report[0] = checkpoints.audit()).as("audit throws on %s", where)
                    .doesNotThrowAnyException();
            assertThat(report[0].balancesConsistent()).as("balancesConsistent on %s", where).isEqualTo(countersMatch);
            if (overflows) {
                assertThat(report[0].tamperDetected()).as("tamperDetected on %s", where).isTrue();
                overflowing++;
            } else if (countersMatch) {
                consistent++;
            } else {
                inRangeMismatch++;
            }
        }
        System.out.println("[OVF-1] pairs=" + pairs.size() + " overflowing=" + overflowing + " consistent="
                + consistent + " inRangeMismatch=" + inRangeMismatch);
        assertThat(overflowing).as("overflowing pairs in the sample").isGreaterThanOrEqualTo(2);
        assertThat(consistent).as("consistent pairs in the sample").isPositive();
        assertThat(inRangeMismatch).as("in-range mismatching pairs in the sample").isPositive();
    }
}
