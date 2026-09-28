package com.ledgermind.ledger.reconciliation;

/**
 * A discrepancy between the PSP feed and the ledger. {@code feedAmount}/{@code ledgerAmount} are 0 when the
 * record does not exist on that side. The {@code detail} is a readable explanation (what the agent narrates).
 */
public record Discrepancy(Type type, String ref, long feedAmount, long ledgerAmount, String detail) {

    public enum Type {
        /** The PSP settled something the ledger has not posted. */
        MISSING_IN_LEDGER,
        /** The ledger has a posting the PSP does not report. */
        MISSING_IN_FEED,
        /** Both have the reference, but for a different amount (e.g. an unposted fee/withholding). */
        AMOUNT_MISMATCH
    }
}
