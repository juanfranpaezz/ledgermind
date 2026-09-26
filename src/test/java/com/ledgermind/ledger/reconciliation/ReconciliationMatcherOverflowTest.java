package com.ledgermind.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** OVF-5: reconciliation totals never wrap. A total outside the 64-bit range is rejected, never reported wrapped. */
class ReconciliationMatcherOverflowTest {

    private final ReconciliationMatcher matcher = new ReconciliationMatcher();

    private static SettlementRecord rec(String ref, long amount) {
        return new SettlementRecord(ref, amount, Instant.EPOCH);
    }

    private static void assertRejectedAsOverflow(Throwable t) {
        assertThat(t).as("a total above Long.MAX must be rejected, not wrapped").isNotNull();
        assertThat(t.getClass().getSimpleName()).isEqualTo("AmountOverflowException");
    }

    @Test
    void feedTotalAboveLongMaxIsRejected() {
        Throwable t = catchThrowable(() -> matcher.reconcile(
                List.of(rec("r1", Long.MAX_VALUE), rec("r2", 10)),
                List.of(new LedgerEntry("r1", Long.MAX_VALUE), new LedgerEntry("r2", 10))));
        assertRejectedAsOverflow(t);
    }

    @Test
    void sameRefSumAboveLongMaxIsRejected() {
        Throwable t = catchThrowable(() -> matcher.reconcile(
                List.of(rec("r1", Long.MAX_VALUE), rec("r1", 10)),
                List.of(new LedgerEntry("r1", 5))));
        assertRejectedAsOverflow(t);
    }

    @Test
    void totalsThatFitAreExactAndBalanced() {
        ReconciliationReport r = matcher.reconcile(
                List.of(rec("r1", Long.MAX_VALUE - 10), rec("r2", 10)),
                List.of(new LedgerEntry("r1", Long.MAX_VALUE - 10), new LedgerEntry("r2", 10)));
        assertThat(r.balanced()).isTrue();
        assertThat(r.feedTotal()).isEqualTo(Long.MAX_VALUE);
        assertThat(r.ledgerTotal()).isEqualTo(Long.MAX_VALUE);
    }
}
