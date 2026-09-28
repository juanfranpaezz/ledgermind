package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * THE CONCURRENCY SPIKE.
 *
 * <p>Fires N transfers IN PARALLEL from an account with a limited balance and verifies that,
 * whatever happens with the execution order, money is conserved: it is never created or lost,
 * there is never an overdraft, and the number of postings matches the number of successful transfers.
 *
 * <p>Runs against a REAL Postgres (Testcontainers), not H2: the locking behaviour we are
 * testing is Postgres-specific.
 */
@SpringBootTest
@Testcontainers
class LedgerConcurrencySpikeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final long FUNDED = 1_000;  // cents the source account will hold
    private static final long AMOUNT = 100;     // each transfer moves 100 cents
    private static final int CONCURRENT = 50;   // 50 simultaneous transfers
    private static final long MAX_SUCCESSES = FUNDED / AMOUNT; // at most 10 can succeed

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private PostingRepository postings;

    @Test
    void money_is_conserved_under_50_concurrent_transfers() throws Exception {
        // --- ARRANGE: an external source (can go negative), two wallets, and we fund A with FUNDED ---
        Account external = accounts.save(new Account("external:funding", "ARS", true));
        Account walletA = accounts.save(new Account("wallet:a", "ARS", false));
        Account walletB = accounts.save(new Account("wallet:b", "ARS", false));
        transferService.transfer(new TransferCommand(external.getId(), walletA.getId(), FUNDED, "seed-A"));

        // Counter baseline BEFORE the concurrent phase: the bean's AtomicLong is cumulative and is
        // shared between @SpringBootTest classes of the same cached context, so we measure the DELTA that
        // THESE 50 transfers cause, not an absolute value that could come from another test.
        long retriesBefore = transferService.retryCount();

        // --- ACT: we fire the 50 A->B transfers as simultaneously as possible ---
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        CountDownLatch startGate = new CountDownLatch(1); // single start signal for maximum contention
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT; i++) {
            final String idemKey = "transfer-" + i;
            tasks.add(() -> {
                startGate.await();
                try {
                    transferService.transfer(
                            new TransferCommand(walletA.getId(), walletB.getId(), AMOUNT, idemKey));
                    ok.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficient.incrementAndGet();
                } catch (TransferConflictException e) {
                    conflict.incrementAndGet();
                }
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> t : tasks) {
            futures.add(pool.submit(t));
        }
        startGate.countDown();          // largada
        for (Future<Void> f : futures) {
            f.get();                    // we wait for all of them to finish
        }
        pool.shutdown();

        int successes = ok.get();
        System.out.printf("SPIKE -> ok=%d insufficient=%d conflict=%d%n",
                successes, insufficient.get(), conflict.get());

        // --- ASSERT: the money invariants (they always hold, regardless of the order) ---
        Account a = accounts.findById(walletA.getId()).orElseThrow();
        Account b = accounts.findById(walletB.getId()).orElseThrow();

        // 1) NO OVERDRAFT: the source account never goes negative.
        assertThat(a.availableBalance()).isGreaterThanOrEqualTo(0);

        // 2) CONSERVATION: what A holds + what B holds is still FUNDED. Nothing was created or lost.
        assertThat(a.availableBalance() + b.availableBalance()).isEqualTo(FUNDED);

        // 3) GLOBAL DOUBLE ENTRY: the sum of (credits - debits) over ALL accounts is zero.
        long globalSum = accounts.findAll().stream()
                .mapToLong(acc -> acc.getPostedCredits() - acc.getPostedDebits())
                .sum();
        assertThat(globalSum).isZero();

        // 4) COHERENCE: B received exactly successes*AMOUNT, and A went down by the same amount.
        assertThat(b.availableBalance()).isEqualTo((long) successes * AMOUNT);
        assertThat(a.availableBalance()).isEqualTo(FUNDED - (long) successes * AMOUNT);

        // 5) ONE POSTING PER SUCCESS: the number of A->B postings matches the successful transfers.
        long postingsAtoB = postings.findAll().stream()
                .filter(p -> p.getDebitAccountId().equals(walletA.getId())
                        && p.getCreditAccountId().equals(walletB.getId()))
                .count();
        assertThat(postingsAtoB).isEqualTo(successes);

        // 6) NEVER more successes than the balance allows.
        assertThat(successes).isBetween(1, (int) MAX_SUCCESSES);

        // 7) REAL CONTENTION: with 50 threads released at once (CountDownLatch) on the SAME account, the
        //    optimistic lock MUST have forced at least one retry. We measure the DELTA against the baseline
        //    so that the assertion proves the contention of THIS spike's phase (and not retries accumulated
        //    by another test in the same context). Without this, the test could pass 'green' through sequential
        //    scheduling WITHOUT ever exercising the retry path -> false coverage of the core piece.
        assertThat(transferService.retryCount() - retriesBefore).isGreaterThan(0);
    }
}
