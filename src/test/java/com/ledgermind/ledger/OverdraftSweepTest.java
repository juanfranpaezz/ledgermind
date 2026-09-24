package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgermind.ledger.OverdraftSweeper.SweepResult;
import com.ledgermind.ledger.mcp.LedgerAdminMcpTools;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Barrido de sobregiro con marca de agua + congelamiento (dec-151), sobre postgres real. Los jobs programados estan en
 * 1 h: los tests manejan el barrido a mano. watermark-lag 0 = la marca avanza hasta el ultimo asiento visible.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000",
        "ledgermind.overdraft.watermark-lag-ms=0"
})
@AutoConfigureMockMvc
@Testcontainers
class OverdraftSweepTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private OverdraftSweeper sweeper;
    @Autowired
    private LedgerAdminMcpTools adminTools;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE overdraft_sweep_state SET watermark_posting_id = 0 WHERE id = 1");
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** funding(1, allow_negative) -> a(2) 100000, -> b(3) 100000. Asientos 1 y 2. */
    private void seed() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        ledger.transfer("external:funding", "wallet:b", 100_000, "seed-b");
    }

    /** Asiento insertado POR FUERA de la app: a(2) -> b(3) 150000, sin tocar contadores. Sobregira a en el journal. */
    private void plantOverdraft() {
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key)"
                + " VALUES (2, 3, 150000, 'ARS', 'PLANTED-OVERDRAFT')");
    }

    private static void log(String tag, SweepResult r) {
        System.out.println("[SWEEP][" + tag + "] " + r);
    }

    @Test
    void sobregiro_plantado_se_marca_y_la_siguiente_transferencia_se_rechaza_con_error_especifico() throws Exception {
        seed();
        log("seed", sweeper.sweep());
        plantOverdraft();
        // el gate de la app mira los contadores: sin el barrido, la cuenta sigue operando como si tuviera 100000
        assertThat(ledger.getByAddress("wallet:a").availableBalance()).isEqualTo(100_000);

        SweepResult r = sweeper.sweep();
        log("planted", r);
        assertThat(r.flagged()).isEqualTo(1);
        assertThat(r.touchedAccounts()).isEqualTo(2);
        Map<String, Object> flag = jdbc.queryForMap("SELECT * FROM overdraft_flag WHERE cleared_at IS NULL");
        System.out.println("[SWEEP][flag-evidence] " + flag);
        assertThat(((Number) flag.get("account_id")).longValue()).isEqualTo(2L);
        assertThat(((Number) flag.get("derived_available")).longValue()).isEqualTo(-50_000L);
        assertThat(((Number) flag.get("stored_available")).longValue()).isEqualTo(100_000L);
        assertThat(((Number) flag.get("posting_id_from")).longValue()).isEqualTo(3L);
        assertThat(((Number) flag.get("posting_id_to")).longValue()).isEqualTo(3L);

        assertThatThrownBy(() -> ledger.transfer("wallet:a", "wallet:b", 1_000, "after-freeze-out"))
                .isInstanceOf(AccountFrozenException.class).hasMessageContaining("CONGELADA")
                .satisfies(e -> assertThat(((AccountFrozenException) e).getAccountId()).isEqualTo(2L));
        assertThatThrownBy(() -> ledger.transfer("external:funding", "wallet:a", 1_000, "after-freeze-in"))
                .isInstanceOf(AccountFrozenException.class);
        // una cuenta no marcada sigue operando
        ledger.transfer("external:funding", "wallet:b", 1_000, "unrelated-ok");

        // por HTTP: 423 con ProblemDetail especifico, no un 500
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debitAddress\":\"wallet:a\",\"creditAddress\":\"wallet:b\",\"amount\":500,"
                                + "\"idempotencyKey\":\"http-frozen\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.title").value("Cuenta congelada por sobregiro"))
                .andExpect(jsonPath("$.accountId").value(2));
    }

    @Test
    void trafico_limpio_con_rafaga_no_marca_ninguna_cuenta() {
        ledger.createAccount("external:funding", "ARS", true);
        int wallets = 10;
        for (int i = 0; i < wallets; i++) {
            ledger.createAccount("wallet:w" + i, "ARS", false);
            ledger.transfer("external:funding", "wallet:w" + i, 10_000, "fund-w" + i);
        }
        int n = 1000;
        for (int i = 0; i < n; i++) {
            if (i % 4 == 3) {   // tambien movimientos entre billeteras, siempre con fondos
                ledger.transfer("wallet:w" + (i % wallets), "wallet:w" + ((i + 1) % wallets), 5, "burst-" + i);
            } else {
                ledger.transfer("external:funding", "wallet:w" + (i % wallets), 100, "burst-" + i);
            }
        }
        SweepResult full = sweeper.sweep();
        log("burst-" + n, full);
        System.out.println("[MEASURE] sweep over " + n + " new postings, " + full.touchedAccounts()
                + " accounts: " + full.durationMicros() / 1000.0 + " ms");
        for (int i = 0; i < 100; i++) {
            ledger.transfer("external:funding", "wallet:w" + (i % wallets), 100, "tail-" + i);
        }
        SweepResult tail = sweeper.sweep();
        log("tail-100", tail);
        System.out.println("[MEASURE] sweep over 100 new postings on top of " + n + ": "
                + tail.durationMicros() / 1000.0 + " ms");
        assertThat(full.flagged()).isZero();
        assertThat(tail.flagged()).isZero();
        assertThat(full.touchedAccounts()).isEqualTo(wallets + 1);   // funding + 10 billeteras
        assertThat(sweeper.activeFlags()).isEmpty();
    }

    @Test
    void descongelar_exige_scope_admin_registra_quien_y_por_que_y_restaura_transferencias() {
        seed();
        sweeper.sweep();
        plantOverdraft();
        assertThat(sweeper.sweep().flagged()).isEqualTo(1);
        assertThatThrownBy(() -> ledger.transfer("wallet:a", "wallet:b", 1_000, "frozen-1"))
                .isInstanceOf(AccountFrozenException.class);

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("intruso", null, "SCOPE_ledger.read"));
        assertThatThrownBy(() -> adminTools.unfreezeAccount("wallet:a", "quiero"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(sweeper.activeFlags()).hasSize(1);

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("operador-ana", null, "SCOPE_ledger.admin"));
        String msg = adminTools.unfreezeAccount("wallet:a", "asiento 3 revisado; contadores conciliados a mano");
        assertThat(msg).contains("descongelada por operador-ana");
        Map<String, Object> row = jdbc.queryForMap("SELECT cleared_at, cleared_by, clear_reason FROM overdraft_flag");
        System.out.println("[SWEEP][unfreeze-record] " + row);
        assertThat(row.get("cleared_at")).isNotNull();
        assertThat(row.get("cleared_by")).isEqualTo("operador-ana");
        assertThat(row.get("clear_reason")).isEqualTo("asiento 3 revisado; contadores conciliados a mano");
        assertThat(sweeper.activeFlags()).isEmpty();

        ledger.transfer("wallet:a", "wallet:b", 1_000, "after-unfreeze");   // vuelve a operar
    }

    /**
     * (a2) del gate 2026-09-24: la EDICION de un asiento ya barrido (id <= marca de agua) no se veia ni despues de que
     * la cuenta volviera a moverse, porque el total incremental no se re-derivaba. Ahora tocar una cuenta re-deriva
     * TODOS sus asientos (no solo los de arriba de la marca).
     */
    @Test
    void edicion_de_un_asiento_ya_barrido_se_marca_cuando_la_cuenta_vuelve_a_moverse() {
        seed();
        ledger.transfer("wallet:a", "wallet:b", 10, "small");              // asiento 3: a -> b 10
        log("a2-first", sweeper.sweep());                                     // la marca de agua pasa el asiento 3
        jdbc.update("UPDATE posting SET amount = 150000 WHERE id = 3");       // edicion por fuera, debajo de la marca
        SweepResult quiet = sweeper.sweep();
        log("a2-quiet", quiet);
        assertThat(quiet.flagged()).isZero();                                 // la cuenta no se movio: no se re-deriva
        ledger.transfer("wallet:a", "wallet:b", 1, "a2-moves");               // el gate mira contadores: pasa
        SweepResult moved = sweeper.sweep();
        log("a2-moved", moved);
        assertThat(moved.flagged()).isEqualTo(1);
        assertThat(sweeper.activeFlags()).extracting(OverdraftSweeper.OverdraftFlag::accountId).containsExactly(2L);
        assertThat(sweeper.activeFlags().get(0).derivedAvailable()).isEqualTo(-50_001L);
    }

    /**
     * (a3) DOCUMENTADO, no arreglado (decision del dueno, J2): un INSERT por fuera con id POR DEBAJO de la marca de agua
     * (p.ej. id -1) en una cuenta que no vuelve a moverse no lo ve el barrido. Este test FIJA ese comportamiento (esta en
     * el NO DETECTA del README y de los tools) para que cambiarlo sea una decision deliberada. Si la cuenta se mueve, el
     * replay completo de la cuenta tocada (a2) lo incluye.
     */
    @Test
    void insercion_con_id_bajo_la_marca_de_agua_en_cuenta_quieta_no_la_ve_el_barrido_documentado() {
        seed();
        log("a3-first", sweeper.sweep());
        jdbc.update("INSERT INTO posting (id, debit_account_id, credit_account_id, amount, asset, idempotency_key)"
                + " OVERRIDING SYSTEM VALUE VALUES (-1, 2, 3, 150000, 'ARS', 'PLANTED-LOW-ID')");
        SweepResult quiet = sweeper.sweep();
        log("a3-quiet", quiet);
        assertThat(quiet.touchedAccounts()).isZero();
        assertThat(sweeper.activeFlags()).isEmpty();                          // NO DETECTA (documentado)
        ledger.transfer("wallet:a", "wallet:b", 1, "a3-moves");
        SweepResult moved = sweeper.sweep();
        log("a3-moved", moved);
        assertThat(moved.flagged()).isEqualTo(1);                             // tocada: el replay completo incluye id -1
    }

    @Test
    void segunda_pasada_sin_asientos_nuevos_no_toca_ninguna_cuenta() {
        seed();
        SweepResult first = sweeper.sweep();
        log("first", first);
        assertThat(first.touchedAccounts()).isEqualTo(3);
        assertThat(first.newWatermark()).isEqualTo(2L);

        SweepResult second = sweeper.sweep();
        log("second", second);
        assertThat(second.touchedAccounts()).isZero();
        assertThat(second.previousWatermark()).isEqualTo(2L);
        assertThat(second.newWatermark()).isEqualTo(2L);

        ledger.transfer("wallet:a", "wallet:b", 10, "one-more");
        SweepResult third = sweeper.sweep();
        log("third", third);
        assertThat(third.touchedAccounts()).isEqualTo(2);   // solo a y b, no funding
        assertThat(third.scannedFromId()).isEqualTo(3L);
    }

    @Test
    void chequeo_de_congelamiento_en_el_camino_caliente_cuesta_menos_de_5ms_p50() {
        seed();
        sweeper.sweep();
        int n = 300;
        long[] check = new long[n];
        long[] transfer = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            sweeper.assertNotFrozen(2L, 3L);
            check[i] = System.nanoTime() - t0;
            t0 = System.nanoTime();
            ledger.transfer("external:funding", "wallet:a", 1, "lat-" + i);
            transfer[i] = System.nanoTime() - t0;
        }
        Arrays.sort(check);
        Arrays.sort(transfer);
        double checkP50 = check[n / 2] / 1e6;
        double transferP50 = transfer[n / 2] / 1e6;
        System.out.println("[MEASURE] n=" + n + " freeze-check p50=" + checkP50 + " ms p95=" + check[n * 95 / 100] / 1e6
                + " ms; transfer-with-check p50=" + transferP50 + " ms p95=" + transfer[n * 95 / 100] / 1e6 + " ms");
        assertThat(checkP50).isLessThan(5.0);
    }
}
