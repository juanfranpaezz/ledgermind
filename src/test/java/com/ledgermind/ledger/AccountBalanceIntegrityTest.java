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
 * Integridad de los contadores de saldo contra el journal.
 *
 * <p>Los contadores {@code posted_debits} / {@code posted_credits} se adelantan con {@code +=} al
 * escribir el asiento y NUNCA se recomputan: si el importe de un asiento se edita despues, el contador
 * conserva la cuenta vieja y el journal la nueva. Estos tests prueban que el chequeo nuevo da los DOS
 * resultados sobre un caso realista: VERDE sobre un ledger intacto y ROJO sobre uno alterado por SQL
 * directo (el mismo vector que usa la demo: {@code UPDATE posting SET amount = amount + 1}).
 *
 * <p>Los jobs programados se desactivan (delay enorme) para que el test sea determinista.
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

    /** El escenario exacto de la demo: 3 cuentas, 5 transferencias (ORD-1001..1005), encadenado y firmado. */
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
    void ledger_intacto_los_contadores_cierran_contra_el_replay_del_journal() {
        seedDemoLedger();

        var result = balances.verify();
        System.out.println("[BALANCE-CHECK][VERDE] " + result);

        assertThat(result.consistent()).isTrue();
        assertThat(result.mismatches()).isEmpty();
        assertThat(result.accountsChecked()).isEqualTo(3);
        assertThat(result.postingsReplayed()).isEqualTo(5);

        // y el audit consolidado (lo que leen la API, el tool MCP y la demo) lo refleja
        var audit = checkpoints.audit();
        assertThat(audit.balancesConsistent()).isTrue();
        assertThat(audit.balanceMismatches()).isEmpty();
        assertThat(audit.tamperDetected()).isFalse();
        assertThat(audit.verdict()).contains("SIN EVIDENCIA DE EDICION");
    }

    // ---------- ROJO: un asiento editado por SQL directo ----------

    @Test
    void un_asiento_editado_por_SQL_deja_el_contador_en_desacuerdo_con_el_journal() {
        seedDemoLedger();

        // Atacante con acceso a la base: le suma 1 centavo al ultimo asiento (ORD-1005, 8.000 -> 8.001).
        // Los contadores de las dos cuentas involucradas NO se tocan: siguen con la aritmetica vieja.
        Long lastId = jdbc.queryForObject("SELECT max(id) FROM posting", Long.class);
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", lastId);

        var result = balances.verify();
        result.mismatches().forEach(m -> System.out.println("[BALANCE-CHECK][ROJO] " + m.describe()));

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

        // El numero que hoy cotiza el gate de descubierto sigue siendo el viejo: 50500, no 50501.
        // (Esta asercion DOCUMENTA la exposicion; este cambio no re-cablea availableBalance.)
        assertThat(ledger.getByAddress("wallet:beto").availableBalance()).isEqualTo(50_500);

        var audit = checkpoints.audit();
        assertThat(audit.balancesConsistent()).isFalse();
        assertThat(audit.balanceMismatches()).hasSize(2);
        assertThat(audit.tamperDetected()).isTrue();
        assertThat(audit.verdict()).contains("los contadores de saldo NO cierran contra el journal");
        assertThat(audit.verdict()).contains("158000").contains("158001");
        System.out.println("[BALANCE-CHECK][ROJO][verdict] " + audit.verdict());
    }

    /**
     * El chequeo nuevo NO es redundante con la hash-chain: si el asiento alterado todavia no estaba
     * encadenado, {@code chainIntact} sigue en true y la firma verifica, y sin este chequeo el audit
     * diria "sin evidencia de edicion" con los contadores ya desfasados.
     */
    @Test
    void detecta_el_tamper_de_un_asiento_aun_NO_encadenado_que_la_hash_chain_no_ve() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:beto", "ARS", false);
        ledger.transfer("external:funding", "wallet:beto", 100_000, "seed");
        chainer.chainPendingPostings();
        assertThat(checkpoints.checkpointIfHeadAdvanced()).isPresent();

        // asiento nuevo que todavia NO paso por el encadenador (ventana async normal)
        Posting sinEncadenar = ledger.transfer("external:funding", "wallet:beto", 5_000, "aun-sin-encadenar");
        jdbc.update("UPDATE posting SET amount = amount + 1 WHERE id = ?", sinEncadenar.getId());

        var audit = checkpoints.audit();
        assertThat(audit.chainIntact()).isTrue();              // la cadena no ve ese asiento: sigue limpia
        assertThat(audit.signatureValid()).isTrue();           // la firma tampoco se toco
        assertThat(audit.signedHeadStillInChain()).isTrue();
        assertThat(audit.balancesConsistent()).isFalse();      // pero el contador ya no cierra
        assertThat(audit.tamperDetected()).isTrue();
        assertThat(audit.verdict()).contains("MANIPULACION DETECTADA");
        System.out.println("[BALANCE-CHECK][ROJO][sin-encadenar][verdict] " + audit.verdict());
    }

    private static AccountBalanceMismatch mismatchOf(AccountBalanceVerifier.BalanceVerifyResult r, String address) {
        return r.mismatches().stream()
                .filter(m -> m.address().equals(address))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no hay descuadre reportado para " + address));
    }
}
