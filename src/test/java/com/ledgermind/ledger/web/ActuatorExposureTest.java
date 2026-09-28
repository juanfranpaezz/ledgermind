package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.TestApiKeys;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Outside the {@code demo} profile the Prometheus scrape endpoint is not anonymous: it needs an X-API-Key, like /api
 * (it used to be served with no auth on the app port). Health stays anonymous (platform health checks).
 * Both outcomes: anonymous and unknown-key requests are refused, a valid key is served.
 *
 * <p>{@code @AutoConfigureObservability}: a plain {@code @SpringBootTest} disables metrics export, so without it the
 * prometheus endpoint does not exist (404) and nothing here would be measured.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false)
@Testcontainers
class ActuatorExposureTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    private int port;

    @Test
    void prometheus_needs_an_api_key_outside_the_demo_profile() throws Exception {
        ApiTestHttp http = new ApiTestHttp(port);

        HttpResponse<String> anonymous = http.send("GET", "/actuator/prometheus", null, null);
        assertThat(anonymous.statusCode()).as("anonymous scrape").isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("jvm_");

        assertThat(http.send("GET", "/actuator/prometheus", "not-a-real-key", null).statusCode())
                .as("unknown key").isEqualTo(401);

        HttpResponse<String> keyed = http.send("GET", "/actuator/prometheus", TestApiKeys.key(), null);
        assertThat(keyed.statusCode()).as("keyed scrape").isEqualTo(200);
        assertThat(keyed.body()).contains("jvm_");

        assertThat(http.send("GET", "/actuator/health", null, null).statusCode()).as("anonymous health").isEqualTo(200);
    }
}
