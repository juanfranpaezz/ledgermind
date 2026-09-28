package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.JournalCheckpointService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scaffolding of the visual demo ('demo' profile): {@code reset} leaves a signed, intact chain;
 * {@code tamper} breaks it and the audit detects it (valid signature + broken chain). It makes sure the
 * 3-button demo works end to end.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@ActiveProfiles("demo")
@Testcontainers
class DemoSupportControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private DemoSupportController demo;
    @Autowired
    private JournalCheckpointService checkpoints;

    @Test
    void reset_deja_integro_y_tamper_es_detectado() {
        demo.reset();
        var clean = checkpoints.audit();
        assertThat(clean.checkpointPresent()).isTrue();
        assertThat(clean.chainIntact()).isTrue();
        assertThat(clean.tamperDetected()).isFalse();

        demo.tamper();
        var tampered = checkpoints.audit();
        assertThat(tampered.tamperDetected()).isTrue();
        assertThat(tampered.chainIntact()).isFalse();
        assertThat(tampered.signatureValid()).isTrue();   // the signature is still valid; the chain exposes it
    }
}
