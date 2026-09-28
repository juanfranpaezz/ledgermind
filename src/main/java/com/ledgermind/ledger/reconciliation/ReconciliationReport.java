package com.ledgermind.ledger.reconciliation;

import java.util.List;

/**
 * Result of reconciling the PSP feed against the ledger. {@code balanced} is true only if there is
 * no discrepancy at all. {@code summary} is a readable verdict for an agent to narrate.
 */
public record ReconciliationReport(int feedCount, int ledgerCount, int matched,
                                   long feedTotal, long ledgerTotal, long difference,
                                   List<Discrepancy> discrepancies, boolean balanced, String summary) {
}
