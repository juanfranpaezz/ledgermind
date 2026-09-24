package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.JournalCheckpointService.CoverageReason;
import com.ledgermind.ledger.JournalCheckpointService.JournalIntegrityReport;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A4 del gate 2026-09-24, EN VIVO: rafaga LIMPIA (cero manipulacion) de 8 s con los schedulers reales, el encadenador
 * cada 300 ms y la ventana en su PISO (3 ciclos = 900 ms), el barrido cada 200 ms y la auditoria en loop. En el gate
 * esta misma rafaga dio 13 de 80 auditorias "insertado por fuera de la app" y 16 MANIPULACION DETECTADA por descuadre de
 * saldos. Criterio: 0 "por fuera", 0 MANIPULACION DETECTADA, 0 tamperDetected, 0 cuentas congeladas.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=300",
        "ledgermind.journal.unchained-grace-ms=0",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=200",
        "spring.datasource.hikari.maximum-pool-size=20"
})
@Testcontainers
class LiveBurstFloorGraceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private JdbcTemplate jdbc;

    /** Lo que vio la auditoria durante la rafaga. */
    record BurstResult(long transfersOk, long transfersFailed, long audits, long porFuera, long manipulacion,
                       long tamper, long degradedAtrasado, long degradedDetenido, long staleSeen, long activeFlags,
                       Map<String, Integer> histogram) {
    }

    /** 10 hilos de transferencias reales durante {@code burstMs} (8 desde funding, 2 entre wallets), auditoria en loop. */
    static BurstResult runBurst(LedgerService ledger, JournalCheckpointService checkpoints, JdbcTemplate jdbc,
                                long burstMs, long settleMs) throws Exception {
        ledger.createAccount("external:funding", "ARS", true);
        String[] w = {"wallet:a", "wallet:b", "wallet:c", "wallet:d"};
        for (String a : w) {
            ledger.createAccount(a, "ARS", false);
            ledger.transfer("external:funding", a, 100_000, "seed-" + a);
        }
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean stopPoll = new AtomicBoolean(false);
        AtomicLong ok = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        for (int t = 0; t < 10; t++) {
            final int tt = t;
            pool.submit(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (!stop.get()) {
                    try {
                        if (tt < 8) {
                            ledger.transfer("external:funding", w[rnd.nextInt(4)], 1, UUID.randomUUID().toString());
                        } else {
                            ledger.transfer(w[tt - 8], w[tt - 6], 1, UUID.randomUUID().toString());
                        }
                        ok.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();                   // p.ej. reintentos agotados por contencion
                    }
                }
            });
        }
        Map<String, Integer> hist = new ConcurrentHashMap<>();
        AtomicLong audits = new AtomicLong();
        AtomicLong porFuera = new AtomicLong();
        AtomicLong manip = new AtomicLong();
        AtomicLong tamper = new AtomicLong();
        AtomicLong atrasado = new AtomicLong();
        AtomicLong detenido = new AtomicLong();
        AtomicLong stale = new AtomicLong();
        Future<?> poller = pool.submit(() -> {
            while (!stopPoll.get()) {
                JournalIntegrityReport r = checkpoints.audit();
                audits.incrementAndGet();
                String v = r.verdict();
                if (v.contains("por fuera")) {
                    porFuera.incrementAndGet();
                }
                if (v.contains("MANIPULACION DETECTADA")) {
                    manip.incrementAndGet();
                }
                if (r.tamperDetected()) {
                    tamper.incrementAndGet();
                }
                if (r.coverageDegraded() && r.coverageReason() == CoverageReason.ATRASADO) {
                    atrasado.incrementAndGet();
                }
                if (r.coverageDegraded() && r.coverageReason() == CoverageReason.DETENIDO) {
                    detenido.incrementAndGet();
                }
                if (r.staleUnchainedPostings() > 0) {
                    stale.incrementAndGet();
                }
                hist.merge("tamper=" + r.tamperDetected() + "|degraded=" + r.coverageDegraded() + "|"
                        + r.coverageReason() + "|balances=" + r.balancesConsistent(), 1, Integer::sum);
            }
            return null;
        });
        Thread.sleep(burstMs);
        stop.set(true);
        Thread.sleep(settleMs);
        stopPoll.set(true);
        poller.get(30, TimeUnit.SECONDS);                           // una excepcion de la auditoria falla el test
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        long flags = jdbc.queryForObject("SELECT count(*) FROM overdraft_flag WHERE cleared_at IS NULL", Long.class);
        return new BurstResult(ok.get(), failed.get(), audits.get(), porFuera.get(), manip.get(), tamper.get(),
                atrasado.get(), detenido.get(), stale.get(), flags, new TreeMap<>(hist));
    }

    @Test
    void rafaga_limpia_con_la_ventana_en_su_piso_no_dice_por_fuera_ni_manipulacion() throws Exception {
        BurstResult r = runBurst(ledger, checkpoints, jdbc, 8_000, 5_000);
        System.out.println("[BURST][floor-900ms] " + r);
        assertThat(r.transfersOk()).isGreaterThan(50);
        assertThat(r.audits()).isGreaterThan(20);
        assertThat(r.porFuera()).isZero();
        assertThat(r.manipulacion()).isZero();
        assertThat(r.tamper()).isZero();
        assertThat(r.activeFlags()).isZero();
    }
}
