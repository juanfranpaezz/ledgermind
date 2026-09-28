package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * EXACTLY-ONCE idempotency under CONCURRENCY: N simultaneous requests with the SAME idempotencyKey must
 * apply the transfer ONLY once and ALL receive the SAME posting (replay), without any of them blowing up.
 *
 * <p>Check-then-insert on its own is NOT enough: under a race, two requests pass the idempotency
 * check together (both see the key free), both insert, and the UNIQUE catches the second one with a
 * {@code DataIntegrityViolationException}. The correct behaviour is to turn that violation into a REPLAY
 * of the original posting, not to propagate a 500.
 */
@SpringBootTest
@Testcontainers
class IdempotencyReplayConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final int CONCURRENT = 24;
    private static final long AMOUNT = 10_000;

    @Autowired
    private LedgerService ledger;
    @Autowired
    private PostingRepository postings;

    @Test
    void misma_clave_concurrente_se_aplica_una_sola_vez_y_todos_reciben_el_mismo_asiento() throws Exception {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:dest", "ARS", false);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Long> postingIds = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT; i++) {
            tasks.add(() -> {
                startGate.await();                 // single start signal: maximum contention on the same key
                try {
                    Posting p = ledger.transfer("external:funding", "wallet:dest", AMOUNT, "same-key");
                    postingIds.add(p.getId());
                } catch (Throwable t) {
                    errors.add(t);
                }
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> t : tasks) {
            futures.add(pool.submit(t));
        }
        startGate.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();

        // 1) NOBODY blows up: a repeated key under a race is a replay, not an error.
        assertThat(errors).as("no request must fail because of the idempotency race").isEmpty();
        // 2) ALL receive the SAME posting (replay of the original operation).
        assertThat(postingIds).hasSize(CONCURRENT);
        assertThat(Set.copyOf(postingIds)).as("all requests return the same posting").hasSize(1);
        // 3) There is EXACTLY one posting with that key.
        assertThat(postings.findByIdempotencyKey("same-key")).isPresent();
        // 4) The transfer was applied ONLY once (the credit is AMOUNT, not N*AMOUNT).
        assertThat(ledger.getByAddress("wallet:dest").availableBalance()).isEqualTo(AMOUNT);
    }
}
