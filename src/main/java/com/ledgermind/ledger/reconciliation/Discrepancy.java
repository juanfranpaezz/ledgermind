package com.ledgermind.ledger.reconciliation;

/**
 * Un descuadre entre el feed del PSP y el ledger. {@code feedAmount}/{@code ledgerAmount} son 0 cuando el
 * registro no existe de ese lado. El {@code detail} es una explicacion legible (lo que el agente narra).
 */
public record Discrepancy(Type type, String ref, long feedAmount, long ledgerAmount, String detail) {

    public enum Type {
        /** El PSP liquidó algo que el ledger no tiene asentado. */
        MISSING_IN_LEDGER,
        /** El ledger tiene un asiento que el PSP no reporta. */
        MISSING_IN_FEED,
        /** Ambos tienen la referencia, pero por importe distinto (p.ej. comision/retencion no asentada). */
        AMOUNT_MISMATCH
    }
}
