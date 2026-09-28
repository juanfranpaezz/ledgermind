package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.ledgermind.TestApiKeys;

/**
 * AUTH-1 and AUTH-2, run once per profile by the concrete subclasses: {@code /api} answers 401 {@code auth_missing}
 * without a key, 401 {@code auth_invalid} with a wrong key, and serves the call with a valid key.
 */
abstract class ApiKeyAuthContract {

    static final String CHALLENGE = "ApiKey header=\"X-API-Key\"";

    @LocalServerPort
    private int port;

    ApiTestHttp http() {
        return new ApiTestHttp(port);
    }

    private static String transferBody(String u) {
        return "{\"debitAddress\":\"external:auth-" + u + "\",\"creditAddress\":\"wallet:auth-" + u
                + "\",\"amount\":100,\"idempotencyKey\":\"auth-" + u + "\"}";
    }

    void assert401(HttpResponse<String> r, String code, String what) throws Exception {
        assertThat(r.statusCode()).as(what + " status").isEqualTo(401);
        assertThat(ApiTestHttp.json(r).path("code").asText()).as(what + " code").isEqualTo(code);
        assertThat(r.headers().firstValue("WWW-Authenticate")).as(what + " challenge").hasValue(CHALLENGE);
    }

    @Test
    void withoutAKeyEveryApiCallIs401AuthMissing() throws Exception {
        ApiTestHttp h = http();
        assert401(h.send("GET", "/api/journal/audit", null, null), "auth_missing", "GET audit");
        assert401(h.send("GET", "/api/accounts/x", null, null), "auth_missing", "GET account");
        assert401(h.send("POST", "/api/transfers", null, transferBody("none")), "auth_missing", "POST transfer");
        // Encoded spellings that the container decodes to /api/...: still behind the key.
        for (String variant : List.of("/api/%6Aournal/audit", "/%61pi/journal/audit")) {
            int status = h.send("GET", variant, null, null).statusCode();
            assertThat(status).as("anonymous " + variant).isIn(400, 401, 404);
        }
    }

    @Test
    void anEmptyKeyHeaderIs401AuthInvalidNotAnonymous() throws Exception {
        // An empty X-API-Key value is a presented credential that matches nothing (ADR 0004 diagram), not an
        // absent header.
        ApiTestHttp h = http();
        assert401(h.send("GET", "/api/journal/audit", "", null), "auth_invalid", "GET audit, empty key");
        assert401(h.send("GET", "/api/accounts/x", "", null), "auth_invalid", "GET account, empty key");
        assert401(h.send("POST", "/api/transfers", "", transferBody("empty")), "auth_invalid", "POST, empty key");
    }

    @Test
    void aValidKeyIsServedAndAnAlteredKeyIs401AuthInvalid() throws Exception {
        ApiTestHttp h = http();
        String k = TestApiKeys.key();
        String u = UUID.randomUUID().toString().substring(0, 8);
        assertThat(h.send("POST", "/api/accounts", k, "{\"address\":\"external:auth-" + u
                + "\",\"asset\":\"ARS\",\"allowNegative\":true}").statusCode() / 100).isEqualTo(2);
        assertThat(h.send("POST", "/api/accounts", k, "{\"address\":\"wallet:auth-" + u
                + "\",\"asset\":\"ARS\"}").statusCode() / 100).isEqualTo(2);

        assertThat(h.send("GET", "/api/journal/audit", k, null).statusCode()).isEqualTo(200);
        assertThat(h.send("GET", "/api/accounts/wallet:auth-" + u, k, null).statusCode()).isEqualTo(200);
        assertThat(h.send("POST", "/api/transfers", k, transferBody(u)).statusCode() / 100).isEqualTo(2);

        String wrong = k + "x";
        assert401(h.send("GET", "/api/journal/audit", wrong, null), "auth_invalid", "GET audit, wrong key");
        assert401(h.send("GET", "/api/accounts/wallet:auth-" + u, wrong, null), "auth_invalid", "GET account, wrong key");
        assert401(h.send("POST", "/api/transfers", wrong, transferBody(u + "w")), "auth_invalid", "POST, wrong key");
    }
}
