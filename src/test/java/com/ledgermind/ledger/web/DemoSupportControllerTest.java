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
 * Andamiaje de la demo visual (perfil 'demo'): {@code reset} deja una cadena firmada e integra;
 * {@code tamper} la rompe y la auditoria lo detecta (firma valida + cadena rota). Asegura que la
 * demo de 3 botones funciona de punta a punta.
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
        assertThat(tampered.signatureValid()).isTrue();   // la firma sigue valida; lo delata la cadena
    }
}
