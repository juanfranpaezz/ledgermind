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
 * STRICT idempotency: a key identifies ONE operation. Reusing it with DIFFERENT parameters is a
 * conflict (not a silent replay that misleads the client); reusing it with the SAME parameters is a
 * replay of the original posting.
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

        // same key, ANOTHER amount -> conflict, NOT a silent replay
        assertThatThrownBy(() -> ledger.transfer("external:funding", "wallet:x", 999, "k1"))
                .isInstanceOf(IdempotencyConflictException.class);

        // same key, SAME parameters -> replay of the original posting (no exception)
        Posting replay = ledger.transfer("external:funding", "wallet:x", 100, "k1");
        assertThat(replay.getId()).isEqualTo(original.getId());

        // the credit was applied ONLY once (100); the conflicting attempt moved nothing
        assertThat(ledger.getByAddress("wallet:x").availableBalance()).isEqualTo(100);
    }
}
