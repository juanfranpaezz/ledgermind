package com.ledgermind.ledger.web;

import org.junit.jupiter.api.BeforeEach;
import com.ledgermind.TestApiKeys;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * REST API integration test: it hits the real endpoints (real HTTP + real Postgres
 * via Testcontainers) and locks in the behaviour we tested by hand: create, transfer,
 * idempotency (no duplicates), overdraft (422), non-existent account (404) and validation (400).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class LedgerApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestRestTemplate rest;

    /** Every /api call needs a key (ApiSecurityConfig); the test key is generated at run time by TestApiKeys. */
    @BeforeEach
    void sendApiKey() {
        rest.getRestTemplate().getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("X-API-Key", TestApiKeys.key());
            return execution.execute(request, body);
        });
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void crear_transferir_idempotencia_sobregiro_y_errores() {
        // --- create accounts ---
        ResponseEntity<Map> ext = rest.postForEntity("/api/accounts",
                Map.<String, Object>of("address", "external:funding", "asset", "ARS", "allowNegative", true), Map.class);
        assertThat(ext.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        rest.postForEntity("/api/accounts", Map.<String, Object>of("address", "wallet:a", "asset", "ARS"), Map.class);
        rest.postForEntity("/api/accounts", Map.<String, Object>of("address", "wallet:b", "asset", "ARS"), Map.class);

        // --- fund wallet:a with 100000 ---
        ResponseEntity<Map> seed = rest.postForEntity("/api/transfers",
                transfer("external:funding", "wallet:a", 100000, "seed"), Map.class);
        assertThat(seed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // --- transferir a -> b 30000 ---
        ResponseEntity<Map> t1 = rest.postForEntity("/api/transfers", transfer("wallet:a", "wallet:b", 30000, "t-1"), Map.class);
        assertThat(t1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object postingId = t1.getBody().get("id");

        // --- retry with the SAME idempotencyKey: same posting, no duplicate ---
        ResponseEntity<Map> t2 = rest.postForEntity("/api/transfers", transfer("wallet:a", "wallet:b", 30000, "t-1"), Map.class);
        assertThat(t2.getBody().get("id")).isEqualTo(postingId);

        // --- balances: a=70000, b=30000 (the retry did NOT duplicate) ---
        assertThat(balanceOf("wallet:a")).isEqualTo(70000L);
        assertThat(balanceOf("wallet:b")).isEqualTo(30000L);

        // --- overdraft -> 422 ---
        ResponseEntity<Map> overdraft = rest.postForEntity("/api/transfers",
                transfer("wallet:a", "wallet:b", 999999, "overdraft"), Map.class);
        assertThat(overdraft.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // --- non-existent account -> 404 ---
        ResponseEntity<Map> notFound = rest.getForEntity("/api/accounts/wallet:zzz", Map.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // --- validacion: amount <= 0 -> 400 ---
        ResponseEntity<Map> bad = rest.postForEntity("/api/transfers",
                transfer("wallet:a", "wallet:b", 0, "bad"), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private Map<String, Object> transfer(String debit, String credit, long amount, String key) {
        return Map.<String, Object>of("debitAddress", debit, "creditAddress", credit, "amount", amount, "idempotencyKey", key);
    }

    @SuppressWarnings("rawtypes")
    private long balanceOf(String address) {
        ResponseEntity<Map> r = rest.getForEntity("/api/accounts/" + address, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) r.getBody().get("balance")).longValue();
    }
}
