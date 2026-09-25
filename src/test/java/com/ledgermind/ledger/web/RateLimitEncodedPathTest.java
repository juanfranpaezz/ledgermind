package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The global rate limit must count a request by the path the server actually routes, not by the raw request URI.
 * Measured bypass (gate, 2026-09-25): {@code /api/%6Aournal/audit} is decoded by the container to
 * {@code /api/journal/audit} and served by the audit handler, but a filter matching the raw URI never counted it, so
 * 45 such requests got no 429. The request is sent with {@link HttpClient} and {@link URI#create}, which keep the
 * percent-encoding on the wire (a RestTemplate would re-encode the {@code %}).
 *
 * <p>Both outcomes: an out-of-scope path never gets 429 (the limiter does not fire on everything), and the encoded
 * audit path does get 429 once the window is used up. The scheduled jobs are pushed out so they do not interfere.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000"
})
@Testcontainers
class RateLimitEncodedPathTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String rawPath) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void percentEncodedAuditPathIsCountedByTheRateLimit() throws Exception {
        // Does not fire: an out-of-scope path is never limited, however many calls it gets.
        int outOfScope429 = 0;
        for (int i = 0; i < 60; i++) {
            if (get("/api/accounts/wallet:none").statusCode() == 429) {
                outOfScope429++;
            }
        }
        assertThat(outOfScope429).as("429s on out-of-scope /api/accounts/*").isZero();

        // The encoded path really reaches the audit handler (this is the bypass vector, not a 404).
        HttpResponse<String> first = get("/api/%6Aournal/audit");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("tamperDetected");

        // Fires: within 100 calls the encoded path must hit the shared window (30 per 10 s) and get 429.
        int encoded200 = 1;
        int encoded429 = 0;
        for (int i = 0; i < 100 && encoded429 == 0; i++) {
            int status = get("/api/%6Aournal/audit").statusCode();
            if (status == 429) {
                encoded429++;
            } else if (status == 200) {
                encoded200++;
            }
        }
        System.out.println("[RATE-LIMIT] encoded path: 200=" + encoded200 + " 429=" + encoded429
                + " outOfScope429=" + outOfScope429);
        assertThat(encoded429).as("429s on /api/%%6Aournal/audit after %d served", encoded200).isPositive();

        // Control: the plain path shares the same counter and is limited too.
        assertThat(get("/api/journal/audit").statusCode()).isEqualTo(429);
    }
}
