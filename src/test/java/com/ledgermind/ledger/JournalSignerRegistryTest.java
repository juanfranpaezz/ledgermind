package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the HEART of crypto-agility: the by-algorithm dispatch of {@link JournalSignerRegistry}.
 * No Spring or Postgres — it instantiates the real signers (BouncyCastle really runs) and builds the
 * registry by hand. It covers: (1) correctly verifying signatures of >=2 schemes, (2) detecting tamper in each
 * scheme, (3) rejecting an unknown algorithm as a structural failure (not as tamper).
 */
class JournalSignerRegistryTest {

    private final MlDsaJournalSigner mldsa = new MlDsaJournalSigner();
    private final Ed25519JournalSigner ed = new Ed25519JournalSigner();
    private final JournalSignerRegistry registry = new JournalSignerRegistry(List.of(mldsa, ed));

    private static byte[] msg(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void registra_ambos_esquemas() {
        assertThat(registry.supportedAlgorithms()).containsExactly("Ed25519", "ML-DSA-65");
        assertThat(registry.supports("ML-DSA-65")).isTrue();
        assertThat(registry.supports("Ed25519")).isTrue();
        assertThat(registry.supports("RSA")).isFalse();
    }

    @Test
    void despacha_y_verifica_una_firma_ML_DSA() {
        byte[] data = msg("ledgermind:journal-checkpoint:v1:7:deadbeef");
        String sig = mldsa.sign(data);

        // dispatch by NAME -> it uses the ML-DSA verifier even though the registry also has Ed25519
        assertThat(registry.verify("ML-DSA-65", data, sig, mldsa.publicKeyBase64())).isTrue();
    }

    @Test
    void despacha_y_verifica_una_firma_Ed25519() {
        byte[] data = msg("ledgermind:journal-checkpoint:v1:7:deadbeef");
        String sig = ed.sign(data);

        assertThat(registry.verify("Ed25519", data, sig, ed.publicKeyBase64())).isTrue();
    }

    @Test
    void no_cruza_esquemas_una_firma_Ed25519_NO_verifica_como_ML_DSA() {
        // An Ed25519 signature presented as ML-DSA: the Ed25519 public key is not an X.509 that the
        // ML-DSA KeyFactory can parse -> STRUCTURAL failure (not evidence of tamper) -> loud.
        byte[] data = msg("head:abc123");
        String edSig = ed.sign(data);

        assertThatThrownBy(() -> registry.verify("ML-DSA-65", data, edSig, ed.publicKeyBase64()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void detecta_tamper_en_ML_DSA() {
        byte[] data = msg("head:abc123");
        String sig = mldsa.sign(data);
        byte[] tampered = msg("head:abc124");

        assertThat(registry.verify("ML-DSA-65", tampered, sig, mldsa.publicKeyBase64())).isFalse();
    }

    @Test
    void detecta_tamper_en_Ed25519() {
        byte[] data = msg("head:abc123");
        String sig = ed.sign(data);
        byte[] tampered = msg("head:abc124");

        assertThat(registry.verify("Ed25519", tampered, sig, ed.publicKeyBase64())).isFalse();
    }

    @Test
    void rechaza_un_algoritmo_desconocido_como_falla_estructural_NO_como_tamper() {
        byte[] data = msg("head:abc123");
        String sig = mldsa.sign(data);

        // An unregistered algorithm CANNOT be verified: that is NOT tamper, it means the verifier is not
        // deployed. It must fail LOUDLY (IllegalStateException), NOT return false (a false "tamper").
        assertThatThrownBy(() -> registry.verify("RSA-PSS", data, sig, mldsa.publicKeyBase64()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    void el_firmante_activo_se_resuelve_por_nombre() {
        assertThat(registry.activeSigner("ML-DSA-65")).isSameAs(mldsa);
        assertThat(registry.activeSigner("Ed25519")).isSameAs(ed);
        assertThatThrownBy(() -> registry.activeSigner("RSA"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dos_firmantes_con_el_mismo_algoritmo_es_un_error_de_configuracion() {
        // Defence: if two beans declared the same algorithm(), one is not picked silently -> it fails.
        assertThatThrownBy(() -> new JournalSignerRegistry(List.of(mldsa, new MlDsaJournalSigner())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same algorithm");
    }
}
