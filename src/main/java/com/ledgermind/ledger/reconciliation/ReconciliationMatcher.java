package com.ledgermind.ledger.reconciliation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Matcher DETERMINISTA de reconciliacion: cruza el feed de un PSP contra el ledger por la referencia
 * externa (el feed.externalRef matchea la ref del asiento, que es su idempotencyKey). No usa IA: la IA
 * solo NARRA el resultado. Logica pura (sin estado ni dependencias) -> testeable sin base de datos.
 *
 * <p>AGREGA por referencia en AMBOS lados antes de comparar: un PSP puede liquidar una misma orden en
 * varios tramos (split / ajuste / reversa), asi que se suman los importes por ref y se compara la suma.
 * Esto evita que un duplicado en el feed se "matchee" en silencio. Alcance: cruce por **referencia exacta
 * e importe exacto** (sin tolerancia configurable ni ventana temporal); toda diferencia de importe se
 * reporta como {@link Discrepancy.Type#AMOUNT_MISMATCH}. {@code balanced} es true solo si NO hay
 * discrepancias Y el neto cuadra ({@code difference == 0}) — dos errores opuestos no pueden cantar "OK".
 */
public class ReconciliationMatcher {

    public ReconciliationReport reconcile(List<SettlementRecord> feed, List<LedgerEntry> ledger) {
        Map<String, Long> feedByRef = feed.stream().collect(Collectors.groupingBy(
                SettlementRecord::externalRef, Collectors.summingLong(SettlementRecord::amount)));
        Map<String, Long> ledgerByRef = ledger.stream().collect(Collectors.groupingBy(
                LedgerEntry::ref, Collectors.summingLong(LedgerEntry::amount)));

        List<Discrepancy> discrepancies = new ArrayList<>();
        int matched = 0;

        // Lo que el PSP liquidó (por ref) vs lo asentado.
        for (Map.Entry<String, Long> e : feedByRef.entrySet()) {
            String ref = e.getKey();
            long feedAmount = e.getValue();
            Long ledgerAmount = ledgerByRef.get(ref);
            if (ledgerAmount == null) {
                discrepancies.add(new Discrepancy(Discrepancy.Type.MISSING_IN_LEDGER, ref, feedAmount, 0,
                        "el PSP liquidó " + feedAmount + " para '" + ref + "' y no hay asiento"));
            } else if (ledgerAmount != feedAmount) {
                long diff = feedAmount - ledgerAmount;
                String hint = diff < 0
                        ? " (el PSP liquidó menos: posible comisión/retención no asentada)"
                        : " (el PSP liquidó de más que lo asentado)";
                discrepancies.add(new Discrepancy(Discrepancy.Type.AMOUNT_MISMATCH, ref, feedAmount, ledgerAmount,
                        "diferencia de " + diff + " en '" + ref + "'" + hint));
            } else {
                matched++;
            }
        }

        // Asientos que el PSP no reporta.
        for (Map.Entry<String, Long> e : ledgerByRef.entrySet()) {
            if (!feedByRef.containsKey(e.getKey())) {
                discrepancies.add(new Discrepancy(Discrepancy.Type.MISSING_IN_FEED, e.getKey(),
                        0, e.getValue(),
                        "el ledger tiene " + e.getValue() + " para '" + e.getKey() + "' que el PSP no reporta"));
            }
        }

        long feedTotal = feed.stream().mapToLong(SettlementRecord::amount).sum();
        long ledgerTotal = ledger.stream().mapToLong(LedgerEntry::amount).sum();
        long difference = feedTotal - ledgerTotal;
        boolean balanced = discrepancies.isEmpty() && difference == 0;
        String summary = balanced
                ? "Conciliado: " + matched + " referencias cuadran; feed y ledger coinciden en " + feedTotal + " centavos."
                : "Descuadre: " + discrepancies.size() + " discrepancia(s). Feed=" + feedTotal
                        + " Ledger=" + ledgerTotal + " (diferencia " + difference + ").";

        return new ReconciliationReport(feed.size(), ledger.size(), matched,
                feedTotal, ledgerTotal, difference, discrepancies, balanced, summary);
    }
}
