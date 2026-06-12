package com.ledgermind.ledger.web;

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
 * Test de integracion de la API REST: golpea los endpoints reales (HTTP real + Postgres real
 * via Testcontainers) y blinda el comportamiento que probamos a mano: crear, transferir,
 * idempotencia (sin duplicar), sobregiro (422), cuenta inexistente (404) y validacion (400).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class LedgerApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void crear_transferir_idempotencia_sobregiro_y_errores() {
        // --- crear cuentas ---
        ResponseEntity<Map> ext = rest.postForEntity("/api/accounts",
                Map.<String, Object>of("address", "external:funding", "asset", "ARS", "allowNegative", true), Map.class);
        assertThat(ext.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        rest.postForEntity("/api/accounts", Map.<String, Object>of("address", "wallet:a", "asset", "ARS"), Map.class);
        rest.postForEntity("/api/accounts", Map.<String, Object>of("address", "wallet:b", "asset", "ARS"), Map.class);

        // --- fondear wallet:a con 100000 ---
        ResponseEntity<Map> seed = rest.postForEntity("/api/transfers",
                transfer("external:funding", "wallet:a", 100000, "seed"), Map.class);
        assertThat(seed.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // --- transferir a -> b 30000 ---
        ResponseEntity<Map> t1 = rest.postForEntity("/api/transfers", transfer("wallet:a", "wallet:b", 30000, "t-1"), Map.class);
        assertThat(t1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object postingId = t1.getBody().get("id");

        // --- reintento con la MISMA idempotencyKey: mismo asiento, no duplica ---
        ResponseEntity<Map> t2 = rest.postForEntity("/api/transfers", transfer("wallet:a", "wallet:b", 30000, "t-1"), Map.class);
        assertThat(t2.getBody().get("id")).isEqualTo(postingId);

        // --- saldos: a=70000, b=30000 (el reintento NO duplico) ---
        assertThat(balanceOf("wallet:a")).isEqualTo(70000L);
        assertThat(balanceOf("wallet:b")).isEqualTo(30000L);

        // --- sobregiro -> 422 ---
        ResponseEntity<Map> overdraft = rest.postForEntity("/api/transfers",
                transfer("wallet:a", "wallet:b", 999999, "overdraft"), Map.class);
        assertThat(overdraft.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // --- cuenta inexistente -> 404 ---
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
