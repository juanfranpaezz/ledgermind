package com.ledgermind.ledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * /mcp token handling over HTTP: a token signed with the demo Authorization Server's own key is refused when its
 * audience is another resource or when it has expired, and accepted (JSON-RPC initialize answered) when both hold.
 *
 * <p>Why DEFINED_PORT: the resource server fetches the signing keys from {@code jwk-set-uri}, which defaults to
 * http://localhost:8080/oauth2/jwks, so a RANDOM_PORT server cannot point it at itself. A free port is picked before the
 * context starts and both {@code server.port} and {@code jwk-set-uri} are set to it. Tokens are minted directly with
 * the app's {@link JWKSource}; the demo client credentials are not involved (that path is
 * {@link McpOAuthAudienceTest}).
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ActiveProfiles("demo")
@Testcontainers
class McpOAuthTokenValidationTest {

    private static final int PORT = freePort();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void selfPointingJwkSetUri(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + PORT + "/oauth2/jwks");
    }

    @Autowired
    private JWKSource<SecurityContext> jwkSource;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void a_token_for_another_audience_is_refused_with_401() throws Exception {
        HttpResponse<String> r = initialize(mint(List.of("other-resource"), Instant.now().plusSeconds(300)));
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(r)).contains("invalid_token").contains("aud");
    }

    @Test
    void an_expired_token_is_refused_with_401() throws Exception {
        // exp one hour ago: far past the resource server's 60 s clock-skew allowance.
        HttpResponse<String> r = initialize(mint(List.of("ledgermind-mcp"), Instant.now().minusSeconds(3600)));
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(wwwAuthenticate(r)).contains("invalid_token").contains("expired");
    }

    @Test
    void a_valid_token_for_this_audience_gets_the_initialize_result() throws Exception {
        HttpResponse<String> r = initialize(mint(List.of("ledgermind-mcp"), Instant.now().plusSeconds(300)));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"jsonrpc\"").contains("\"result\"").contains("protocolVersion");
    }

    private String mint(List<String> audience, Instant expiresAt) {
        Instant issuedAt = expiresAt.isBefore(Instant.now()) ? expiresAt.minusSeconds(600) : Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("mcp-token-validation-test")
                .audience(audience)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return new NimbusJwtEncoder(jwkSource)
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private HttpResponse<String> initialize(String token) throws IOException, InterruptedException {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"mcp-token-validation-test\",\"version\":\"1\"}}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String wwwAuthenticate(HttpResponse<String> r) {
        return r.headers().firstValue("WWW-Authenticate").orElse("");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free port", e);
        }
    }
}
