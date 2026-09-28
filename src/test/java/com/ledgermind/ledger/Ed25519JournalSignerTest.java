package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * RUNTIME test of the second signer (Ed25519/EdDSA via BouncyCastle): that it really signs and
 * verifies (compiling only proves the symbols exist). It needs neither Spring nor Postgres. It is the scheme
 * that shows crypto-agility supports >1 algorithm, not just ML-DSA.
 */
class Ed25519JournalSignerTest {

    private final Ed25519JournalSigner signer = new Ed25519JournalSigner();

    @Test
    void firma_y_verifica_un_roundtrip() {
        byte[] data = "ledgermind:journal-checkpoint:v1:3:abc123".getBytes(StandardCharsets.UTF_8);
        String sig = signer.sign(data);

        assertThat(signer.algorithm()).isEqualTo("Ed25519");
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
        Ed25519JournalSigner other = new Ed25519JournalSigner();

        assertThat(signer.verify(data, sig, other.publicKeyBase64())).isFalse();
    }
}
