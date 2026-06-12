package com.ledgermind.ledger;

/**
 * Firma crypto-AGIL del journal. La implementacion v1 es ML-DSA (FIPS 204, post-cuantica); la interfaz
 * permite rotar a Ed25519 o a un hibrido sin tocar el dominio. El entregable real es la CRYPTO-AGILITY:
 * un ENABLER del plan de cambio de cripto que facilita PCI DSS 4.0 12.3.3 (inventario y plan ante
 * deprecaciones) y, a nivel marco, la gestion de riesgo ICT (DORA UE 2022/2554, arts. 5-15). NO es
 * "una firma cuantica" ni compliance certificado.
 *
 * <p>Honestidad de alcance: hoy el servicio inyecta UN solo firmante cableado a ML-DSA. La agility es
 * hacia ADELANTE (cada checkpoint guarda su algoritmo y clave, asi un checkpoint viejo se sigue
 * verificando con su algoritmo); falta el dispatch por {@code algorithm} para verificar OTRO esquema.
 *
 * <p>{@code verify} recibe la clave publica EXPLICITA. Eso prueba INTEGRIDAD-DE-MENSAJE (la firma cierra
 * contra la clave que la acompaña), NO autenticidad del firmante: sin un trust anchor externo (clave
 * pinneada en config, HSM/KMS, o un log de transparencia) NO prueba <i>quien</i> firmo.
 */
public interface JournalSigner {

    /** Nombre del algoritmo de firma en uso (para el checkpoint y la entrevista). */
    String algorithm();

    /** Clave PUBLICA (base64, X.509) del firmante actual; se persiste junto a cada firma. */
    String publicKeyBase64();

    /** Firma los datos y devuelve la firma en base64. */
    String sign(byte[] data);

    /** Verifica una firma base64 contra los datos, usando la clave publica (base64) provista. */
    boolean verify(byte[] data, String signatureBase64, String publicKeyBase64);
}
