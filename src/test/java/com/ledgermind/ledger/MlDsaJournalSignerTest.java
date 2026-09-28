package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * RUNTIME test of the post-quantum signer: that BouncyCastle really signs and verifies ML-DSA
 * (compiling only proves the symbols exist, not that the algorithm runs). It needs neither Spring nor Postgres.
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
        MlDsaJournalSigner other = new MlDsaJournalSigner();

        assertThat(signer.verify(data, sig, other.publicKeyBase64())).isFalse();
    }
}
