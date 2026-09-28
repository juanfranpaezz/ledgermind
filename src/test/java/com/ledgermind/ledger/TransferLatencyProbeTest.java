package com.ledgermind.ledger;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Transfer latency probe (only an API that exists before and after the freeze check), to
 * measure the same path with and without the overdraft_flag read. It asserts nothing: it prints p50/p95. The assertion on
 * the check's cost lives in OverdraftSweepTest.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000"
})
@Testcontainers
class TransferLatencyProbeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void transfer_latency_p50_p95() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        for (int i = 0; i < 50; i++) {
            ledger.transfer("external:funding", "wallet:a", 1, "warm-" + i);
        }
        int n = 400;
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            ledger.transfer("external:funding", "wallet:a", 1, "probe-" + i);
            t[i] = System.nanoTime() - t0;
        }
        Arrays.sort(t);
        System.out.println("[LATENCY] n=" + n + " transfer p50=" + t[n / 2] / 1e6 + " ms p95=" + t[n * 95 / 100] / 1e6
                + " ms");
    }
}
