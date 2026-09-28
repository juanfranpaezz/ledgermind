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
 * Ed25519 signer (EdDSA, RFC 8032) via BouncyCastle — the SECOND scheme, to show that
 * crypto-agility is REAL and not just declared: the journal can be signed and verified with >1 algorithm,
 * and an old checkpoint is still verified with ITS scheme even after the active signer has rotated.
 *
 * <p>Ed25519 is a CLASSICAL signature (not post-quantum): an adversary with a quantum computer breaks it.
 * Its role here is NOT post-quantum security — it is to be the "other" algorithm that proves the by-name
 * dispatch of {@link JournalSignerRegistry}. In production it would serve as the rotation lane for a
 * deprecation of ML-DSA, or as the classical leg of a HYBRID scheme (classical + PQC).
 *
 * <p>Same honesty of scope as {@link MlDsaJournalSigner}: EPHEMERAL key at startup -> a demonstration,
 * not compliance; {@code verify} with an explicit key proves message integrity, NOT the signer's
 * authenticity without an external trust anchor.
 *
 * <p>By default it is NOT the ACTIVE signer (chosen by {@code ledgermind.journal.signer.algorithm};
 * default ML-DSA-65). But it is ALWAYS registered for VERIFICATION: that way a checkpoint signed with Ed25519
 * — in this or another deployment — can always be audited.
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
            throw new IllegalStateException("Could not initialize the Ed25519 signer", e);
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
            throw new IllegalStateException("Error signing with Ed25519", e);
        }
    }

    /**
     * Verifies the signature with the SAME structural-vs-tamper discipline as {@link MlDsaJournalSigner#verify}:
     * a signature that does not check out is the ONLY cause of {@code false} (genuine tamper); an environmental failure
     * (corrupt Base64, invalid X.509 key, missing provider) is NOT disguised as tamper -> it fails loudly.
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
            log.warn("Invalid Ed25519 signature: the signature does not check out under the provided key", badSignature);
            return false;
        } catch (GeneralSecurityException | IllegalArgumentException structural) {
            throw new IllegalStateException(
                    "Could not verify the Ed25519 signature (structural cause, not evidence of tamper)", structural);
        }
    }
}
