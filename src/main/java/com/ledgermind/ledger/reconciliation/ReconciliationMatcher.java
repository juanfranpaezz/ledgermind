package com.ledgermind.ledger.reconciliation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Matcher DETERMINISTA de reconciliacion: cruza el feed de un PSP contra el ledger por la referencia
 * externa (el feed.externalRef matchea la ref del asiento, que es su idempotencyKey). No usa IA: la IA
 * solo NARRA el resultado. Logica pura (sin estado ni dependencias) -> testeable sin base de datos.
 *
 * <p>Clasifica cuatro casos: cuadra (mismo ref, mismo importe), {@link Discrepancy.Type#AMOUNT_MISMATCH}
 * (mismo ref, importe distinto — tipico de una comision/retencion no asentada),
 * {@link Discrepancy.Type#MISSING_IN_LEDGER} (el PSP lo tiene, el ledger no) y
 * {@link Discrepancy.Type#MISSING_IN_FEED} (el ledger lo tiene, el PSP no).
 */
public class ReconciliationMatcher {

    public ReconciliationReport reconcile(List<SettlementRecord> feed, List<LedgerEntry> ledger) {
        Map<String, Long> ledgerByRef = new HashMap<>();
        for (LedgerEntry e : ledger) {
            ledgerByRef.put(e.ref(), e.amount());
        }
        Set<String> feedRefs = feed.stream().map(SettlementRecord::externalRef).collect(Collectors.toSet());

        List<Discrepancy> discrepancies = new ArrayList<>();
        int matched = 0;

        // Recorrido por el feed: lo que el PSP dice vs lo que el ledger tiene.
        for (SettlementRecord r : feed) {
            Long ledgerAmount = ledgerByRef.get(r.externalRef());
            if (ledgerAmount == null) {
                discrepancies.add(new Discrepancy(Discrepancy.Type.MISSING_IN_LEDGER, r.externalRef(),
                        r.amount(), 0,
                        "el PSP liquidó " + r.amount() + " para '" + r.externalRef() + "' y no hay asiento"));
            } else if (ledgerAmount != r.amount()) {
                long diff = r.amount() - ledgerAmount;
                discrepancies.add(new Discrepancy(Discrepancy.Type.AMOUNT_MISMATCH, r.externalRef(),
                        r.amount(), ledgerAmount,
                        "diferencia de " + diff + " en '" + r.externalRef()
                                + "' (posible comisión/retención no asentada)"));
            } else {
                matched++;
            }
        }

        // Recorrido por el ledger: asientos que el PSP no reporta.
        for (LedgerEntry e : ledger) {
            if (!feedRefs.contains(e.ref())) {
                discrepancies.add(new Discrepancy(Discrepancy.Type.MISSING_IN_FEED, e.ref(),
                        0, e.amount(),
                        "el ledger tiene " + e.amount() + " para '" + e.ref() + "' que el PSP no reporta"));
            }
        }

        long feedTotal = feed.stream().mapToLong(SettlementRecord::amount).sum();
        long ledgerTotal = ledger.stream().mapToLong(LedgerEntry::amount).sum();
        long difference = feedTotal - ledgerTotal;
        boolean balanced = discrepancies.isEmpty();
        String summary = balanced
                ? "Conciliado: " + matched + " registros cuadran; feed y ledger coinciden en " + feedTotal + " centavos."
                : "Descuadre: " + discrepancies.size() + " discrepancia(s). Feed=" + feedTotal
                        + " Ledger=" + ledgerTotal + " (diferencia " + difference + ").";

        return new ReconciliationReport(feed.size(), ledger.size(), matched,
                feedTotal, ledgerTotal, difference, discrepancies, balanced, summary);
    }
}
