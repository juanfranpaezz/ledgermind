package com.ledgermind.ledger.reconciliation;

import java.time.Instant;

/**
 * Una linea del feed de liquidacion (settlement) de un PSP externo: "para la operacion {externalRef} se
 * liquidaron {amount} centavos el {occurredAt}". Es lo que el PSP dice que pasó; la reconciliacion lo
 * cruza contra lo que el ledger registró.
 */
public record SettlementRecord(String externalRef, long amount, Instant occurredAt) {
}
