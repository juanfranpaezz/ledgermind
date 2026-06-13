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
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Firmante ML-DSA-65 (FIPS 204, post-cuantico) via BouncyCastle.
 *
 * <p>La clave es EFIMERA (se genera al arranque) -> es una DEMOSTRACION de capacidad, no compliance.
 * En produccion la clave privada vive en un HSM/KMS y nunca toca el proceso, y la clave PUBLICA se
 * ancla FUERA de la DB (pinneada/log de transparencia). La clave publica viaja con cada checkpoint por
 * conveniencia de verificacion, pero por si sola da integridad-de-mensaje, NO autenticidad del firmante:
 * quien pueda reescribir la fila puede sustituir (clave, firma) por un par propio. El ancla externa es
 * lo que convierte la firma en no-repudio operativo.
 *
 * <p>Amenaza que mitiga (con ese ancla): "forge-later" (un adversario con computadora cuantica futura
 * forjando una firma sobre un journal reescrito). NO es "harvest-now-decrypt-later": esto firma, no cifra.
 */
@Component
public class MlDsaJournalSigner implements JournalSigner {

    private static final Logger log = LoggerFactory.getLogger(MlDsaJournalSigner.class);

    public static final String ALGORITHM = "ML-DSA-65";

    private final KeyPair keyPair;

    public MlDsaJournalSigner() {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("ML-DSA", "BC");
            generator.initialize(MLDSAParameterSpec.ml_dsa_65);
            this.keyPair = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo inicializar el firmante ML-DSA", e);
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
            Signature signature = Signature.getInstance("ML-DSA", "BC");
            signature.initSign(keyPair.getPrivate());
            signature.update(data);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Error firmando con ML-DSA", e);
        }
    }

    /**
     * Verifica la firma. CLAVE: distingue dos estados que NO son lo mismo y conflacionarlos borra informacion
     * de seguridad:
     * <ul>
     *   <li><b>La firma no cierra</b> ({@link SignatureException}, o {@code verify} devuelve false): esto SI es
     *       evidencia de manipulacion -> {@code false}. Es el unico caso que justifica un {@code false}.</li>
     *   <li><b>No se pudo verificar</b> por causa estructural/ambiental (Base64 corrupto, clave X.509 invalida,
     *       provider 'BC' ausente): NO es evidencia criptografica de tamper. Antes un {@code catch(Exception)}
     *       lo disfrazaba de "firma invalida" -> {@code audit()} gritaba un falso "MANIPULACION DETECTADA".
     *       Ahora falla RUIDOSO ({@link IllegalStateException}) en vez de mentir un veredicto de seguridad.</li>
     * </ul>
     */
    @Override
    public boolean verify(byte[] data, String signatureBase64, String publicKeyBase64) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("ML-DSA", "BC");
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature signature = Signature.getInstance("ML-DSA", "BC");
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (SignatureException badSignature) {
            // La firma no verifica bajo la clave provista: tamper genuino. Unico caso que da false.
            log.warn("Firma ML-DSA invalida: la firma no cierra bajo la clave provista", badSignature);
            return false;
        } catch (GeneralSecurityException | IllegalArgumentException structural) {
            // Base64 corrupto / clave X.509 invalida / provider ausente: NO pudimos verificar. No lo
            // disfrazamos de tamper -> fallamos ruidoso para no emitir un veredicto de seguridad falso.
            throw new IllegalStateException(
                    "No se pudo verificar la firma ML-DSA (causa estructural, no evidencia de tamper)", structural);
        }
    }
}
