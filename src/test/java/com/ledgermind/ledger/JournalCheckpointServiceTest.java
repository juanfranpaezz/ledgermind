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
 * Signed checkpoint (Signed Tree Head): it signs the head of the hash-chain with ML-DSA and proves that,
 * after a tamper, the signature is still valid but the chain NO longer recomputes -> cryptographic
 * (post-quantum) proof that the journal was altered after signing.
 * The scheduled jobs are disabled (huge delay) so that the test is deterministic.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@Testcontainers
class JournalCheckpointServiceTest {

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

    /** Every test starts with a clean journal (the Postgres container is shared between tests). */
    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
    }

    @Test
    void signs_the_head_and_detects_a_later_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        Posting t1 = ledger.transfer("wallet:a", "wallet:b", 30_000, "t-1");

        // --- chain and sign the head ---
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();

        // --- idempotent: the head did not change -> no re-signing ---
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isEmpty();

        // --- clean verification: every plane green ---
        var ok = checkpoints.verifyLatest();
        assertThat(ok.present()).isTrue();
        assertThat(ok.algorithm()).isEqualTo("ML-DSA-65");
        assertThat(ok.signatureValid()).isTrue();
        assertThat(ok.chainIntact()).isTrue();
        assertThat(ok.signedHeadStillInChain()).isTrue();
        assertThat(ok.isLatestHead()).isTrue();

        // --- direct TAMPER in the DB on an already-chained posting ---
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", t1.getId());

        var afterTamper = checkpoints.verifyLatest();
        // the signature is STILL cryptographically valid (it signs the original head)...
        assertThat(afterTamper.signatureValid()).isTrue();
        // ...and what EXPOSES the content tamper is the recomputed SHA-256, not the signature:
        assertThat(afterTamper.chainIntact()).isFalse();
        // the signed link and the live head did NOT change (the tamper was in posting, not in posting_hash):
        assertThat(afterTamper.signedHeadStillInChain()).isTrue();
        assertThat(afterTamper.isLatestHead()).isTrue();
    }

    @Test
    void rewriting_ONLY_the_algorithm_column_invalidates_the_signature() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();

        // baseline: valid signature with the correct algorithm.
        assertThat(checkpoints.verifyLatest().signatureValid()).isTrue();

        // A DB writer rewrites ONLY the `algorithm` column (signature and key INTACT) to slip in a
        // FALSE scheme. The algorithm is trust metadata: it has to be INSIDE the verification
        // loop, not left as a label. Without that check, signatureValid would stay true and the verdict
        // would print "Ed25519 verified OK".
        jdbc.update("UPDATE journal_checkpoint SET algorithm = ? "
                + "WHERE chain_seq = (SELECT max(chain_seq) FROM journal_checkpoint)", "Ed25519");

        var tampered = checkpoints.verifyLatest();
        assertThat(tampered.signatureValid()).isFalse();   // the declared algorithm no longer matches the verifier
        assertThat(checkpoints.audit().tamperDetected()).isTrue();
    }

    @Test
    void a_lagging_checkpoint_is_NOT_a_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();

        // a NEW posting arrives and is chained, but we do NOT re-sign yet (normal async window)
        ledger.transfer("external:funding", "wallet:a", 5_000, "later");
        chainer.chainPendingPostings();

        // we verify the OLD checkpoint against the chain that has already advanced
        var v = checkpoints.verifyLatest();
        assertThat(v.signatureValid()).isTrue();
        assertThat(v.chainIntact()).isTrue();              // nothing was altered: the chain recomputes clean
        assertThat(v.signedHeadStillInChain()).isTrue();   // the signed link is still present and intact
        assertThat(v.isLatestHead()).isFalse();            // but it is NO longer the live head: NORMAL operation, not tamper
    }

    @Test
    void audit_reports_intact_and_then_detects_the_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        Posting t1 = ledger.transfer("external:funding", "wallet:a", 20_000, "t-1");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();

        var ok = checkpoints.audit();
        assertThat(ok.tamperDetected()).isFalse();
        assertThat(ok.chainIntact()).isTrue();
        assertThat(ok.checkpointPresent()).isTrue();
        assertThat(ok.signatureValid()).isTrue();
        assertThat(ok.signatureAlgorithm()).isEqualTo("ML-DSA-65");
        assertThat(ok.verdict()).contains("NO EVIDENCE OF EDITING");
        assertThat(ok.verdict()).contains("message integrity");   // the trust-anchor nuance reaches the LLM

        // tamper of an already-chained posting
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", t1.getId());

        var bad = checkpoints.audit();
        assertThat(bad.tamperDetected()).isTrue();
        assertThat(bad.chainIntact()).isFalse();
        assertThat(bad.brokenAtSeq()).isNotNull();
        assertThat(bad.verdict()).contains("TAMPER DETECTED");
    }

    @Test
    void detects_a_rewrite_of_the_hash_table() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        JournalCheckpoint cp = checkpoints.checkpointIfHeadAdvanced().orElseThrow();

        // an attacker rewrites the entry_hash of the signed link directly in posting_hash
        jdbc.update("UPDATE posting_hash SET entry_hash = ? WHERE seq = ?", "f".repeat(64), cp.getChainSeq());

        var v = checkpoints.verifyLatest();
        assertThat(v.signedHeadStillInChain()).isFalse();  // the signed link no longer matches the signature
        assertThat(v.chainIntact()).isFalse();             // and the chain does not recompute either
    }

    @Test
    void a_structurally_corrupt_signature_fails_loudly_and_NOT_as_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();

        // An actor with write access to the DB rewrites the checkpoint's public key with NON-Base64 garbage.
        // verify() cannot even decode it: it is NOT cryptographic evidence of tamper, it is a STRUCTURAL
        // failure. It must fail LOUDLY (IllegalStateException), not return a false "TAMPER
        // DETECTED" (which is what the old catch(Exception)->false did).
        jdbc.update("UPDATE journal_checkpoint SET public_key = ? "
                + "WHERE chain_seq = (SELECT max(chain_seq) FROM journal_checkpoint)", "not-valid-base64!!");

        assertThatThrownBy(() -> checkpoints.audit())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void an_empty_stored_public_key_fails_loud_as_structural_not_as_a_null_pointer() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();

        // public_key is NOT NULL, so '' is the degenerate value a DB writer can store. It is not an X.509 key, so it
        // must fail like any other key that does not parse: IllegalStateException (structural, not tamper), never a
        // NullPointerException out of the ASN.1 parser.
        jdbc.update("UPDATE journal_checkpoint SET public_key = ? "
                + "WHERE chain_seq = (SELECT max(chain_seq) FROM journal_checkpoint)", "");

        assertThatThrownBy(() -> checkpoints.verifyLatest())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> checkpoints.audit())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_checkpoint_signed_by_an_earlier_key_of_the_same_scheme_still_verifies() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        JournalCheckpoint cp = checkpoints.checkpointIfHeadAdvanced().orElseThrow();

        // The signing key is regenerated at every startup, so a checkpoint written before a restart carries a
        // DIFFERENT ML-DSA-65 key than today's signer. Simulate that previous boot: sign the same head with a fresh
        // key of the same scheme and store that (key, signature) pair. The declared-scheme check compares key
        // ALGORITHMS, not key bytes, so this must still verify; an exact stored-key comparison would turn every
        // pre-restart checkpoint into a false tamper.
        MlDsaJournalSigner previousBoot = new MlDsaJournalSigner();
        assertThat(previousBoot.publicKeyBase64()).isNotEqualTo(cp.getPublicKey());
        byte[] message = JournalCheckpointService.checkpointMessage(cp.getChainSeq(), cp.getHeadHash());
        jdbc.update("UPDATE journal_checkpoint SET public_key = ?, signature = ? WHERE chain_seq = ?",
                previousBoot.publicKeyBase64(), previousBoot.sign(message), cp.getChainSeq());

        assertThat(checkpoints.verifyLatest().signatureValid()).isTrue();
        assertThat(checkpoints.audit().tamperDetected()).isFalse();
    }

    @Test
    void with_no_checkpoint_chainIntact_on_a_fresh_db_matches_the_chain_verify() {
        // S104 (d1): with no checkpoint yet, chainIntact is still the recomputed chain, the same answer as
        // GET /api/journal/verify (chainer.verify()). A fresh database has an empty, intact chain.
        assertThat(chainer.verify().intact()).as("chain verify on a fresh db").isTrue();
        var v = checkpoints.verifyLatest();
        assertThat(v.present()).isFalse();
        assertThat(v.signatureValid()).isFalse();
        assertThat(v.chainIntact()).as("chainIntact with no checkpoint on a fresh db").isTrue();
    }

    /** Control for the test above: with no checkpoint and a tampered chained posting, chainIntact is false. */
    @Test
    void with_no_checkpoint_a_broken_chain_still_reports_chainIntact_false() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        Posting seed = ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        chainer.chainPendingPostings();
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", seed.getId());

        assertThat(chainer.verify().intact()).as("chain verify after the tamper").isFalse();
        var v = checkpoints.verifyLatest();
        assertThat(v.present()).isFalse();
        assertThat(v.chainIntact()).as("chainIntact with no checkpoint after the tamper").isFalse();
    }

    @Test
    void creates_a_new_checkpoint_when_the_head_advances() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:c", "ARS", false);
        ledger.transfer("external:funding", "wallet:c", 50_000, "seed-2");
        chainer.chainPendingPostings();
        var first = checkpoints.checkpointIfHeadAdvanced();
        assertThat(first).isPresent();

        // new posting -> the head advances -> new checkpoint with a higher seq
        ledger.transfer("external:funding", "wallet:c", 10_000, "t-extra");
        chainer.chainPendingPostings();
        var second = checkpoints.checkpointIfHeadAdvanced();
        assertThat(second).isPresent();
        assertThat(second.get().getChainSeq()).isGreaterThan(first.get().getChainSeq());
    }
}
