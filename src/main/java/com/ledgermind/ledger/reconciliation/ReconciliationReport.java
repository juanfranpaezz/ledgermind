package com.ledgermind.ledger.reconciliation;

import java.util.List;

/**
 * Resultado de reconciliar el feed del PSP contra el ledger. {@code balanced} es true solo si no hay
 * ninguna discrepancia. {@code summary} es un veredicto legible para que un agente lo narre.
 */
public record ReconciliationReport(int feedCount, int ledgerCount, int matched,
                                   long feedTotal, long ledgerTotal, long difference,
                                   List<Discrepancy> discrepancies, boolean balanced, String summary) {
}
