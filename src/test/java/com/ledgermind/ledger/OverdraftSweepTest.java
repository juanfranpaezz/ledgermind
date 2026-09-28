package com.ledgermind.ledger;

import com.ledgermind.TestApiKeys;

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
 * Overdraft sweep with a watermark + freeze, on real postgres. The scheduled jobs are set to
 * 1 h: the tests drive the sweep by hand. watermark-lag 0 = the watermark advances up to the last visible posting.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000",
        "ledgermind.overdraft.sweep-initial-delay-ms=3600000",
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

    /** funding(1, allow_negative) -> a(2) 100000, -> b(3) 100000. Postings 1 and 2. */
    private void seed() {
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
        ledger.createAccount("wallet:b", "ARS", false);
        ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
        ledger.transfer("external:funding", "wallet:b", 100_000, "seed-b");
    }

    /** A posting inserted OUTSIDE the app: a(2) -> b(3) 150000, without touching the counters. It overdraws a in the journal. */
    private void plantOverdraft() {
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key)"
                + " VALUES (2, 3, 150000, 'ARS', 'PLANTED-OVERDRAFT')");
    }

    private static void log(String tag, SweepResult r) {
        System.out.println("[SWEEP][" + tag + "] " + r);
    }

    @Test
    void planted_overdraft_is_flagged_and_the_next_transfer_is_rejected_with_a_specific_error() throws Exception {
        seed();
        log("seed", sweeper.sweep());
        plantOverdraft();
        // the app's gate looks at the counters: without the sweep, the account keeps operating as if it had 100000
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
                .isInstanceOf(AccountFrozenException.class).hasMessageContaining("FROZEN")
                .satisfies(e -> assertThat(((AccountFrozenException) e).getAccountId()).isEqualTo(2L));
        assertThatThrownBy(() -> ledger.transfer("external:funding", "wallet:a", 1_000, "after-freeze-in"))
                .isInstanceOf(AccountFrozenException.class);
        // an account that is not flagged keeps operating
        ledger.transfer("external:funding", "wallet:b", 1_000, "unrelated-ok");

        // over HTTP: 423 with a specific ProblemDetail, not a 500
        mvc.perform(post("/api/transfers").header("X-API-Key", TestApiKeys.key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debitAddress\":\"wallet:a\",\"creditAddress\":\"wallet:b\",\"amount\":500,"
                                + "\"idempotencyKey\":\"http-frozen\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.title").value("Account frozen for overdraft"))
                .andExpect(jsonPath("$.accountId").value(2));
    }

    @Test
    void clean_burst_traffic_flags_no_account() {
        ledger.createAccount("external:funding", "ARS", true);
        int wallets = 10;
        for (int i = 0; i < wallets; i++) {
            ledger.createAccount("wallet:w" + i, "ARS", false);
            ledger.transfer("external:funding", "wallet:w" + i, 10_000, "fund-w" + i);
        }
        int n = 1000;
        for (int i = 0; i < n; i++) {
            if (i % 4 == 3) {   // also movements between wallets, always with funds
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
    void unfreezing_requires_admin_scope_records_who_and_why_and_restores_transfers() {
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
                new TestingAuthenticationToken("operator-ana", null, "SCOPE_ledger.admin"));
        String msg = adminTools.unfreezeAccount("wallet:a", "posting 3 reviewed; counters reconciled by hand");
        assertThat(msg).contains("unfrozen by operator-ana");
        Map<String, Object> row = jdbc.queryForMap("SELECT cleared_at, cleared_by, clear_reason FROM overdraft_flag");
        System.out.println("[SWEEP][unfreeze-record] " + row);
        assertThat(row.get("cleared_at")).isNotNull();
        assertThat(row.get("cleared_by")).isEqualTo("operator-ana");
        assertThat(row.get("clear_reason")).isEqualTo("posting 3 reviewed; counters reconciled by hand");
        assertThat(sweeper.activeFlags()).isEmpty();

        ledger.transfer("wallet:a", "wallet:b", 1_000, "after-unfreeze");   // vuelve a operar
    }

    /**
     * (a2): the EDIT of an already-swept posting (id <= watermark) was not seen even after
     * the account moved again, because the incremental total was not re-derived. Now touching an account re-derives
     * ALL its postings (not only the ones above the watermark).
     */
    @Test
    void edit_of_an_already_swept_posting_is_flagged_when_the_account_moves_again() {
        seed();
        ledger.transfer("wallet:a", "wallet:b", 10, "small");              // posting 3: a -> b 10
        log("a2-first", sweeper.sweep());                                     // the watermark passes posting 3
        jdbc.update("UPDATE posting SET amount = 150000 WHERE id = 3");       // edit outside the app, below the watermark
        SweepResult quiet = sweeper.sweep();
        log("a2-quiet", quiet);
        assertThat(quiet.flagged()).isZero();                                 // the account did not move: not re-derived
        ledger.transfer("wallet:a", "wallet:b", 1, "a2-moves");               // the gate looks at the counters: it passes
        SweepResult moved = sweeper.sweep();
        log("a2-moved", moved);
        assertThat(moved.flagged()).isEqualTo(1);
        assertThat(sweeper.activeFlags()).extracting(OverdraftSweeper.OverdraftFlag::accountId).containsExactly(2L);
        assertThat(sweeper.activeFlags().get(0).derivedAvailable()).isEqualTo(-50_001L);
    }

    /**
     * (a3) DOCUMENTED, not fixed (a deliberate design decision): an INSERT outside the app with an id BELOW the watermark
     * (e.g. id -1) on an account that never moves again is not seen by the sweep. This test PINS that behaviour (it is in
     * the DOES NOT DETECT of the README and the tools) so that changing it is a deliberate decision. If the account moves, the
     * full replay of the touched account (a2) includes it.
     */
    @Test
    void insertion_with_id_below_the_watermark_on_a_quiet_account_is_not_seen_by_the_sweep_documented() {
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
        assertThat(moved.flagged()).isEqualTo(1);                             // touched: the full replay includes id -1
    }

    @Test
    void second_pass_without_new_postings_touches_no_account() {
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
    void freeze_check_on_the_hot_path_costs_less_than_5ms_p50() {
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
