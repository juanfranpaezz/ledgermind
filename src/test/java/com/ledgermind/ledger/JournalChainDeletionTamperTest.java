package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Deleting a chained, checkpoint-covered MIDDLE posting is tamper, even when the attacker also deletes its hash row and
 * puts the two account counters back (gate r2, 2026-09-26: no artifact test pinned deletion; a verify() that recomputed
 * each link from its own stored prev_hash kept JournalChainerTest green).
 *
 * <p>What catches it is the chain linkage: the next link's stored prev_hash is the deleted link's hash, not the hash
 * of the link now before it. The signed head is untouched and the counters match the journal again, so neither the
 * signature nor the balance replay can be what fires here.
 *
 * <p>Both outcomes: before the deletion the audit reports no tamper; after it, tamper at the link that followed the
 * deleted one. Scheduled jobs are disabled (huge delays) so chaining and checkpointing are driven by the test.
 *
 * <p>Also pinned here, same fixture: an edit of a chained posting in the tail AFTER the last checkpoint is still
 * tamper when the editor does not recompute the links (README:23 and :213 wording, docs-truth gate r3).
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000",
        "ledgermind.overdraft.sweep-initial-delay-ms=3600000"
})
@Testcontainers
class JournalChainDeletionTamperTest {

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
    void deleting_a_checkpoint_covered_middle_posting_with_its_hash_row_and_counters_is_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        Posting middle = ledger.transfer("wallet:a", "wallet:b", 30_000, "t-1");
        Posting last = ledger.transfer("wallet:a", "wallet:b", 20_000, "t-2");

        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();   // the signed head covers all three links
        Long lastSeq = jdbc.queryForObject("SELECT seq FROM posting_hash WHERE posting_id = ?", Long.class,
                last.getId());

        // does not fire: clean journal, signed head present
        var clean = checkpoints.audit();
        assertThat(clean.tamperDetected()).isFalse();
        assertThat(clean.chainIntact()).isTrue();
        assertThat(clean.chainedCount()).isEqualTo(3);
        assertThat(clean.balancesConsistent()).isTrue();

        // TAMPER: remove the middle posting completely, as an attacker with direct DB access would
        jdbc.update("DELETE FROM posting_hash WHERE posting_id = ?", middle.getId());
        jdbc.update("DELETE FROM posting WHERE id = ?", middle.getId());
        jdbc.update("UPDATE account SET posted_debits = posted_debits - ? WHERE id = ?",
                middle.getAmount(), middle.getDebitAccountId());
        jdbc.update("UPDATE account SET posted_credits = posted_credits - ? WHERE id = ?",
                middle.getAmount(), middle.getCreditAccountId());

        // fires: only the chain linkage can see it
        var tampered = checkpoints.audit();
        assertThat(tampered.balancesConsistent()).as("counters were put back, so the balance replay is quiet").isTrue();
        assertThat(tampered.signedHeadStillInChain()).as("the signed head link was not touched").isTrue();
        assertThat(tampered.chainIntact()).isFalse();
        assertThat(tampered.brokenAtSeq()).isEqualTo(lastSeq);
        assertThat(tampered.tamperDetected()).isTrue();
        assertThat(tampered.verdict()).contains("MANIPULACION DETECTADA");
    }

    @Test
    void editing_a_chained_posting_after_the_last_checkpoint_without_relinking_is_tamper() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        ledger.transfer("wallet:a", "wallet:b", 30_000, "t-1");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();   // the signed head covers seq 1..2

        Posting tail = ledger.transfer("wallet:a", "wallet:b", 20_000, "t-2");
        chainer.chainPendingPostings();                                   // chained at seq 3, NOT checkpointed
        Long tailSeq = jdbc.queryForObject("SELECT seq FROM posting_hash WHERE posting_id = ?", Long.class,
                tail.getId());

        // does not fire: clean journal with a chained tail after the checkpoint
        var clean = checkpoints.audit();
        assertThat(clean.signedChainSeq()).isLessThan(tailSeq);
        assertThat(clean.tamperDetected()).isFalse();
        assertThat(clean.chainIntact()).isTrue();

        // TAMPER: edit the tail posting's amount and put both counters in line with it, but do NOT recompute its link
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", tail.getId());
        jdbc.update("UPDATE account SET posted_debits = posted_debits + 1 WHERE id = ?", tail.getDebitAccountId());
        jdbc.update("UPDATE account SET posted_credits = posted_credits + 1 WHERE id = ?", tail.getCreditAccountId());

        // fires: the stored entry_hash of the tail link no longer recomputes
        var tampered = checkpoints.audit();
        assertThat(tampered.balancesConsistent()).as("counters were adjusted, so the balance replay is quiet").isTrue();
        assertThat(tampered.chainIntact()).isFalse();
        assertThat(tampered.brokenAtSeq()).isEqualTo(tailSeq);
        assertThat(tampered.tamperDetected()).isTrue();
    }
}
