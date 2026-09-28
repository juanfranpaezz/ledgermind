package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.LiveBurstFloorGraceTest.BurstResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same clean burst as {@link LiveBurstFloorGraceTest}, with the PRODUCTION values (chainer every 5 s,
 * window 60 s, sweep every 10 s, checkpoint every 10 s): 0 "outside the app", 0 TAMPER DETECTED, 0 tamperDetected.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
@Testcontainers
class LiveBurstProductionDefaultsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void clean_burst_with_production_values_reports_no_outside_insertion_nor_tamper() throws Exception {
        BurstResult r = LiveBurstFloorGraceTest.runBurst(ledger, checkpoints, jdbc, 8_000, 5_000);
        System.out.println("[BURST][prod-defaults] " + r);
        assertThat(r.transfersOk()).isGreaterThan(50);
        assertThat(r.audits()).isGreaterThan(20);
        assertThat(r.outsideAppFlags()).isZero();
        assertThat(r.manipulacion()).isZero();
        assertThat(r.tamper()).isZero();
        assertThat(r.activeFlags()).isZero();
    }
}
