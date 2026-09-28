package com.ledgermind.ledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies money transfers as double-entry postings, safe under concurrency.
 *
 * <p>Concurrency strategy: OPTIMISTIC LOCKING + RETRY.
 * The retry loop lives OUTSIDE the transaction: every attempt opens its own transaction
 * and re-reads fresh accounts. If two transfers collide on the same account, one fails at
 * commit with {@link ObjectOptimisticLockingFailureException}; we catch it and retry with
 * the already-updated state. That makes it impossible to spend the same balance twice.
 *
 * <p>EXACTLY-ONCE idempotency under concurrency: the {@code findByIdempotencyKey} check handles
 * sequential retries, but NOT a race (two requests with the same key see the key free at the
 * same time). For that race, the {@code UNIQUE} on {@code idempotency_key} is the real safety net: when it catches the
 * duplicate we get a {@link DataIntegrityViolationException}, and we turn it into a REPLAY of the
 * already-committed posting (not a 500). That way a concurrent duplicate returns the original response, just like a
 * sequential retry.
 */
@Service
public class TransferService {

    /** How many times we retry a transfer that loses the race before giving up. */
    private static final int MAX_ATTEMPTS = 5;

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final OverdraftSweeper freezes;
    private final TransactionTemplate tx;
    /** Accumulated retries on transient conflicts. Observable so that a test can assert there was real contention. */
    private final AtomicLong retries = new AtomicLong(0);

    public TransferService(AccountRepository accounts,
                           PostingRepository postings,
                           OverdraftSweeper freezes,
                           PlatformTransactionManager txManager,
                           MeterRegistry meterRegistry) {
        this.accounts = accounts;
        this.postings = postings;
        this.freezes = freezes;
        // Programmatic transactions: we need to control the transactional boundary by hand
        // so that the retry stays OUTSIDE (every attempt = a new transaction).
        this.tx = new TransactionTemplate(txManager);
        // We expose the retries as a Micrometer gauge: it is the observable CONCURRENCY PRESSURE
        // in Prometheus/Grafana. It rises when two transfers collide on the same account (lost optimistic
        // lock) or deadlock (40P01). It is the SAME counter the concurrency spike asserts is
        // > 0; here it also serves as operational telemetry (it is not a coupled test hook).
        Gauge.builder("ledgermind.transfer.retries", retries, AtomicLong::get)
                .description("Accumulated transfer retries on transient conflicts (optimistic lock or deadlock)")
                .register(meterRegistry);
    }

    public Posting transfer(TransferCommand cmd) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> apply(cmd));
            } catch (ConcurrencyFailureException conflict) {
                // TRANSIENT concurrency conflict. It covers BOTH retryable cases, which extend
                // ConcurrencyFailureException: (1) the @Version optimistic conflict
                // ({@link ObjectOptimisticLockingFailureException}) when two transfers collide on
                // the same account; and (2) the Postgres DEADLOCK (40P01 -> CannotAcquireLockException) when
                // two opposite transfers (A->B and B->A) lock the rows in reverse order. Before, only the
                // optimistic conflict was caught and the deadlock escaped as a 500 even though it is perfectly retryable.
                // The transaction was rolled back entirely (we wrote nothing). We retry from scratch.
                retries.incrementAndGet();
                if (attempt >= MAX_ATTEMPTS) {
                    throw new TransferConflictException(attempt);
                }
                backoffBeforeRetry(attempt);   // exponential backoff + jitter: breaks the thundering herd
            } catch (DataIntegrityViolationException duplicate) {
                // IDEMPOTENCY race: another request with the same key inserted first and we hit
                // the UNIQUE. The operation WAS already applied exactly once -> we return that posting
                // (replay), not an error. If the violation came from ANOTHER constraint, there will be no posting with
                // this key and we rethrow the original exception.
                Posting existing = postings.findByIdempotencyKey(cmd.idempotencyKey())
                        .orElseThrow(() -> duplicate);
                return replayOrConflict(existing, cmd);
            }
        }
    }

    /** Accumulated retries on transient conflicts (optimistic or deadlock). The concurrency spike uses it
     *  to assert that contention was really exercised (a test that passes without a single retry proves nothing). */
    public long retryCount() {
        return retries.get();
    }

    /**
     * BOUNDED exponential backoff + jitter before retrying. Without it, under high contention the N losers
     * compete again in lockstep (thundering herd) and transfers with enough balance can exhaust the retries and fail
     * with 409 even though there were funds. The cap (20 ms) keeps latency and the tests bounded.
     */
    private static void backoffBeforeRetry(int attempt) {
        long base = Math.min(20L, 1L << (attempt - 1));                        // 1,2,4,8,16 -> tope 20 ms
        long sleepMs = base + ThreadLocalRandom.current().nextLong(base + 1);  // + jitter en [0, base]
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TransferConflictException(attempt);
        }
    }

    /**
     * An idempotency key identifies ONE operation. If the posting that already exists matches the request,
     * it is a legitimate REPLAY (same result). If the parameters DIFFER, the client reused the key for
     * something else: returning the original would mislead it, so we throw {@link IdempotencyConflictException}.
     */
    private Posting replayOrConflict(Posting existing, TransferCommand cmd) {
        boolean sameOperation = existing.getDebitAccountId().equals(cmd.debitAccountId())
                && existing.getCreditAccountId().equals(cmd.creditAccountId())
                && existing.getAmount() == cmd.amount();
        if (sameOperation) {
            return existing;
        }
        throw new IdempotencyConflictException(cmd.idempotencyKey());
    }

    /** One attempt. Runs inside ONE transaction. The ledger's invariants live here. */
    private Posting apply(TransferCommand cmd) {
        // 1) Idempotency: if a posting with this key already exists, it is a replay (same params) or a
        //    conflict (the key was reused for another operation). We never duplicate.
        var existing = postings.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), cmd);
        }

        // 1.5) Invariant: a transfer moves money BETWEEN two DIFFERENT accounts. Validating it in Java
        //      (besides the posting_distinct_accounts CHECK in the DB) classifies it as a clean 400, not as
        //      an opaque 500 from the CHECK violation that would slip through the idempotency catch.
        if (cmd.debitAccountId().equals(cmd.creditAccountId())) {
            throw new IllegalArgumentException("An account cannot transfer to itself.");
        }
        // Overdraft freeze: ONE indexed read, no balance re-derivation on the hot path.
        freezes.assertNotFrozen(cmd.debitAccountId(), cmd.creditAccountId());

        // 2) We load both accounts. They are 'managed' entities: their changes are flushed at commit
        //    with the version check (optimistic locking).
        Account debit = accounts.findById(cmd.debitAccountId())
                .orElseThrow(() -> new AccountNotFoundException(cmd.debitAccountId()));
        Account credit = accounts.findById(cmd.creditAccountId())
                .orElseThrow(() -> new AccountNotFoundException(cmd.creditAccountId()));

        // 3) Business invariants (besides the CHECKs in the DB: second line of defence).
        if (!debit.getAsset().equals(credit.getAsset())) {
            throw new IllegalArgumentException("Cannot transfer between different assets");
        }
        if (!debit.isAllowNegative() && debit.availableBalance() < cmd.amount()) {
            throw new InsufficientFundsException(debit.getId(), debit.availableBalance(), cmd.amount());
        }

        // 4) Double entry: debit one account and credit the other by the same amount.
        debit.applyDebit(cmd.amount());
        credit.applyCredit(cmd.amount());

        // 5) We record the immutable posting. The UNIQUE on idempotency_key is the real net against duplicates.
        Posting posting = new Posting(debit.getId(), credit.getId(), cmd.amount(), debit.getAsset(),
                cmd.idempotencyKey());
        return postings.save(posting);
        // When the transaction closes, JPA issues for each account:
        //   UPDATE account SET ..., version = version + 1 WHERE id = ? AND version = ?
        // If another transaction already changed it -> 0 rows -> ObjectOptimisticLockingFailureException -> retry.
    }
}
