package com.ledgermind.ledger;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registry of journal signature algorithms: the VERIFICATION side of crypto-agility.
 *
 * <p>Why it exists: a checkpoint stores the {@code algorithm} it was signed with. To verify a
 * checkpoint you have to use THE SAME algorithm, not the one the active signer uses TODAY. Without this registry the
 * service could only verify checkpoints signed with the single injected signer: rotating from ML-DSA to
 * Ed25519 left the old checkpoints impossible to verify (the wrong KeyFactory threw a structural
 * {@link IllegalStateException}). This completes the agility "backwards": every checkpoint is
 * verified with its own scheme, supporting >1 algorithm in parallel.
 *
 * <p>VERIFICATION needs no private key: the algorithm, the public key and the signature travel in the
 * checkpoint itself. That is why the registry indexes {@link JournalSigner} by {@link JournalSigner#algorithm()}
 * and when verifying ONLY uses its {@code verify} method — a signer without private material (mounted only to
 * verify a retired scheme) would fit just as well if it implemented the interface.
 *
 * <p>UNKNOWN algorithm (not registered): it is NOT evidence of tamper. It is a structural failure —
 * the verifier for that scheme is not deployed — and it must fail LOUDLY ({@link IllegalStateException}),
 * consistent with the discipline of {@code MlDsaJournalSigner.verify} (do not disguise an environmental cause as
 * "TAMPER DETECTED"). A false security verdict is worse than a visible error.
 */
@Component
public class JournalSignerRegistry {

    private final Map<String, JournalSigner> byAlgorithm;

    /**
     * Spring injects ALL the {@link JournalSigner} beans in the context. They are indexed by their
     * {@link JournalSigner#algorithm()}; two signers with the same algorithm name is a
     * configuration error and fails at startup (one is not picked silently).
     */
    public JournalSignerRegistry(List<JournalSigner> signers) {
        this.byAlgorithm = signers.stream().collect(Collectors.toMap(
                JournalSigner::algorithm,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                            "Two JournalSigner beans declare the same algorithm() = '" + a.algorithm()
                                    + "': " + a.getClass().getName() + " and " + b.getClass().getName());
                }));
    }

    /**
     * Verifies a signature by dispatching on the algorithm NAME the checkpoint recorded.
     *
     * @param algorithm        name of the algorithm it was signed with (a checkpoint field)
     * @param data             canonical bytes that were signed
     * @param signatureBase64  signature in base64
     * @param publicKeyBase64  public key (base64, X.509) that accompanies the checkpoint
     * @return {@code true} if the signature checks out; {@code false} ONLY if the signature does not verify (genuine tamper)
     * @throws IllegalStateException if the algorithm is not registered (structural failure, NOT tamper)
     */
    public boolean verify(String algorithm, byte[] data, String signatureBase64, String publicKeyBase64) {
        JournalSigner verifier = byAlgorithm.get(algorithm);
        if (verifier == null) {
            // Unsupported algorithm: we CANNOT verify. It is NOT cryptographic evidence of tamper ->
            // we fail loudly instead of returning a false security verdict.
            throw new IllegalStateException("Unknown/unregistered signature algorithm: '" + algorithm
                    + "'. Supported algorithms: " + supportedAlgorithms()
                    + ". (The checkpoint could not be verified; this is NOT evidence of tampering.)");
        }
        return verifier.verify(data, signatureBase64, publicKeyBase64);
    }

    /** Algorithms this deployment can verify (for diagnostics/observability). */
    public List<String> supportedAlgorithms() {
        return byAlgorithm.keySet().stream().sorted().toList();
    }

    /** {@code true} if the deployment has a verifier for that algorithm. */
    public boolean supports(String algorithm) {
        return byAlgorithm.containsKey(algorithm);
    }

    /**
     * The ACTIVE signer (the one that signs NEW checkpoints), resolved by algorithm name.
     * It is the SIGNING side of the agility: rotating schemes = changing this name by configuration,
     * without touching the domain. Verification keeps working for ALL registered schemes, so
     * checkpoints signed with the previous scheme are still audited after the rotation.
     *
     * @throws IllegalStateException if the configured active algorithm has no registered signer
     *                               (fails LOUDLY on the first attempt to sign — the first checkpoint
     *                               tick — it does not silently sign with the wrong scheme)
     */
    public JournalSigner activeSigner(String algorithm) {
        JournalSigner signer = byAlgorithm.get(algorithm);
        if (signer == null) {
            throw new IllegalStateException("Unsupported ACTIVE signature algorithm: '" + algorithm
                    + "'. Available algorithms: " + supportedAlgorithms()
                    + ". Check the ledgermind.journal.signer.algorithm property.");
        }
        return signer;
    }
}
