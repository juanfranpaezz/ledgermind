package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DEMO-2 and DEMO-3: the demo flows keep their outcomes for an anonymous caller, through /api/demo only, and an
 * anonymous caller cannot grow the journal (the idempotency demo uses one fixed key, so repeats replay).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class DemoFlowAnonymousTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode call(String method, String path) throws Exception {
        HttpResponse<String> r = new ApiTestHttp(port).send(method, path, null, null);
        assertThat(r.statusCode()).as(method + " " + path + " anonymous").isEqualTo(200);
        return ApiTestHttp.json(r);
    }

    private long postings() {
        return jdbc.queryForObject("SELECT count(*) FROM posting", Long.class);
    }

    @Test
    void idempotencyDemoReplaysAndAnonymousCallersCannotGrowTheJournal() throws Exception {
        call("POST", "/api/demo/reset");
        JsonNode first = call("POST", "/api/demo/idempotency");
        assertThat(first.path("firstPostingId").asLong()).isPositive();
        assertThat(first.path("secondPostingId").asLong()).isEqualTo(first.path("firstPostingId").asLong());
        assertThat(first.path("betoAfter").asLong() - first.path("betoBefore").asLong()).isEqualTo(5000);
        long afterOne = postings();

        for (int i = 0; i < 19; i++) {
            JsonNode again = call("POST", "/api/demo/idempotency");
            assertThat(again.path("firstPostingId").asLong()).isEqualTo(first.path("firstPostingId").asLong());
            assertThat(again.path("betoAfter").asLong()).isEqualTo(again.path("betoBefore").asLong());
        }
        assertThat(postings()).as("posting rows after 20 anonymous calls vs after 1").isEqualTo(afterOne);
    }

    @Test
    void tamperIsDetectedAndResetClearsIt() throws Exception {
        call("POST", "/api/demo/reset");
        call("POST", "/api/demo/tamper");
        assertThat(call("GET", "/api/demo/audit").path("tamperDetected").asBoolean()).isTrue();
        call("POST", "/api/demo/reset");
        assertThat(call("GET", "/api/demo/audit").path("tamperDetected").asBoolean()).isFalse();
    }
}
