package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Prueba el CORAZON de la crypto-agility: el dispatch por algoritmo del {@link JournalSignerRegistry}.
 * Sin Spring ni Postgres — instancia los firmantes reales (BouncyCastle corre de verdad) y arma el
 * registro a mano. Cubre: (1) verificar bien firmas de >=2 esquemas, (2) detectar tamper en cada
 * esquema, (3) rechazar un algoritmo desconocido como falla estructural (no como tamper).
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

        // dispatch por NOMBRE -> usa el verificador ML-DSA aunque el registro tambien tenga Ed25519
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
        // Una firma Ed25519 presentada como ML-DSA: la clave publica Ed25519 no es un X.509 que el
        // KeyFactory de ML-DSA pueda parsear -> falla ESTRUCTURAL (no es evidencia de tamper) -> ruidoso.
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

        // Un algoritmo no registrado NO puede verificarse: eso NO es tamper, es que el verificador no
        // esta desplegado. Debe fallar RUIDOSO (IllegalStateException), NO devolver false (falso "tamper").
        assertThatThrownBy(() -> registry.verify("RSA-PSS", data, sig, mldsa.publicKeyBase64()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("desconocido");
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
        // Defensa: si dos beans declararan el mismo algorithm(), no se elige uno en silencio -> falla.
        assertThatThrownBy(() -> new JournalSignerRegistry(List.of(mldsa, new MlDsaJournalSigner())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mismo algorithm");
    }
}
