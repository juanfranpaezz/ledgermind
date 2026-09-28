package com.ledgermind.ledger;

/**
 * Crypto-AGILE signing of the journal. There are two implementations: ML-DSA-65 (FIPS 204, post-quantum; the
 * ACTIVE signer by default) and {@link Ed25519JournalSigner} (classical; the second scheme that proves
 * rotation). The interface allows rotating schemes or mounting a hybrid without touching the domain. The
 * real deliverable is CRYPTO-AGILITY: an ENABLER of the crypto change plan that supports PCI DSS 4.0
 * 12.3.3 (inventory and plan for deprecations) and, at the framework level, ICT risk management (EU DORA
 * 2022/2554, arts. 5-15). It is NOT "a quantum signature" nor certified compliance.
 *
 * <p>COMPLETE agility (signing + verification):
 * <ul>
 *   <li><b>Signing:</b> the active scheme is chosen by configuration ({@code ledgermind.journal.signer.algorithm},
 *       default {@code ML-DSA-65}); every checkpoint persists its {@code algorithm} and its public key.</li>
 *   <li><b>Verification:</b> the {@link JournalSignerRegistry} DISPATCHES by the {@code algorithm} the
 *       checkpoint recorded -> a checkpoint signed with one scheme is verified with THAT scheme, even if
 *       the active signer has already rotated to another. It supports >1 algorithm in parallel. An algorithm that is not
 *       registered fails LOUDLY (it is not disguised as tamper).</li>
 * </ul>
 *
 * <p>{@code verify} receives the EXPLICIT public key. That proves MESSAGE INTEGRITY (the signature checks out
 * against the key that accompanies it), NOT the signer's authenticity: without an external trust anchor (key
 * pinned in config, HSM/KMS, or a transparency log) it does NOT prove <i>who</i> signed. Completing the
 * agility does NOT change this: the remaining limit is one of KEY MANAGEMENT (anchoring the public key outside
 * the DB), not of algorithm agility. See {@code JournalCheckpointService.verifyLatest}.
 */
public interface JournalSigner {

    /** Name of the signature algorithm in use (persisted in every checkpoint). */
    String algorithm();

    /** PUBLIC key (base64, X.509) of the current signer; persisted alongside every signature. */
    String publicKeyBase64();

    /** Signs the data and returns the signature in base64. */
    String sign(byte[] data);

    /** Verifies a base64 signature against the data, using the provided (base64) public key. */
    boolean verify(byte[] data, String signatureBase64, String publicKeyBase64);
}
