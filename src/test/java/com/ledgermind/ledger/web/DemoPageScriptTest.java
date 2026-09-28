package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The demo page ({@code static/index.html}) in the profile it ships in: it may call only the anonymous
 * {@code /api/demo/*} endpoints, and it must never render a check mark from a non-2xx answer or from a missing field
 * (the page once called three keyed paths, got 401, and still showed three green results).
 *
 * <p>The runtime cases run the page's OWN inline script in node ({@code src/test/resources/demo-page/page-harness.mjs})
 * with a DOM stub: once against this live demo-profile app with no credentials, once with every call answering a
 * planted 401, once with every call answering 200 {@code {}}. They are skipped (not failed) when {@code node} is not on
 * the PATH. The scheduled chainer and checkpointer are idle, so the idempotency posting stays unchained and the tamper
 * target is decided by {@code /api/demo/tamper} alone.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@ActiveProfiles("demo")
@Testcontainers
class DemoPageScriptTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final Path PAGE = Path.of("src/main/resources/static/index.html");
    private static final Path HARNESS = Path.of("src/test/resources/demo-page/page-harness.mjs");
    private static final List<String> DEMOS = List.of("idem", "tamper", "audit", "reconcile");

    @LocalServerPort
    private int port;

    @Test
    void the_page_calls_only_the_anonymous_demo_endpoints() throws Exception {
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertThat(Pattern.compile("['\"]/api/(?!demo/)").matcher(html).results().count())
                .as("quoted /api paths outside /api/demo/ in the page").isZero();
        assertThat(Pattern.compile("/api/demo/").matcher(html).results().count())
                .as("positive control: the same page does reference the demo endpoints").isGreaterThanOrEqualTo(5);
    }

    @Test
    void live_anonymous_run_renders_the_real_outcomes() throws Exception {
        JsonNode run = runPageScript("live");
        for (JsonNode call : run.path("calls")) {
            assertThat(call.path("path").asText()).startsWith("/api/demo/");
            assertThat(call.path("status").asInt()).as(call.toString()).isBetween(200, 299);
        }
        assertThat(run.path("calls")).hasSize(6);   // reset, idempotency, tamper, audit (after tamper), audit, reconcile
        JsonNode out = run.path("outputs");
        assertThat(out.path("resetMsg").asText()).startsWith("Clean state");
        assertThat(out.path("idem").asText())
                .containsPattern("posting id=\\d+\\n")
                .contains("✓ same posting", "✓ beto received 5000 only once")
                .doesNotContain("✗");
        assertThat(out.path("tamper").asText())
                .contains("✓ signatureValid", "✓ chainIntact = false")
                .doesNotContain("✗");
        assertThat(out.path("audit").asText()).contains("✗ tamperDetected = TRUE");
        assertThat(out.path("reconcile").asText()).contains("discrepancy(ies)");
    }

    @Test
    void a_planted_401_renders_a_failure_never_a_check_mark() throws Exception {
        JsonNode out = runPageScript("planted401").path("outputs");
        assertThat(out.path("resetMsg").asText()).contains("✗").contains("401");
        for (String demo : DEMOS) {
            assertThat(out.path(demo).asText()).as(demo).contains("✗").contains("HTTP 401").doesNotContain("✓");
        }
    }

    @Test
    void missing_fields_never_render_a_check_mark() throws Exception {
        JsonNode out = runPageScript("empty200").path("outputs");
        for (String demo : DEMOS) {
            assertThat(out.path(demo).asText()).as(demo).contains("✗").doesNotContain("✓");
        }
    }

    @Test
    void a_network_error_renders_a_failure_never_a_stuck_progress_text() throws Exception {
        // reset() used to have no catch, so a rejected fetch left "resetting…" on screen.
        JsonNode run = runPageScript("networkError");
        JsonNode out = run.path("outputs");
        assertThat(out.path("resetMsg").asText()).as("resetMsg")
                .contains("✗").contains("failed").doesNotContain("resetting");
        for (String demo : DEMOS) {
            assertThat(out.path(demo).asText()).as(demo).startsWith("error: ").doesNotContain("✓");
        }
    }

    private JsonNode runPageScript(String mode) throws Exception {
        assumeTrue(nodeAvailable(), "node is not on the PATH: the page-script run is skipped");
        Process p = new ProcessBuilder("node", HARNESS.toString(), PAGE.toString(), mode, "http://localhost:" + port)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("harness finished").isTrue();
        assertThat(p.exitValue()).as("harness exit code, stdout: " + stdout).isZero();
        JsonNode run = new ObjectMapper().readTree(stdout);
        // Every mode: no click handler may leave an error uncaught (a browser would just stop that handler).
        assertThat(run.has("uncaught")).as("harness reports uncaught listener errors").isTrue();
        assertThat(run.path("uncaught")).as("uncaught listener errors, outputs: " + run.path("outputs")).isEmpty();
        return run;
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
