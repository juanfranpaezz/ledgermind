# ADR-0001: Optimistic locking + retry to move money

## Status
Accepted — 2026-06-12

## Context
An account's balance is updated on every transfer. If two concurrent transfers
touch the same account, they can produce a *lost update*: both read the
same balance, both decide "there are funds" and one overwrites the other's write. Result:
money is created or lost. In a ledger that is unacceptable.

We need safety under concurrency without sacrificing performance in the common case,
where two operations on the SAME account at the SAME instant are rare.

## Decision
We use **optimistic locking** with `@Version` (JPA) on `account`, plus a **retry loop
outside the transaction** (`TransactionTemplate`, `MAX_ATTEMPTS = 5`):

- On write, JPA issues `UPDATE account SET ..., version = version + 1 WHERE id = ? AND version = ?`.
- If another transaction already changed the row, the WHERE matches 0 rows → `OptimisticLockException`.
- The loop catches that exception and retries from scratch (re-reads the fresh balance and decides again).
- Each attempt is a new transaction; that is why the retry lives OUTSIDE the transactional boundary.

The `catch` does not only catch the optimistic conflict: it catches `ConcurrencyFailureException`, the
common superclass that covers **both** retryable cases. Besides the `@Version` optimistic conflict
(`ObjectOptimisticLockingFailureException`), that includes the **Postgres deadlock (`40P01`)** that
happens when two opposite transfers (A→B and B→A) take the row locks in reverse order.
The deadlock used to escape as a 500 even though it is perfectly retryable; now it falls into the same
retry loop. To reduce the deadlock *at its root* (not just absorb it), `hibernate.order_updates` /
`order_inserts` order the writes by id, so both transfers take the locks in the same
order and the collision becomes less frequent; the retry covers the residual. *(The truth is in
`TransferService.java`, not in this ADR: see the `catch (ConcurrencyFailureException)`, lines ~64-71.)*

As a second line of defence, no-overdraft is also enforced with a CHECK in the DB.

## Consequences
- (+) No pessimistic database locks (`SELECT ... FOR UPDATE`); very fast when collisions
  are rare (the common case).
- (+) Turns a silent corruption (lost update) into a loud, catchable error.
- (~) **Deadlocks CAN happen** (two opposite transfers that lock rows in reverse
  order → `40P01`). We do not avoid them completely: we **reduce** them by ordering the writes by id
  (`hibernate.order_updates`/`order_inserts`) and **absorb** the residual in the same retry loop,
  which treats the deadlock as one more transient, retryable conflict. *(Correction to an
  earlier version of this ADR that claimed "no deadlocks": the real code handles them explicitly
  in `TransferService.java`; the code is the truth.)*
- (−) Under high contention on the same row, there are retries (wasted work).
  In the 50-concurrent-transfer spike, some exhaust the 5 retries and fail with 409
  *even though the cause is contention, not lack of funds* (see the exact figure from one run in
  Verification, below, and the warning that it is data from ONE run).
  Mitigations applied: bounded exponential *backoff* + *jitter* (already in the code). Possible future
  mitigations: more retries or serializing per account.

## Alternatives considered
- **Pessimistic locking (`SELECT ... FOR UPDATE`)**: locks the row on read; the others wait.
  No retries, but it serializes access (slower under contention) and risks deadlocks.
  Rejected as the default; it is the alternative if contention on one account became dominant.
- **Balance table without a version**: vulnerable to lost updates. Rejected.

## Verification
`LedgerConcurrencySpikeTest`: 50 concurrent transfers against real Postgres (Testcontainers).
Invariants verified: conservation, no overdraft, global double entry (Σ credits − debits = 0),
one posting per success, and **that there was at least one retry** (otherwise the scheduler could have run the threads
almost serially and the test would pass without exercising concurrency — false coverage).

> **Note on the numbers.** The split `ok=10, insufficient=36, conflict=4` is the result of **ONE
> single run**, recorded before the exponential *backoff* + *jitter* was added. **It is not a
> guaranteed invariant** and the test, on purpose, does **not** assert it exactly: it only asserts ranges (successes
> between 1 and 10, depending on the balance) and that there were retries. With *backoff*+*jitter* now in the code,
> **fewer** transfers can be expected to exhaust the retries, so `conflict=4` may be
> out of date. Take it as an illustration of "under high contention some exhaust the retries", not
> as a fixed figure.
