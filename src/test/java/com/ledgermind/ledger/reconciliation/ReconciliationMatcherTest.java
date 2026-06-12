package com.ledgermind.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Matcher de reconciliacion: cuadra cuando feed y ledger coinciden, y clasifica los tres descuadres
 * (importe distinto, falta en el ledger, falta en el feed). Logica pura -> sin Spring ni Postgres.
 */
class ReconciliationMatcherTest {

    private final ReconciliationMatcher matcher = new ReconciliationMatcher();
    private static final Instant T = Instant.parse("2026-06-12T12:00:00Z");

    @Test
    void cuadra_cuando_feed_y_ledger_coinciden() {
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
        assertThat(report.summary()).contains("Conciliado");
    }

    @Test
    void detecta_los_tres_tipos_de_descuadre() {
        List<SettlementRecord> feed = List.of(
                new SettlementRecord("ref-1", 1000, T),   // cuadra
                new SettlementRecord("ref-2", 2000, T),   // mismatch: el ledger tiene 1961 (39 de comision)
                new SettlementRecord("ref-3", 500, T));    // falta en el ledger
        List<LedgerEntry> ledger = List.of(
                new LedgerEntry("ref-1", 1000),
                new LedgerEntry("ref-2", 1961),
                new LedgerEntry("ref-4", 700));            // falta en el feed

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
        assertThat(report.summary()).contains("Descuadre");
    }
}
