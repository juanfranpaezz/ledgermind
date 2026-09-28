# ADR-0002: Money as integers and an immutable double-entry ledger

## Status
Accepted — 2026-06-12

## Context
The system moves money. We need to represent it without rounding errors, be able to audit
every movement, and make it structurally impossible for money to "disappear" or be duplicated.

## Decision
1. **Money as integers in minor units (cents), `BIGINT`/`long`** — never `float`/`double`.
2. **Double-entry ledger**: every movement is a posting (`posting`) with one debited account
   and one credited account for the same amount. The global sum of (credits − debits) is always 0.
3. **Immutable postings (append-only)**: the `posting` table only accepts INSERT. A correction
   is a new reversing posting (storno), never UPDATE/DELETE.
4. **Derived balance**: the account stores accumulated counters (`posted_debits`, `posted_credits`,
   plus `pending_*` for two-phase); the balance is computed as `credits − debits` (O(1)). There is no
   mutable `balance` field that can be overwritten.
5. **Hierarchical account addresses** (`wallet:user:x`, `mp:settlement`, `external:funding`) and
   **one asset per account** (currencies are never mixed in the same balance).

## Consequences
- (+) Exact to the cent; full auditability (complete, immutable history).
- (+) Impossible to create/lose money: the zero-sum invariant is structural and, in part, enforced in the DB.
- (+) The balance is O(1) and the journal naturally feeds a read-model (future audit MCP).
- (−) More writes: every transfer updates the counters of two accounts (which motivates [ADR-0001](0001-optimistic-locking.md)).
- (−) Funding needs an external account that can go negative (`allow_negative`), the accounting
  counterpart of real money (reconciled against the PSP — future reconciliation module).

## Alternatives considered
- **`BigDecimal`**: valid and exact; we chose integer cents for simplicity and performance,
  with `BigDecimal` as an equivalent option. What is rejected is `double`/`float`.
- **Single-entry (balance table)**: simpler but undebuggable and without an audit trail. Rejected.
- **Movement DSL (like Formance's Numscript)**: over-engineering for this scope. Rejected
  deliberately; in Java the "DSL" is a typed domain service.

## References
Model inspired by TigerBeetle (immutable accounts + transfers, balance counters) and by the canonical
double-entry accounting model.
