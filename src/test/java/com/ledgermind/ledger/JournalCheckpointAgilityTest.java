package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * END-TO-END crypto-agility at the service level: it runs with the ACTIVE signer set to Ed25519 (not the
 * default ML-DSA-65) and proves that the checkpoint is signed with Ed25519 and that VERIFICATION dispatches it
 * to Ed25519 (not to ML-DSA). This shows that the by-algorithm dispatch of
 * {@link JournalCheckpointService} is really wired, not only in the isolated registry. Same tamper
 * as the ML-DSA test: after editing a posting, the signature stays valid and what exposes it is SHA-256.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.journal.signer.algorithm=Ed25519"
})
@Testcontainers
class JournalCheckpointAgilityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalChainer chainer;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
    }

    @Test
    void signs_with_Ed25519_and_verification_dispatches_to_Ed25519() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        Posting t1 = ledger.transfer("wallet:a", "wallet:b", 30_000, "t-1");

        chainer.chainPendingPostings();
        var cp = checkpoints.checkpointIfHeadAdvanced();
        assertThat(cp).isPresent();
        // the checkpoint was signed with the ACTIVE scheme = Ed25519, NOT with the default ML-DSA-65
        assertThat(cp.get().getAlgorithm()).isEqualTo("Ed25519");

        // verification dispatches by that algorithm and comes out green
        var ok = checkpoints.verifyLatest();
        assertThat(ok.algorithm()).isEqualTo("Ed25519");
        assertThat(ok.signatureValid()).isTrue();
        assertThat(ok.chainIntact()).isTrue();
        assertThat(ok.signedHeadStillInChain()).isTrue();

        // audit() too, with the correct algorithm in the verdict
        var audit = checkpoints.audit();
        assertThat(audit.tamperDetected()).isFalse();
        assertThat(audit.signatureValid()).isTrue();
        assertThat(audit.signatureAlgorithm()).isEqualTo("Ed25519");

        // tamper: the Ed25519 signature is STILL valid (it signs the original head); SHA-256 exposes the content
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", t1.getId());
        var afterTamper = checkpoints.verifyLatest();
        assertThat(afterTamper.signatureValid()).isTrue();
        assertThat(afterTamper.chainIntact()).isFalse();
    }

    @Test
    void a_checkpoint_with_an_UNKNOWN_algorithm_fails_loudly_and_NOT_as_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();

        // Simulates a checkpoint signed with a scheme this deployment does NOT have deployed (e.g.
        // the verifier was retired). Not being able to verify is NOT evidence of tamper -> it must fail LOUDLY,
        // not return a false "TAMPER DETECTED".
        jdbc.update("UPDATE journal_checkpoint SET algorithm = ? "
                + "WHERE chain_seq = (SELECT max(chain_seq) FROM journal_checkpoint)", "RSA-PSS-4096");

        assertThatThrownBy(() -> checkpoints.audit())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> checkpoints.verifyLatest())
                .isInstanceOf(IllegalStateException.class);
    }
}
