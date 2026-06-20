package com.ledgermind.ledger;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Firmante Ed25519 (EdDSA, RFC 8032) via BouncyCastle — el SEGUNDO esquema, para demostrar que la
 * crypto-agility es REAL y no solo declarada: el journal puede firmarse y verificarse con >1 algoritmo,
 * y un checkpoint viejo se sigue verificando con SU esquema aunque el firmante activo haya rotado.
 *
 * <p>Ed25519 es una firma CLASICA (no post-cuantica): un adversario con computadora cuantica la rompe.
 * Su rol aca NO es seguridad post-cuantica — es ser el "otro" algoritmo que prueba el dispatch por
 * nombre del {@link JournalSignerRegistry}. En produccion serviria como el carril de rotacion ante una
 * deprecacion de ML-DSA, o como pata clasica de un esquema HIBRIDO (clasico + PQC).
 *
 * <p>Misma honestidad de alcance que {@link MlDsaJournalSigner}: clave EFIMERA al arranque -> demostracion,
 * no compliance; {@code verify} con clave explicita prueba integridad-de-mensaje, NO autenticidad del
 * firmante sin un trust anchor externo.
 *
 * <p>Por defecto NO es el firmante ACTIVO (lo elige {@code ledgermind.journal.signer.algorithm};
 * default ML-DSA-65). Pero SIEMPRE esta registrado para VERIFICAR: asi un checkpoint firmado con Ed25519
 * — en este o en otro despliegue — se puede auditar siempre.
 */
@Component
public class Ed25519JournalSigner implements JournalSigner {

    private static final Logger log = LoggerFactory.getLogger(Ed25519JournalSigner.class);

    public static final String ALGORITHM = "Ed25519";

    private final KeyPair keyPair;

    public Ed25519JournalSigner() {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519", "BC");
            this.keyPair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo inicializar el firmante Ed25519", e);
        }
    }

    @Override
    public String algorithm() {
        return ALGORITHM;
    }

    @Override
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @Override
    public String sign(byte[] data) {
        try {
            Signature signature = Signature.getInstance("Ed25519", "BC");
            signature.initSign(keyPair.getPrivate());
            signature.update(data);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Error firmando con Ed25519", e);
        }
    }

    /**
     * Verifica la firma con la MISMA disciplina estructural-vs-tamper que {@link MlDsaJournalSigner#verify}:
     * la firma que no cierra es la UNICA causa de {@code false} (tamper genuino); una falla ambiental
     * (Base64 corrupto, clave X.509 invalida, provider ausente) NO se disfraza de tamper -> falla ruidoso.
     */
    @Override
    public boolean verify(byte[] data, String signatureBase64, String publicKeyBase64) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519", "BC");
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature signature = Signature.getInstance("Ed25519", "BC");
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (SignatureException badSignature) {
            log.warn("Firma Ed25519 invalida: la firma no cierra bajo la clave provista", badSignature);
            return false;
        } catch (GeneralSecurityException | IllegalArgumentException structural) {
            throw new IllegalStateException(
                    "No se pudo verificar la firma Ed25519 (causa estructural, no evidencia de tamper)", structural);
        }
    }
}
