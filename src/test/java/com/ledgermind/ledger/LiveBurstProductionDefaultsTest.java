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
 * La misma rafaga limpia de {@link LiveBurstFloorGraceTest}, con los valores de PRODUCCION (encadenador cada 5 s,
 * ventana 60 s, barrido cada 10 s, checkpoint cada 10 s): 0 "por fuera", 0 MANIPULACION DETECTADA, 0 tamperDetected.
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
    void rafaga_limpia_con_valores_de_produccion_no_dice_por_fuera_ni_manipulacion() throws Exception {
        BurstResult r = LiveBurstFloorGraceTest.runBurst(ledger, checkpoints, jdbc, 8_000, 5_000);
        System.out.println("[BURST][prod-defaults] " + r);
        assertThat(r.transfersOk()).isGreaterThan(50);
        assertThat(r.audits()).isGreaterThan(20);
        assertThat(r.porFuera()).isZero();
        assertThat(r.manipulacion()).isZero();
        assertThat(r.tamper()).isZero();
        assertThat(r.activeFlags()).isZero();
    }
}
