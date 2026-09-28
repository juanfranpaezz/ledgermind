package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** AUTH-1/AUTH-2 under the demo profile: "demo" is not an excuse for an open read model. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class ApiKeyAuthDemoProfileTest extends ApiKeyAuthContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void anEmptyKeyHeaderOnEveryDemoPairIs401AuthInvalid() throws Exception {
        ApiTestHttp h = http();
        // Positive control: the pair IS reachable anonymously when no header is sent at all.
        assertThat(h.send("GET", "/api/demo/audit", null, null).statusCode()).as("no header").isEqualTo(200);
        for (String path : ApiSecurityConfig.DEMO_ANONYMOUS_POST) {
            assert401(h.send("POST", path, "", null), "auth_invalid", "POST " + path + ", empty key");
        }
        for (String path : ApiSecurityConfig.DEMO_ANONYMOUS_GET) {
            assert401(h.send("GET", path, "", null), "auth_invalid", "GET " + path + ", empty key");
        }
    }
}
