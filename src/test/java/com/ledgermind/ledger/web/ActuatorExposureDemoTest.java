package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Under the {@code demo} profile the Prometheus scrape endpoint stays anonymous: the bundled observability compose
 * scrapes the demo without a key. Counterpart of {@link ActuatorExposureTest} (outside demo it needs an X-API-Key).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("demo")
@Testcontainers
class ActuatorExposureDemoTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    private int port;

    @Test
    void prometheus_stays_anonymous_under_the_demo_profile() throws Exception {
        HttpResponse<String> anonymous = new ApiTestHttp(port).send("GET", "/actuator/prometheus", null, null);
        assertThat(anonymous.statusCode()).isEqualTo(200);
        assertThat(anonymous.body()).contains("jvm_");
    }

    /** Control for {@link ActuatorExposureTest#oauth2_jwks_is_not_served_outside_the_demo_profile}: under demo it is served. */
    @Test
    void oauth2_jwks_is_served_under_the_demo_profile() throws Exception {
        HttpResponse<String> jwks = new ApiTestHttp(port).send("GET", "/oauth2/jwks", null, null);
        assertThat(jwks.statusCode()).isEqualTo(200);
        assertThat(jwks.body()).contains("\"keys\"");
    }
}
