package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Prueba de RUNTIME del firmante post-cuantico: que BouncyCastle realmente firme y verifique ML-DSA
 * (compilar solo prueba que los simbolos existen, no que el algoritmo corra). No necesita Spring ni Postgres.
 */
class MlDsaJournalSignerTest {

    private final MlDsaJournalSigner signer = new MlDsaJournalSigner();

    @Test
    void firma_y_verifica_un_roundtrip() {
        byte[] data = "ledgermind:journal-checkpoint:v1:3:abc123".getBytes(StandardCharsets.UTF_8);
        String sig = signer.sign(data);

        assertThat(signer.algorithm()).isEqualTo("ML-DSA-65");
        assertThat(signer.verify(data, sig, signer.publicKeyBase64())).isTrue();
    }

    @Test
    void rechaza_datos_alterados() {
        byte[] data = "head:abc123".getBytes(StandardCharsets.UTF_8);
        String sig = signer.sign(data);
        byte[] tampered = "head:abc124".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.verify(tampered, sig, signer.publicKeyBase64())).isFalse();
    }

    @Test
    void rechaza_una_clave_publica_ajena() {
        byte[] data = "head:abc123".getBytes(StandardCharsets.UTF_8);
        String sig = signer.sign(data);
        MlDsaJournalSigner otro = new MlDsaJournalSigner();

        assertThat(signer.verify(data, sig, otro.publicKeyBase64())).isFalse();
    }
}
