package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Idempotencia ESTRICTA: una clave identifica UNA operacion. Reusarla con parametros DISTINTOS es un
 * conflicto (no un replay silencioso que engania al cliente); reusarla con los MISMOS parametros es un
 * replay del asiento original.
 */
@SpringBootTest
@Testcontainers
class IdempotencyMismatchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;

    @Test
    void misma_clave_con_parametros_distintos_es_conflicto_y_no_aplica_nada() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:x", "ARS", false);
        Posting original = ledger.transfer("external:funding", "wallet:x", 100, "k1");

        // misma clave, OTRO monto -> conflicto, NO un replay silencioso
        assertThatThrownBy(() -> ledger.transfer("external:funding", "wallet:x", 999, "k1"))
                .isInstanceOf(IdempotencyConflictException.class);

        // misma clave, MISMOS parametros -> replay del asiento original (sin excepcion)
        Posting replay = ledger.transfer("external:funding", "wallet:x", 100, "k1");
        assertThat(replay.getId()).isEqualTo(original.getId());

        // el credito se aplico UNA sola vez (100); el intento conflictivo no movio nada
        assertThat(ledger.getByAddress("wallet:x").availableBalance()).isEqualTo(100);
    }
}
