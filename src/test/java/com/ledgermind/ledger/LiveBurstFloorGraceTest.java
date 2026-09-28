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
 * LIVE: a CLEAN burst (zero tampering) of 8 s with the real schedulers, the chainer
 * every 300 ms and the window at its FLOOR (3 cycles = 900 ms), the sweep every 200 ms and the audit in a loop. Before the fix
 * this same burst gave 13 of 80 audits "inserted outside the app" and 16 TAMPER DETECTED from a balance
 * mismatch. Criterion: 0 "outside the app", 0 TAMPER DETECTED, 0 tamperDetected, 0 frozen accounts.
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

    /** What the audit saw during the burst. */
    record BurstResult(long transfersOk, long transfersFailed, long audits, long outsideAppFlags, long tamperHeadlines,
                       long tamper, long degradedBehind, long degradedStopped, long staleSeen, long activeFlags,
                       Map<String, Integer> histogram) {
    }

    /** 10 threads of real transfers during {@code burstMs} (8 from funding, 2 between wallets), audit in a loop. */
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
                        failed.incrementAndGet();                   // e.g. retries exhausted by contention
                    }
                }
            });
        }
        Map<String, Integer> hist = new ConcurrentHashMap<>();
        AtomicLong audits = new AtomicLong();
        AtomicLong outsideAppFlags = new AtomicLong();
        AtomicLong manip = new AtomicLong();
        AtomicLong tamper = new AtomicLong();
        AtomicLong behind = new AtomicLong();
        AtomicLong stopped = new AtomicLong();
        AtomicLong stale = new AtomicLong();
        Future<?> poller = pool.submit(() -> {
            while (!stopPoll.get()) {
                JournalIntegrityReport r = checkpoints.audit();
                audits.incrementAndGet();
                String v = r.verdict();
                if (v.contains("outside the app")) {
                    outsideAppFlags.incrementAndGet();
                }
                if (v.contains("TAMPER DETECTED")) {
                    manip.incrementAndGet();
                }
                if (r.tamperDetected()) {
                    tamper.incrementAndGet();
                }
                if (r.coverageDegraded() && r.coverageReason() == CoverageReason.ATRASADO) {
                    behind.incrementAndGet();
                }
                if (r.coverageDegraded() && r.coverageReason() == CoverageReason.DETENIDO) {
                    stopped.incrementAndGet();
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
        poller.get(30, TimeUnit.SECONDS);                           // an exception from the audit fails the test
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        long flags = jdbc.queryForObject("SELECT count(*) FROM overdraft_flag WHERE cleared_at IS NULL", Long.class);
        return new BurstResult(ok.get(), failed.get(), audits.get(), outsideAppFlags.get(), manip.get(), tamper.get(),
                behind.get(), stopped.get(), stale.get(), flags, new TreeMap<>(hist));
    }

    @Test
    void clean_burst_with_the_window_at_its_floor_reports_no_outside_insertion_nor_tamper() throws Exception {
        BurstResult r = runBurst(ledger, checkpoints, jdbc, 8_000, 5_000);
        System.out.println("[BURST][floor-900ms] " + r);
        assertThat(r.transfersOk()).isGreaterThan(50);
        assertThat(r.audits()).isGreaterThan(20);
        assertThat(r.outsideAppFlags()).isZero();
        assertThat(r.tamperHeadlines()).isZero();
        assertThat(r.tamper()).isZero();
        assertThat(r.activeFlags()).isZero();
    }
}
