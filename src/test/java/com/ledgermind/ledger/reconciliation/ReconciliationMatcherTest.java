package com.ledgermind.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation matcher: it balances when feed and ledger match, and classifies the three discrepancies
 * (different amount, missing in the ledger, missing in the feed). Pure logic -> no Spring or Postgres.
 */
class ReconciliationMatcherTest {

    private final ReconciliationMatcher matcher = new ReconciliationMatcher();
    private static final Instant T = Instant.parse("2026-06-12T12:00:00Z");

    @Test
    void balances_when_feed_and_ledger_match() {
        List<SettlementRecord> feed = List.of(
                new SettlementRecord("ref-1", 1000, T),
                new SettlementRecord("ref-2", 2000, T));
        List<LedgerEntry> ledger = List.of(
                new LedgerEntry("ref-1", 1000),
                new LedgerEntry("ref-2", 2000));

        ReconciliationReport report = matcher.reconcile(feed, ledger);

        assertThat(report.balanced()).isTrue();
        assertThat(report.matched()).isEqualTo(2);
        assertThat(report.discrepancies()).isEmpty();
        assertThat(report.difference()).isZero();
        assertThat(report.summary()).contains("Reconciled");
    }

    @Test
    void detects_the_three_kinds_of_discrepancy() {
        List<SettlementRecord> feed = List.of(
                new SettlementRecord("ref-1", 1000, T),   // balances
                new SettlementRecord("ref-2", 2000, T),   // mismatch: the ledger has 1961 (39 of fee)
                new SettlementRecord("ref-3", 500, T));    // missing in the ledger
        List<LedgerEntry> ledger = List.of(
                new LedgerEntry("ref-1", 1000),
                new LedgerEntry("ref-2", 1961),
                new LedgerEntry("ref-4", 700));            // missing in the feed

        ReconciliationReport report = matcher.reconcile(feed, ledger);

        assertThat(report.balanced()).isFalse();
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.discrepancies()).extracting(Discrepancy::type).containsExactlyInAnyOrder(
                Discrepancy.Type.AMOUNT_MISMATCH,
                Discrepancy.Type.MISSING_IN_LEDGER,
                Discrepancy.Type.MISSING_IN_FEED);

        Discrepancy mismatch = report.discrepancies().stream()
                .filter(d -> d.type() == Discrepancy.Type.AMOUNT_MISMATCH).findFirst().orElseThrow();
        assertThat(mismatch.ref()).isEqualTo("ref-2");
        assertThat(mismatch.feedAmount()).isEqualTo(2000);
        assertThat(mismatch.ledgerAmount()).isEqualTo(1961);
        assertThat(report.summary()).contains("Mismatch");
    }

    @Test
    void duplicate_refs_in_the_feed_are_aggregated_and_do_not_falsely_balance() {
        // The PSP settles the SAME order in two parts (split) that add up to MORE than the posting.
        List<SettlementRecord> feed = List.of(
                new SettlementRecord("ref-1", 600, T),
                new SettlementRecord("ref-1", 600, T));
        List<LedgerEntry> ledger = List.of(new LedgerEntry("ref-1", 1000));

        ReconciliationReport report = matcher.reconcile(feed, ledger);

        assertThat(report.feedTotal()).isEqualTo(1200);          // 600 + 600 agregados
        assertThat(report.difference()).isEqualTo(200);          // 1200 vs 1000
        assertThat(report.balanced()).isFalse();                 // it cannot report "reconciled"
        assertThat(report.discrepancies()).extracting(Discrepancy::type)
                .containsExactly(Discrepancy.Type.AMOUNT_MISMATCH);
    }
}
