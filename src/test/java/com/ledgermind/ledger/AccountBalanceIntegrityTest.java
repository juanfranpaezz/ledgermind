package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.AccountBalanceVerifier.AccountBalanceMismatch;
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
 * Integrity of the balance counters against the journal.
 *
 * <p>The {@code posted_debits} / {@code posted_credits} counters are advanced with {@code +=} when the
 * posting is written and are NEVER recomputed: if a posting's amount is edited afterwards, the counter
 * keeps the old arithmetic and the journal the new one. These tests prove that the new check gives BOTH
 * outcomes on a realistic case: GREEN on an intact ledger and RED on one altered by direct
 * SQL (the same vector the demo uses: {@code UPDATE posting SET amount = amount + 1}).
 *
 * <p>The scheduled jobs are disabled (huge delay) so that the test is deterministic.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@Testcontainers
class AccountBalanceIntegrityTest {

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
    private AccountBalanceVerifier balances;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
    }

    /** The exact demo scenario: 3 accounts, 5 transfers (ORD-1001..1005), chained and signed. */
    private void seedDemoLedger() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:ana", "ARS", false);
        ledger.createAccount("wallet:beto", "ARS", false);
        ledger.transfer("external:funding", "wallet:ana", 100_000, "ORD-1001");
        ledger.transfer("external:funding", "wallet:ana", 50_000, "ORD-1002");
        ledger.transfer("wallet:ana", "wallet:beto", 30_000, "ORD-1003");
        ledger.transfer("wallet:ana", "wallet:beto", 12_500, "ORD-1004");
        ledger.transfer("external:funding", "wallet:beto", 8_000, "ORD-1005");
        chainer.chainPendingPostings();
        checkpoints.checkpointIfHeadAdvanced();
    }

    // ---------- VERDE: ledger intacto ----------

    @Test
    void intact_ledger_counters_balance_against_the_journal_replay() {
        seedDemoLedger();

        var result = balances.verify();
        System.out.println("[BALANCE-CHECK][GREEN] " + result);

        assertThat(result.consistent()).isTrue();
        assertThat(result.mismatches()).isEmpty();
        assertThat(result.accountsChecked()).isEqualTo(3);
        assertThat(result.postingsReplayed()).isEqualTo(5);

        // and the consolidated audit (what the API, the MCP tool and the demo read) reflects it
        var audit = checkpoints.audit();
        assertThat(audit.balancesConsistent()).isTrue();
        assertThat(audit.balanceMismatches()).isEmpty();
        assertThat(audit.tamperDetected()).isFalse();
        assertThat(audit.verdict()).contains("NO EVIDENCE OF EDITING");
    }

    // ---------- RED: a posting edited by direct SQL ----------

    @Test
    void a_posting_edited_by_SQL_leaves_the_counter_disagreeing_with_the_journal() {
        seedDemoLedger();

        // Attacker with database access: adds 1 cent to the last posting (ORD-1005, 8,000 -> 8,001).
        // The counters of the two accounts involved are NOT touched: they keep the old arithmetic.
        Long lastId = jdbc.queryForObject("SELECT max(id) FROM posting", Long.class);
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", lastId);

        var result = balances.verify();
        result.mismatches().forEach(m -> System.out.println("[BALANCE-CHECK][RED] " + m.describe()));

        assertThat(result.consistent()).isFalse();
        assertThat(result.mismatches()).hasSize(2);

        AccountBalanceMismatch funding = mismatchOf(result, "external:funding");
        assertThat(funding.storedPostedDebits()).isEqualTo(158_000);
        assertThat(funding.journalPostedDebits()).isEqualTo(158_001);
        assertThat(funding.postedDebitsDifference()).isEqualTo(-1);
        assertThat(funding.describe()).contains("158000").contains("158001");

        AccountBalanceMismatch beto = mismatchOf(result, "wallet:beto");
        assertThat(beto.storedPostedCredits()).isEqualTo(50_500);
        assertThat(beto.journalPostedCredits()).isEqualTo(50_501);
        assertThat(beto.postedCreditsDifference()).isEqualTo(-1);
        assertThat(beto.describe()).contains("50500").contains("50501");

        // The number the overdraft gate uses today is still the old one: 50500, not 50501.
        // (This assertion DOCUMENTS the exposure; this change does not re-wire availableBalance.)
        assertThat(ledger.getByAddress("wallet:beto").availableBalance()).isEqualTo(50_500);

        var audit = checkpoints.audit();
        assertThat(audit.balancesConsistent()).isFalse();
        assertThat(audit.balanceMismatches()).hasSize(2);
        assertThat(audit.tamperDetected()).isTrue();
        assertThat(audit.verdict()).contains("the balance counters do NOT balance against the journal");
        assertThat(audit.verdict()).contains("158000").contains("158001");
        System.out.println("[BALANCE-CHECK][RED][verdict] " + audit.verdict());
    }

    /**
     * The new check is NOT redundant with the hash-chain: if the altered posting was not chained
     * yet, {@code chainIntact} stays true and the signature verifies, and without this check the audit
     * would say "no evidence of editing" with the counters already out of step.
     */
    @Test
    void detects_tamper_of_a_posting_NOT_yet_chained_that_the_hash_chain_cannot_see() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:beto", "ARS", false);
        ledger.transfer("external:funding", "wallet:beto", 100_000, "seed");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();

        // a new posting that has NOT gone through the chainer yet (normal async window)
        Posting notYetChained = ledger.transfer("external:funding", "wallet:beto", 5_000, "not-yet-chained");
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", notYetChained.getId());

        var audit = checkpoints.audit();
        assertThat(audit.chainIntact()).isTrue();              // the chain does not see that posting: still clean
        assertThat(audit.signatureValid()).isTrue();           // the signature was not touched either
        assertThat(audit.signedHeadStillInChain()).isTrue();
        assertThat(audit.balancesConsistent()).isFalse();      // but the counter no longer balances
        assertThat(audit.tamperDetected()).isTrue();
        assertThat(audit.verdict()).contains("TAMPER DETECTED");
        System.out.println("[BALANCE-CHECK][RED][not-yet-chained][verdict] " + audit.verdict());
    }

    private static AccountBalanceMismatch mismatchOf(AccountBalanceVerifier.BalanceVerifyResult r, String address) {
        return r.mismatches().stream()
                .filter(m -> m.address().equals(address))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no mismatch reported for " + address));
    }
}
