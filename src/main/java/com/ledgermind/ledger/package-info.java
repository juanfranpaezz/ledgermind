/**
 * Modulo LEDGER: el nucleo del sistema.
 *
 * <p>Modela el dinero como asientos de doble entrada inmutables (append-only):
 * cuentas, postings (debito/credito que suman cero), saldos derivados e idempotencia.
 * Es el corazon que debe ser estructuralmente imposible de hacer perder o duplicar dinero
 * bajo concurrencia y reintentos.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ledger")
package com.ledgermind.ledger;
