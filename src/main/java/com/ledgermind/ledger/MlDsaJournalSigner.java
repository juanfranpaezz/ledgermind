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
 * ML-DSA-65 signer (FIPS 204, post-quantum) via BouncyCastle.
 *
 * <p>The key is EPHEMERAL (generated at startup) -> it is a DEMONSTRATION of capability, not compliance.
 * In production the private key lives in an HSM/KMS and never touches the process, and the PUBLIC key is
 * anchored OUTSIDE the DB (pinned/transparency log). The public key travels with every checkpoint for
 * verification convenience, but on its own it gives message integrity, NOT the signer's authenticity:
 * whoever can rewrite the row can replace (key, signature) with a pair of their own. The external anchor is
 * what turns the signature into operational non-repudiation.
 *
 * <p>Threat it mitigates (with that anchor): "forge-later" (an adversary with a future quantum computer
 * forging a signature over a rewritten journal). It is NOT "harvest-now-decrypt-later": this signs, it does not encrypt.
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
            throw new IllegalStateException("Could not initialize the ML-DSA signer", e);
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
            throw new IllegalStateException("Error signing with ML-DSA", e);
        }
    }

    /**
     * Verifies the signature. KEY POINT: it tells apart two states that are NOT the same, and conflating them erases security
     * information:
     * <ul>
     *   <li><b>The signature does not check out</b> ({@link SignatureException}, or {@code verify} returns false): this IS
     *       evidence of tampering -> {@code false}. It is the only case that justifies a {@code false}.</li>
     *   <li><b>It could not be verified</b> for a structural/environmental reason (corrupt Base64, invalid X.509 key,
     *       'BC' provider missing): it is NOT cryptographic evidence of tamper. A {@code catch(Exception)} used to
     *       disguise it as an "invalid signature" -> {@code audit()} shouted a false "TAMPER DETECTED".
     *       Now it fails LOUDLY ({@link IllegalStateException}) instead of lying with a security verdict.</li>
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
            // The signature does not verify under the provided key: genuine tamper. The only case that gives false.
            log.warn("Invalid ML-DSA signature: the signature does not check out under the provided key", badSignature);
            return false;
        } catch (GeneralSecurityException | IllegalArgumentException structural) {
            // Corrupt Base64 / invalid X.509 key / missing provider: we COULD NOT verify. We do not
            // disguise it as tamper -> we fail loudly so as not to issue a false security verdict.
            throw new IllegalStateException(
                    "Could not verify the ML-DSA signature (structural cause, not tamper evidence)", structural);
        }
    }
}
