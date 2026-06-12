package com.ledgermind.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.LedgerService;
import com.ledgermind.ledger.Posting;
import java.util.List;
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
 * Reconciliacion contra el ledger real: el feed demo expone los tres descuadres, y un feed que coincide
 * con el ledger cuadra. Asi confirmamos la proyeccion asiento -> {@link LedgerEntry} y el servicio entero.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@Testcontainers
class ReconciliationServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private ReconciliationService reconciliation;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
    }

    @Test
    void el_feed_demo_expone_los_tres_descuadres() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "ORD-1");
        ledger.transfer("external:funding", "wallet:a", 50_000, "ORD-2");
        ledger.transfer("external:funding", "wallet:a", 30_000, "ORD-3");
        ledger.transfer("external:funding", "wallet:a", 20_000, "ORD-4");
        ledger.transfer("external:funding", "wallet:a", 10_000, "ORD-5");

        ReconciliationReport report = reconciliation.reconcileDemoFeed();

        assertThat(report.balanced()).isFalse();
        assertThat(report.matched()).isGreaterThanOrEqualTo(1);
        assertThat(report.discrepancies()).extracting(Discrepancy::type).contains(
                Discrepancy.Type.AMOUNT_MISMATCH,
                Discrepancy.Type.MISSING_IN_LEDGER,
                Discrepancy.Type.MISSING_IN_FEED);
    }

    @Test
    void un_feed_que_coincide_con_el_ledger_cuadra() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:b", "ARS", false);
        Posting p = ledger.transfer("external:funding", "wallet:b", 70_000, "ORD-X");

        ReconciliationReport report = reconciliation.reconcile(
                List.of(new SettlementRecord("ORD-X", 70_000, p.getCreatedAt())));

        assertThat(report.balanced()).isTrue();
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.discrepancies()).isEmpty();
    }
}
