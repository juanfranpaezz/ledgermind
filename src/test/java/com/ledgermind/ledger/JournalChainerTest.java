package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Journal hash-chain: it chains the postings and DETECTS whether someone edited one that was already chained.
 * The scheduled job is disabled (huge delay) so that the test's chaining is deterministic.
 */
@SpringBootTest(properties = "ledgermind.journal.chain-delay-ms=3600000")
@Testcontainers
class JournalChainerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JournalChainer chainer;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void encadena_los_asientos_y_detecta_un_asiento_alterado() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed");
        Posting t1 = ledger.transfer("wallet:a", "wallet:b", 30_000, "t-1");
        ledger.transfer("wallet:a", "wallet:b", 20_000, "t-2");

        // --- chain and verify: chain intact ---
        chainer.chainPendingPostings();
        JournalChainer.VerifyResult ok = chainer.verify();
        assertThat(ok.intact()).isTrue();
        assertThat(ok.chainedCount()).isEqualTo(3);   // seed + t-1 + t-2

        // --- TAMPER: edit the amount of an already-chained posting (attack with direct DB access) ---
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", t1.getId());

        // --- verify again: the chain breaks at the altered posting ---
        JournalChainer.VerifyResult broken = chainer.verify();
        assertThat(broken.intact()).isFalse();
        assertThat(broken.brokenAtSeq()).isNotNull();
    }
}
