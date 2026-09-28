package com.ledgermind.ledger.reconciliation;

/**
 * Projection of a ledger posting for reconciliation: its external reference (the {@code idempotencyKey},
 * which the client usually sets to its order/payment id) and the amount. The matcher works against this, not
 * against the JPA entity — so the matching logic is pure and testable without a database.
 */
public record LedgerEntry(String ref, long amount) {
}
