package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ledgermind.TestApiKeys;

/** AUTH-5: the key value never reaches the logs or a response, on an accepted call and on a rejected one. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyNoLeakTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    private int port;

    @Test
    void keyNeverAppearsInLogsOrBodies(CapturedOutput output) throws Exception {
        ApiTestHttp h = new ApiTestHttp(port);
        String key = TestApiKeys.key();
        HttpResponse<String> ok = h.send("GET", "/api/journal/audit", key, null);
        // The wrong key CONTAINS the real key: echoing or logging the presented header would leak it.
        HttpResponse<String> rejected = h.send("GET", "/api/journal/audit", key + "x", null);

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(ok.body()).doesNotContain(key);
        assertThat(rejected.body()).doesNotContain(key);
        assertThat(rejected.headers().map().toString()).doesNotContain(key);
        assertThat(output.getAll()).as("captured stdout+stderr").doesNotContain(key);
    }
}
