package com.ledgermind.ledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OAuth2.1 del MCP con validacion de AUDIENCIA: el SAS demo emite tokens con {@code aud=ledgermind-mcp}
 * (precondicion para que el resource server, que ahora valida aud, los acepte), y sin token /mcp da 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class McpOAuthAudienceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void sin_token_mcp_da_401() {
        ResponseEntity<String> r = rest.getForEntity("/mcp", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void el_token_del_sas_lleva_aud_ledgermind_mcp() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("mcp-client", "secret");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "ledger.read");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> tok = (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>)
                rest.postForEntity("/oauth2/token", new HttpEntity<>(form, headers), Map.class);
        assertThat(tok.getStatusCode()).isEqualTo(HttpStatus.OK);

        String accessToken = (String) tok.getBody().get("access_token");
        assertThat(accessToken).isNotBlank();

        // Decodifico el payload del JWT (sin verificar firma) y confirmo el claim aud.
        String payload = new String(Base64.getUrlDecoder()
                .decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("ledgermind-mcp");
    }
}
