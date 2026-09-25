package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Crypto-agility END-TO-END a nivel servicio: corre con el firmante ACTIVO seteado a Ed25519 (no el
 * default ML-DSA-65) y prueba que el checkpoint se firma con Ed25519 y que la VERIFICACION lo despacha
 * con Ed25519 (no con ML-DSA). Esto demuestra que el dispatch por algoritmo del
 * {@link JournalCheckpointService} esta cableado de verdad, no solo en el registry aislado. Mismo tamper
 * que el test de ML-DSA: tras editar un asiento, la firma sigue valida y quien delata es el SHA-256.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.journal.signer.algorithm=Ed25519"
})
@Testcontainers
class JournalCheckpointAgilityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalChainer chainer;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
    }

    @Test
    void firma_con_Ed25519_y_la_verificacion_despacha_a_Ed25519() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        Posting t1 = ledger.transfer("wallet:a", "wallet:b", 30_000, "t-1");

        chainer.chainPendingPostings();
        var cp = checkpoints.checkpointIfHeadAdvanced();
        assertThat(cp).isPresent();
        // el checkpoint quedo firmado con el esquema ACTIVO = Ed25519, NO con el default ML-DSA-65
        assertThat(cp.get().getAlgorithm()).isEqualTo("Ed25519");

        // la verificacion despacha por ese algoritmo y cierra en verde
        var ok = checkpoints.verifyLatest();
        assertThat(ok.algorithm()).isEqualTo("Ed25519");
        assertThat(ok.signatureValid()).isTrue();
        assertThat(ok.chainIntact()).isTrue();
        assertThat(ok.signedHeadStillInChain()).isTrue();

        // audit() tambien, con el algoritmo correcto en el verdict
        var audit = checkpoints.audit();
        assertThat(audit.tamperDetected()).isFalse();
        assertThat(audit.signatureValid()).isTrue();
        assertThat(audit.signatureAlgorithm()).isEqualTo("Ed25519");

        // tamper: la firma Ed25519 SIGUE valida (firma la cabeza original); el SHA-256 delata el contenido
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", t1.getId());
        var afterTamper = checkpoints.verifyLatest();
        assertThat(afterTamper.signatureValid()).isTrue();
        assertThat(afterTamper.chainIntact()).isFalse();
    }

    @Test
    void un_checkpoint_de_un_algoritmo_DESCONOCIDO_falla_ruidoso_y_NO_como_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();

        // Simula un checkpoint firmado con un esquema que este despliegue NO tiene desplegado (p.ej.
        // se retiro el verificador). No poder verificar NO es evidencia de tamper -> debe fallar RUIDOSO,
        // no devolver un falso "MANIPULACION DETECTADA".
        jdbc.update("UPDATE journal_checkpoint SET algorithm = ? "
                + "WHERE chain_seq = (SELECT max(chain_seq) FROM journal_checkpoint)", "RSA-PSS-4096");

        assertThatThrownBy(() -> checkpoints.audit())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> checkpoints.verifyLatest())
                .isInstanceOf(IllegalStateException.class);
    }
}
