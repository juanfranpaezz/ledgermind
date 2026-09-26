package com.ledgermind.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Gate fix (amend round 1): pins the two reconciliation overflow guards the OVF-5 suite could not tell from a wrap
 * (verifier mutations M19 per-reference sum, M20 total difference), and pins that only a FINAL value outside 64 bits is
 * rejected: the result never depends on the order of the rows. Negative feed amounts reach the matcher over HTTP
 * (the controller validates only null or blank references).
 */
class ReconciliationMatcherOverflowAdversarialTest {

    private final ReconciliationMatcher matcher = new ReconciliationMatcher();

    private static SettlementRecord rec(String ref, long amount) {
        return new SettlementRecord(ref, amount, Instant.EPOCH);
    }

    private static void assertRejectedAsOverflow(Throwable t) {
        assertThat(t).as("a value outside the 64-bit range must be rejected, not wrapped").isNotNull();
        assertThat(t.getClass().getSimpleName()).isEqualTo("AmountOverflowException");
    }

    /** Only the per-reference guard can fire: r1 sums to MAX+10, while the feed total is MAX-10 and fits. */
    @Test
    void perReferenceSumOutsideTheRangeIsRejectedEvenWhenTheFeedTotalFits() {
        Throwable t = catchThrowable(() -> matcher.reconcile(
                List.of(rec("r2", -20), rec("r1", Long.MAX_VALUE), rec("r1", 10)),
                List.of(new LedgerEntry("r1", 5), new LedgerEntry("r2", 5))));
        assertRejectedAsOverflow(t);
    }

    /** Only the total-difference guard can fire: no reference is on both sides, -MAX - 5 is below Long.MIN. */
    @Test
    void totalDifferenceOutsideTheRangeIsRejected() {
        Throwable t = catchThrowable(() -> matcher.reconcile(
                List.of(rec("r1", -Long.MAX_VALUE)),
                List.of(new LedgerEntry("r2", 5))));
        assertRejectedAsOverflow(t);
    }

    /** [MAX, 10, -20] on one reference is MAX-10: an intermediate step above MAX is not an error. */
    @Test
    void anIntermediateStepAboveTheRangeInsideOneReferenceIsNotAnError() {
        ReconciliationReport r = matcher.reconcile(
                List.of(rec("r1", Long.MAX_VALUE), rec("r1", 10), rec("r1", -20)),
                List.of(new LedgerEntry("r1", Long.MAX_VALUE - 10)));
        assertThat(r.balanced()).isTrue();
        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.feedTotal()).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(r.difference()).isZero();
    }

    /** The same rows spread over three references: the running total passes MAX, the final total does not. */
    @Test
    void anIntermediateStepAboveTheRangeInTheTotalIsNotAnError() {
        ReconciliationReport r = matcher.reconcile(
                List.of(rec("a", Long.MAX_VALUE), rec("b", 10), rec("c", -20)),
                List.of(new LedgerEntry("a", Long.MAX_VALUE), new LedgerEntry("b", 10), new LedgerEntry("c", -20)));
        assertThat(r.balanced()).isTrue();
        assertThat(r.feedTotal()).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(r.ledgerTotal()).isEqualTo(Long.MAX_VALUE - 10);
    }
}
