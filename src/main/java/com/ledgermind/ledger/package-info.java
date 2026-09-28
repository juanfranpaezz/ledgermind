/**
 * LEDGER module: the core of the system.
 *
 * <p>It models money as immutable (append-only) double-entry postings:
 * accounts, postings (debit/credit that sum to zero), derived balances and idempotency.
 * It is the heart that must make it structurally impossible to lose or duplicate money
 * under concurrency and retries.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Ledger")
package com.ledgermind.ledger;
