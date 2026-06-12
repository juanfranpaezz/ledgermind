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
 * Idempotencia EXACTLY-ONCE bajo CONCURRENCIA: N requests simultaneos con la MISMA idempotencyKey deben
 * aplicar la transferencia UNA sola vez y TODOS recibir el MISMO asiento (replay), sin que ninguno explote.
 *
 * <p>El check-then-insert por si solo NO alcanza: bajo carrera, dos requests pasan juntas el chequeo de
 * idempotencia (ambas ven la clave libre), ambas insertan, y la UNIQUE atrapa a la segunda con una
 * {@code DataIntegrityViolationException}. La operacion correcta es convertir esa violacion en un REPLAY
 * del asiento original, no propagar un 500.
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
                startGate.await();                 // largada unica: maxima contencion sobre la misma clave
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

        // 1) NADIE explota: una clave repetida bajo carrera es un replay, no un error.
        assertThat(errors).as("ningun request debe fallar por la carrera de idempotencia").isEmpty();
        // 2) TODOS reciben el MISMO asiento (replay de la operacion original).
        assertThat(postingIds).hasSize(CONCURRENT);
        assertThat(Set.copyOf(postingIds)).as("todos los requests devuelven el mismo asiento").hasSize(1);
        // 3) Existe EXACTAMENTE un asiento con esa clave.
        assertThat(postings.findByIdempotencyKey("same-key")).isPresent();
        // 4) La transferencia se aplico UNA sola vez (el credito es AMOUNT, no N*AMOUNT).
        assertThat(ledger.getByAddress("wallet:dest").availableBalance()).isEqualTo(AMOUNT);
    }
}
