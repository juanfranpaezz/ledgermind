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
        // Null-safe por diseño: una ref nula colapsa a "" (un bucket de descuadre) en vez de reventar el
        // groupingBy con un NPE. El borde (controller) ya rechaza refs vacias; esto blinda al matcher como
        // funcion pura ante CUALQUIER caller (incl. la demo) sin acoplarlo a esa validacion.
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

        // Lo que el PSP liquidó (por ref) vs lo asentado.
        for (Map.Entry<String, Long> e : feedByRef.entrySet()) {
            String ref = e.getKey();
            long feedAmount = e.getValue();
            Long ledgerAmount = ledgerByRef.get(ref);
            if (ledgerAmount == null) {
                discrepancies.add(new Discrepancy(Discrepancy.Type.MISSING_IN_LEDGER, ref, feedAmount, 0,
                        "el PSP liquidó " + feedAmount + " para '" + ref + "' y no hay asiento"));
            } else if (ledgerAmount != feedAmount) {
                long diff = subtractExact(feedAmount, ledgerAmount);
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

        // Exact totals: a caller-supplied feed can sum past Long.MAX; that is rejected (422), never wrapped.
        long feedTotal = fitOrReject(feed.stream().map(r -> BigInteger.valueOf(r.amount()))
                .reduce(BigInteger.ZERO, BigInteger::add));
        long ledgerTotal = fitOrReject(ledger.stream().map(le -> BigInteger.valueOf(le.amount()))
                .reduce(BigInteger.ZERO, BigInteger::add));
        long difference = subtractExact(feedTotal, ledgerTotal);
        boolean balanced = discrepancies.isEmpty() && difference == 0;
        String summary = balanced
                ? "Conciliado: " + matched + " referencias cuadran; feed y ledger coinciden en " + feedTotal + " centavos."
                : "Descuadre: " + discrepancies.size() + " discrepancia(s). Feed=" + feedTotal
                        + " Ledger=" + ledgerTotal + " (diferencia " + difference + ").";

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
