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
 * EL SPIKE DE CONCURRENCIA (gate de la semana 1).
 *
 * <p>Dispara N transferencias EN PARALELO desde una cuenta con saldo limitado y verifica que,
 * pase lo que pase con el orden de ejecucion, el dinero se conserva: nunca se crea ni se pierde,
 * nunca hay sobregiro, y la cantidad de asientos coincide con la cantidad de transferencias exitosas.
 *
 * <p>Corre contra un Postgres REAL (Testcontainers), no H2: el comportamiento de locking que
 * estamos probando es especifico de Postgres.
 */
@SpringBootTest
@Testcontainers
class LedgerConcurrencySpikeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final long FUNDED = 1_000;  // centavos que tendra la cuenta origen
    private static final long AMOUNT = 100;     // cada transferencia mueve 100 centavos
    private static final int CONCURRENT = 50;   // 50 transferencias simultaneas
    private static final long MAX_SUCCESSES = FUNDED / AMOUNT; // a lo sumo 10 pueden salir bien

    @Autowired
    private TransferService transferService;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private PostingRepository postings;

    @Test
    void dinero_se_conserva_bajo_50_transferencias_concurrentes() throws Exception {
        // --- ARRANGE: una fuente externa (puede ir a negativo), dos wallets, y fondeamos A con FUNDED ---
        Account external = accounts.save(new Account("external:funding", "ARS", true));
        Account walletA = accounts.save(new Account("wallet:a", "ARS", false));
        Account walletB = accounts.save(new Account("wallet:b", "ARS", false));
        transferService.transfer(new TransferCommand(external.getId(), walletA.getId(), FUNDED, "seed-A"));

        // --- ACT: disparamos las 50 transferencias A->B lo mas simultaneas posible ---
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        CountDownLatch startGate = new CountDownLatch(1); // largada unica para maxima contencion
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
            f.get();                    // esperamos a que todas terminen
        }
        pool.shutdown();

        int successes = ok.get();
        System.out.printf("SPIKE -> ok=%d insufficient=%d conflict=%d%n",
                successes, insufficient.get(), conflict.get());

        // --- ASSERT: los invariantes del dinero (valen siempre, sin importar el orden) ---
        Account a = accounts.findById(walletA.getId()).orElseThrow();
        Account b = accounts.findById(walletB.getId()).orElseThrow();

        // 1) NO SOBREGIRO: la cuenta origen nunca queda negativa.
        assertThat(a.availableBalance()).isGreaterThanOrEqualTo(0);

        // 2) CONSERVACION: lo que tiene A + lo que tiene B sigue siendo FUNDED. Nada se creo ni perdio.
        assertThat(a.availableBalance() + b.availableBalance()).isEqualTo(FUNDED);

        // 3) DOBLE ENTRADA GLOBAL: la suma de (creditos - debitos) de TODAS las cuentas es cero.
        long globalSum = accounts.findAll().stream()
                .mapToLong(acc -> acc.getPostedCredits() - acc.getPostedDebits())
                .sum();
        assertThat(globalSum).isZero();

        // 4) COHERENCIA: B recibio exactamente successes*AMOUNT, y A bajo en la misma cantidad.
        assertThat(b.availableBalance()).isEqualTo((long) successes * AMOUNT);
        assertThat(a.availableBalance()).isEqualTo(FUNDED - (long) successes * AMOUNT);

        // 5) UN ASIENTO POR EXITO: la cantidad de asientos A->B coincide con las transferencias exitosas.
        long postingsAtoB = postings.findAll().stream()
                .filter(p -> p.getDebitAccountId().equals(walletA.getId())
                        && p.getCreditAccountId().equals(walletB.getId()))
                .count();
        assertThat(postingsAtoB).isEqualTo(successes);

        // 6) NUNCA mas exitos de los que el saldo permite.
        assertThat(successes).isBetween(1, (int) MAX_SUCCESSES);

        // 7) CONTENCION REAL: con 50 hilos largados a la vez (CountDownLatch) sobre la MISMA cuenta, el
        //    optimistic-lock DEBE haber forzado al menos un reintento. Sin esto, el test podria pasar 'verde'
        //    por puro scheduling secuencial SIN ejercitar nunca el camino retry -> falsa cobertura sobre la
        //    pieza estrella. (El seed inicial no genera contencion: este contador refleja la fase concurrente.)
        assertThat(transferService.retryCount()).isGreaterThan(0);
    }
}
