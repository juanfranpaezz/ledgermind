package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgermind.TestApiKeys;
import com.ledgermind.ledger.LedgerService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OVF-2 (plan v1): over HTTP with a key, a journal whose sum is above the 64-bit range (two out-of-band postings of
 * 5e18 on one account, counters untouched) is answered {@code 200} with {@code tamperDetected=true}, never {@code 409}
 * (gate finding 2026-09-25: the sum read with {@code getLong} surfaced as a 409 on this route).
 *
 * <p>Both outcomes: the overflowing journal reports tamper; the same route on an in-range journal whose counters match
 * reports {@code balancesConsistent=true}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000",
        "ledgermind.overdraft.sweep-initial-delay-ms=3600000"
})
@Testcontainers
class OverflowAuditHttpTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    private int port;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);   // id 1
        ledger.createAccount("wallet:a", "ARS", false);          // id 2
    }

    private void insertPosting(long amount, String key) {
        jdbc.update("INSERT INTO posting (debit_account_id, credit_account_id, amount, asset, idempotency_key)"
                + " VALUES (1, 2, ?, 'ARS', ?)", amount, key);
    }

    private HttpResponse<String> audit() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/journal/audit"))
                .header("X-API-Key", TestApiKeys.key()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void journalSumAboveLongMaxIsA200TamperNotA409() throws Exception {
        insertPosting(5_000_000_000_000_000_000L, "ovf2-1");
        insertPosting(5_000_000_000_000_000_000L, "ovf2-2");

        HttpResponse<String> response = audit();
        System.out.println("[OVF-2] status=" + response.statusCode() + " body=" + response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = ApiTestHttp.json(response);
        assertThat(body.path("tamperDetected").asBoolean(false)).isTrue();
        assertThat(body.path("balancesConsistent").asBoolean(true)).isFalse();
    }

    @Test
    void inRangeJournalWithMatchingCountersIsConsistentOnTheSameRoute() throws Exception {
        insertPosting(4_000_000_000_000_000_000L, "ovf2-3");
        insertPosting(4_000_000_000_000_000_000L, "ovf2-4");
        jdbc.update("UPDATE account SET posted_debits = 8000000000000000000 WHERE id = 1");
        jdbc.update("UPDATE account SET posted_credits = 8000000000000000000 WHERE id = 2");

        HttpResponse<String> response = audit();
        System.out.println("[OVF-2 control] status=" + response.statusCode() + " body=" + response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ApiTestHttp.json(response).path("balancesConsistent").asBoolean(false)).isTrue();
    }
}
