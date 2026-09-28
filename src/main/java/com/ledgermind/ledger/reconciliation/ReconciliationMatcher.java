package com.ledgermind.ledger.reconciliation;

import com.ledgermind.ledger.AmountOverflowException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * DETERMINISTIC reconciliation matcher: it matches a PSP's feed against the ledger by the external
 * reference (feed.externalRef matches the posting's ref, which is its idempotencyKey). It uses no AI: the AI
 * only NARRATES the result. Pure logic (no state, no dependencies) -> testable without a database.
 *
 * <p>It AGGREGATES by reference on BOTH sides before comparing: a PSP can settle the same order in
 * several parts (split / adjustment / reversal), so the amounts are summed per ref and the sums are compared.
 * This keeps a duplicate in the feed from being silently "matched". Scope: matching by **exact reference
 * and exact amount** (no configurable tolerance, no time window); every amount difference is
 * reported as {@link Discrepancy.Type#AMOUNT_MISMATCH}. {@code balanced} is true only if there are NO
 * discrepancies AND the net balances ({@code difference == 0}) — two opposite errors cannot report "OK".
 */
public class ReconciliationMatcher {

    public ReconciliationReport reconcile(List<SettlementRecord> feed, List<LedgerEntry> ledger) {
        // Null-safe by design: a null ref collapses to "" (one discrepancy bucket) instead of blowing up the
        // groupingBy with an NPE. The edge (controller) already rejects empty refs; this hardens the matcher as a
        // pure function against ANY caller (incl. the demo) without coupling it to that validation.
        // Sums are exact (BigInteger) and only the FINAL value must fit in 64 bits: the result does not depend on the
        // order of the rows ([MAX, 10, -20] is MAX-10, not an error), and a final value outside the range is rejected.
        Map<String, Long> feedByRef = toLongs(feed.stream().collect(Collectors.groupingBy(
                r -> Objects.requireNonNullElse(r.externalRef(), ""),
                Collectors.reducing(BigInteger.ZERO, r -> BigInteger.valueOf(r.amount()), BigInteger::add))));
        Map<String, Long> ledgerByRef = toLongs(ledger.stream().collect(Collectors.groupingBy(
                le -> Objects.requireNonNullElse(le.ref(), ""),
                Collectors.reducing(BigInteger.ZERO, le -> BigInteger.valueOf(le.amount()), BigInteger::add))));

        List<Discrepancy> discrepancies = new ArrayList<>();
        int matched = 0;

        // What the PSP settled (per ref) vs what was posted.
        for (Map.Entry<String, Long> e : feedByRef.entrySet()) {
            String ref = e.getKey();
            long feedAmount = e.getValue();
            Long ledgerAmount = ledgerByRef.get(ref);
            if (ledgerAmount == null) {
                discrepancies.add(new Discrepancy(Discrepancy.Type.MISSING_IN_LEDGER, ref, feedAmount, 0,
                        "the PSP settled " + feedAmount + " for '" + ref + "' and there is no posting"));
            } else if (ledgerAmount != feedAmount) {
                long diff = subtractExact(feedAmount, ledgerAmount);
                String hint = diff < 0
                        ? " (the PSP settled less: possible unposted fee/withholding)"
                        : " (the PSP settled more than was posted)";
                discrepancies.add(new Discrepancy(Discrepancy.Type.AMOUNT_MISMATCH, ref, feedAmount, ledgerAmount,
                        "difference of " + diff + " on '" + ref + "'" + hint));
            } else {
                matched++;
            }
        }

        // Postings the PSP does not report.
        for (Map.Entry<String, Long> e : ledgerByRef.entrySet()) {
            if (!feedByRef.containsKey(e.getKey())) {
                discrepancies.add(new Discrepancy(Discrepancy.Type.MISSING_IN_FEED, e.getKey(),
                        0, e.getValue(),
                        "the ledger has " + e.getValue() + " for '" + e.getKey() + "' that the PSP does not report"));
            }
        }

        // Exact totals: a caller-supplied feed can sum past Long.MAX; that is rejected (422), never wrapped.
        long feedTotal = fitOrReject(feed.stream().map(r -> BigInteger.valueOf(r.amount()))
                .reduce(BigInteger.ZERO, BigInteger::add));
        long ledgerTotal = fitOrReject(ledger.stream().map(le -> BigInteger.valueOf(le.amount()))
                .reduce(BigInteger.ZERO, BigInteger::add));
        long difference = subtractExact(feedTotal, ledgerTotal);
        boolean balanced = discrepancies.isEmpty() && difference == 0;
        String summary = balanced
                ? "Reconciled: " + matched + " references balance; feed and ledger agree on " + feedTotal + " cents."
                : "Mismatch: " + discrepancies.size() + " discrepancy(ies). Feed=" + feedTotal
                        + " Ledger=" + ledgerTotal + " (difference " + difference + ").";

        return new ReconciliationReport(feed.size(), ledger.size(), matched,
                feedTotal, ledgerTotal, difference, discrepancies, balanced, summary);
    }

    private static Map<String, Long> toLongs(Map<String, BigInteger> exact) {
        Map<String, Long> out = new HashMap<>();
        exact.forEach((ref, sum) -> out.put(ref, fitOrReject(sum)));
        return out;
    }

    private static long fitOrReject(BigInteger exactSum) {
        try {
            return exactSum.longValueExact();
        } catch (ArithmeticException e) {
            throw new AmountOverflowException("A reconciliation total is outside the 64-bit range; it is rejected, not wrapped.");
        }
    }

    private static long subtractExact(long a, long b) {
        try {
            return Math.subtractExact(a, b);
        } catch (ArithmeticException e) {
            throw new AmountOverflowException("A reconciliation difference is outside the 64-bit range; it is rejected, not wrapped.");
        }
    }
}
