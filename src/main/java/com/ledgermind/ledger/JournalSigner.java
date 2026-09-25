package com.ledgermind.ledger;

/**
 * Firma crypto-AGIL del journal. Hay dos implementaciones: ML-DSA-65 (FIPS 204, post-cuantica; el
 * firmante ACTIVO por default) y {@link Ed25519JournalSigner} (clasica; el segundo esquema que prueba
 * la rotacion). La interfaz permite rotar de esquema o montar un hibrido sin tocar el dominio. El
 * entregable real es la CRYPTO-AGILITY: un ENABLER del plan de cambio de cripto que facilita PCI DSS 4.0
 * 12.3.3 (inventario y plan ante deprecaciones) y, a nivel marco, la gestion de riesgo ICT (DORA UE
 * 2022/2554, arts. 5-15). NO es "una firma cuantica" ni compliance certificado.
 *
 * <p>Agility COMPLETA (firma + verificacion):
 * <ul>
 *   <li><b>Firma:</b> el esquema activo se elige por configuracion ({@code ledgermind.journal.signer.algorithm},
 *       default {@code ML-DSA-65}); cada checkpoint persiste su {@code algorithm} y su clave publica.</li>
 *   <li><b>Verificacion:</b> el {@link JournalSignerRegistry} DESPACHA por el {@code algorithm} que el
 *       checkpoint registro -> un checkpoint firmado con un esquema se verifica con ESE esquema, aunque
 *       el firmante activo ya haya rotado a otro. Soporta >1 algoritmo en paralelo. Un algoritmo no
 *       registrado falla RUIDOSO (no se disfraza de tamper).</li>
 * </ul>
 *
 * <p>{@code verify} recibe la clave publica EXPLICITA. Eso prueba INTEGRIDAD-DE-MENSAJE (la firma cierra
 * contra la clave que la acompaña), NO autenticidad del firmante: sin un trust anchor externo (clave
 * pinneada en config, HSM/KMS, o un log de transparencia) NO prueba <i>quien</i> firmo. Completar la
 * agility NO cambia esto: el limite que queda es de GESTION DE CLAVES (anclar la clave publica fuera de
 * la DB), no de agility de algoritmo. Ver {@code JournalCheckpointService.verifyLatest}.
 */
public interface JournalSigner {

    /** Nombre del algoritmo de firma en uso (se persiste en cada checkpoint). */
    String algorithm();

    /** Clave PUBLICA (base64, X.509) del firmante actual; se persiste junto a cada firma. */
    String publicKeyBase64();

    /** Firma los datos y devuelve la firma en base64. */
    String sign(byte[] data);

    /** Verifica una firma base64 contra los datos, usando la clave publica (base64) provista. */
    boolean verify(byte[] data, String signatureBase64, String publicKeyBase64);
}
