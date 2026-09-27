package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.TestApiKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The rate limit is counted per caller, not globally (security gate F2, 2026-09-26: with one global counter, key A
 * used up the window and key B's audit got 429; 30 anonymous demo audits put every keyed audit in 429).
 *
 * <p>Runs under the {@code demo} profile so the anonymous bucket exists, with its own keys file holding two keys
 * generated at run time (only their SHA-256 lines are written, to a temp file outside the repository).
 *
 * <p>Both outcomes, in one context (the windows are 10 s and shared by the whole context, so the order is fixed):
 * fires: the anonymous caller and key A each get 429 once their own window is used up; does not fire: key A's audit
 * after the anonymous caller is used up, and key B's audit after key A is used up, both get 200.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000",
        "ledgermind.overdraft.sweep-initial-delay-ms=3600000"
})
@ActiveProfiles("demo")
@Testcontainers
class RateLimitPerCallerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final String KEY_A = randomKey();
    private static final String KEY_B = randomKey();
    private static final Path KEYS_FILE = writeKeysFile();

    @DynamicPropertySource
    static void keysFile(DynamicPropertyRegistry registry) {
        registry.add(TestApiKeys.PROPERTY, () -> KEYS_FILE.toAbsolutePath().toString().replace('\\', '/'));
    }

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return "lmk_" + HexFormat.of().formatHex(raw);
    }

    private static Path writeKeysFile() {
        try {
            Path file = Files.createTempFile("ledgermind-ratelimit-keys-", ".txt");
            Files.writeString(file, "rate-a sha256:" + TestApiKeys.sha256Hex(KEY_A) + "\n"
                    + "rate-b sha256:" + TestApiKeys.sha256Hex(KEY_B) + "\n", StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private int get(String path, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (key != null) {
            request.header("X-API-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** Calls until the first 429 (at most {@code max} calls); returns how many were served before it, -1 if none. */
    private int useUpWindow(String path, String key, int max) throws Exception {
        for (int i = 0; i < max; i++) {
            if (get(path, key) == 429) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void oneCallerUsingUpItsWindowDoesNotLimitAnotherCaller() throws Exception {
        // Precondition: both keys authenticate (a 401 would never be counted and would make the test vacuous).
        assertThat(get("/api/journal/audit", KEY_A)).isEqualTo(200);
        assertThat(get("/api/journal/audit", KEY_B)).isEqualTo(200);

        // Anonymous demo caller uses up the shared anonymous window (fires) ...
        int anonymousServed = useUpWindow("/api/demo/audit", null, 60);
        // ... and a keyed audit is still served (does not fire).
        int keyAAfterAnonymous = get("/api/journal/audit", KEY_A);

        // Key A uses up its own window (fires) ...
        int keyAServed = useUpWindow("/api/journal/audit", KEY_A, 60);
        // ... key B is still served (does not fire), and key A stays limited.
        int keyBAfterKeyA = get("/api/journal/audit", KEY_B);
        int keyAAgain = get("/api/journal/audit", KEY_A);

        System.out.println("[RATE-PER-CALLER] anonymousServed=" + anonymousServed + " keyAAfterAnonymous="
                + keyAAfterAnonymous + " keyAServed=" + keyAServed + " keyBAfterKeyA=" + keyBAfterKeyA
                + " keyAAgain=" + keyAAgain);
        assertThat(anonymousServed).as("anonymous demo audits served before the first 429").isBetween(1, 30);
        assertThat(keyAAfterAnonymous).as("keyed audit after the anonymous window is used up").isEqualTo(200);
        assertThat(keyAServed).as("key A audits served before its first 429").isBetween(1, 30);
        assertThat(keyBAfterKeyA).as("key B audit after key A's window is used up").isEqualTo(200);
        assertThat(keyAAgain).as("key A is still limited").isEqualTo(429);
    }
}
