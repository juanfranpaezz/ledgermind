package com.ledgermind.ledger.reconciliation;

import java.time.Instant;

/**
 * A line of an external PSP's settlement feed: "for operation {externalRef}
 * {amount} cents were settled on {occurredAt}". It is what the PSP says happened; reconciliation
 * matches it against what the ledger recorded.
 */
public record SettlementRecord(String externalRef, long amount, Instant occurredAt) {
}
