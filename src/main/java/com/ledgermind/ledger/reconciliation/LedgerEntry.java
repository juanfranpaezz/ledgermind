package com.ledgermind.ledger.reconciliation;

/**
 * Proyeccion de un asiento del ledger para reconciliar: su referencia externa (la {@code idempotencyKey},
 * que el cliente suele setear con su id de orden/pago) y el importe. El matcher trabaja contra esto, no
 * contra la entidad JPA — asi la logica de matching es pura y testeable sin base de datos.
 */
public record LedgerEntry(String ref, long amount) {
}
