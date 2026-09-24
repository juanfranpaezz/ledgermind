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
 * Sonda de latencia de la transferencia (solo API que existe antes y despues del chequeo de congelamiento), para
 * medir el mismo camino con y sin la lectura de overdraft_flag. No afirma nada: imprime p50/p95. La asercion de
 * costo del chequeo vive en OverdraftSweepTest.
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
    void latencia_de_transferencia_p50_p95() {
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
