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
 * Checkpoint firmado (Signed Tree Head): firma la cabeza de la hash-chain con ML-DSA y prueba que,
 * tras un tamper, la firma sigue siendo valida pero la cadena ya NO recomputa -> prueba criptografica
 * (post-cuantica) de que el journal fue alterado despues de firmar.
 * Se desactivan los jobs programados (delay enorme) para que el test sea determinista.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@Testcontainers
class JournalCheckpointServiceTest {

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

    /** Cada test arranca con un journal limpio (el contenedor Postgres se comparte entre tests). */
    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
    }

    @Test
    void firma_la_cabeza_y_detecta_un_tamper_posterior() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        Posting t1 = ledger.transfer("wallet:a", "wallet:b", 30_000, "t-1");

        // --- encadenar y firmar la cabeza ---
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();

        // --- idempotente: la cabeza no cambio -> no re-firma ---
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isEmpty();

        // --- verificacion limpia: todos los planos en verde ---
        var ok = checkpoints.verifyLatest();
        assertThat(ok.present()).isTrue();
        assertThat(ok.algorithm()).isEqualTo("ML-DSA-65");
        assertThat(ok.signatureValid()).isTrue();
        assertThat(ok.chainIntact()).isTrue();
        assertThat(ok.signedHeadStillInChain()).isTrue();
        assertThat(ok.isLatestHead()).isTrue();

        // --- TAMPER directo en la DB sobre un asiento ya encadenado ---
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", t1.getId());

        var afterTamper = checkpoints.verifyLatest();
        // la firma SIGUE siendo criptograficamente valida (firma la cabeza original)...
        assertThat(afterTamper.signatureValid()).isTrue();
        // ...y quien DELATA el tamper de contenido es el SHA-256 recomputado, no la firma:
        assertThat(afterTamper.chainIntact()).isFalse();
        // el eslabon firmado y la cabeza viva NO cambiaron (el tamper fue en posting, no en posting_hash):
        assertThat(afterTamper.signedHeadStillInChain()).isTrue();
        assertThat(afterTamper.isLatestHead()).isTrue();
    }

    @Test
    void un_checkpoint_atrasado_NO_es_un_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();

        // llega y se encadena un asiento NUEVO, pero todavia NO re-firmamos (ventana async normal)
        ledger.transfer("external:funding", "wallet:a", 5_000, "later");
        chainer.chainPendingPostings();

        // verificamos el checkpoint VIEJO contra la cadena que ya avanzo
        var v = checkpoints.verifyLatest();
        assertThat(v.signatureValid()).isTrue();
        assertThat(v.chainIntact()).isTrue();              // nada se altero: la cadena recomputa limpio
        assertThat(v.signedHeadStillInChain()).isTrue();   // el eslabon firmado sigue presente e intacto
        assertThat(v.isLatestHead()).isFalse();            // pero ya NO es la cabeza viva: operacion NORMAL, no tamper
    }

    @Test
    void audit_reporta_integro_y_luego_detecta_el_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        Posting t1 = ledger.transfer("external:funding", "wallet:a", 20_000, "t-1");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();

        var ok = checkpoints.audit();
        assertThat(ok.tamperDetected()).isFalse();
        assertThat(ok.chainIntact()).isTrue();
        assertThat(ok.checkpointPresent()).isTrue();
        assertThat(ok.signatureValid()).isTrue();
        assertThat(ok.signatureAlgorithm()).isEqualTo("ML-DSA-65");
        assertThat(ok.verdict()).contains("SIN EVIDENCIA DE EDICION");
        assertThat(ok.verdict()).contains("integridad-de-mensaje");   // el matiz del trust-anchor viaja al LLM

        // tamper de un asiento ya encadenado
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", t1.getId());

        var bad = checkpoints.audit();
        assertThat(bad.tamperDetected()).isTrue();
        assertThat(bad.chainIntact()).isFalse();
        assertThat(bad.brokenAtSeq()).isNotNull();
        assertThat(bad.verdict()).contains("MANIPULACION DETECTADA");
    }

    @Test
    void detecta_reescritura_de_la_tabla_de_hashes() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        JournalCheckpoint cp = checkpoints.checkpointIfHeadAdvanced().orElseThrow();

        // un atacante reescribe el entry_hash del eslabon firmado directamente en posting_hash
        jdbc.update("UPDATE posting_hash SET entry_hash = ? WHERE seq = ?", "f".repeat(64), cp.getChainSeq());

        var v = checkpoints.verifyLatest();
        assertThat(v.signedHeadStillInChain()).isFalse();  // el eslabon firmado ya no coincide con la firma
        assertThat(v.chainIntact()).isFalse();             // y la cadena tampoco recomputa
    }

    @Test
    void una_firma_estructuralmente_corrupta_falla_ruidoso_y_NO_como_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();

        // Un actor con escritura en la DB reescribe la clave publica del checkpoint con basura NO-Base64.
        // verify() no puede ni decodificarla: NO es evidencia criptografica de tamper, es una falla
        // ESTRUCTURAL. Debe fallar RUIDOSO (IllegalStateException), no devolver un falso "MANIPULACION
        // DETECTADA" (que es lo que hacia el viejo catch(Exception)->false).
        jdbc.update("UPDATE journal_checkpoint SET public_key = ? "
                + "WHERE chain_seq = (SELECT max(chain_seq) FROM journal_checkpoint)", "no-es-base64-valido!!");

        assertThatThrownBy(() -> checkpoints.audit())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void crea_un_nuevo_checkpoint_cuando_la_cabeza_avanza() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:c", "ARS", false);
        ledger.transfer("external:funding", "wallet:c", 50_000, "seed-2");
        chainer.chainPendingPostings();
        var first = checkpoints.checkpointIfHeadAdvanced();
        assertThat(first).isPresent();

        // nuevo asiento -> la cabeza avanza -> nuevo checkpoint con seq mayor
        ledger.transfer("external:funding", "wallet:c", 10_000, "t-extra");
        chainer.chainPendingPostings();
        var second = checkpoints.checkpointIfHeadAdvanced();
        assertThat(second).isPresent();
        assertThat(second.get().getChainSeq()).isGreaterThan(first.get().getChainSeq());
    }
}
