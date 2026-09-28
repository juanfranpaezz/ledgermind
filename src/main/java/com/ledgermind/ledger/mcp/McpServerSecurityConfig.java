package com.ledgermind.ledger.mcp;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MCP server security: it turns it into an OAuth 2.1 Resource Server (standard Spring Security).
 *
 * <p>Security chains in every profile, in order: {@code /api/**} (X-API-Key, order 0, {@code ApiSecurityConfig});
 * {@code /mcp} (JWT Bearer, order 1, this class; per-tool scope via {@code @PreAuthorize}); and the default chain
 * (order 2, this class), which leaves only the static page and the actuator open (outside the {@code demo} profile the
 * /api chain also takes the prometheus endpoint and asks for an X-API-Key). Under the {@code demo} profile a fourth
 * chain runs before all of them: the co-located authorization server's ({@code DemoAuthorizationServerConfig},
 * {@code @Order(HIGHEST_PRECEDENCE)}), matching only its endpoints such as {@code /oauth2/token} and
 * {@code /oauth2/jwks}; outside demo it does not exist, those paths reach the default chain and no handler serves them
 * ({@code /oauth2/jwks} answers 404). OAuth applies only to {@code /mcp}.
 *
 * <p>The JWT is validated with signature + expiry + AUDIENCE: a token signed by the same IdP but issued
 * for ANOTHER resource (without {@code aud=ledgermind-mcp}) is rejected. That closes the classic
 * confused-deputy / token-reuse problem of OAuth/MCP (in the spirit of RFC 8707, resource indicators). The decoder is LAZY (jwk-set-uri): it does not
 * fetch at startup, avoiding the problem with the demo profile's co-located Authorization Server.
 *
 * <p>NOTE: org.springaicommunity:mcp-server-security:0.0.6 was rejected because its decoder fetches the issuer EAGERLY
 * when the filter is created, which is incompatible with the co-located AS. The RFC 9728 endpoint is deferred.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class McpServerSecurityConfig {

    /** The MCP endpoint is an OAuth 2.1 Resource Server: without a valid JWT (signature+exp+aud) -> 401. */
    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/mcp", "/mcp/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * The resource server's JWT decoder: lazy (jwk-set-uri) + timestamp validation (default) + AUDIENCE.
     * {@code oauth2ResourceServer().jwt()} picks it up automatically because it is a {@link JwtDecoder} bean.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${ledgermind.mcp.audience:ledgermind-mcp}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), withAudience));
        return decoder;
    }

    /**
     * Default chain (order 2): whatever the {@code /api/**} chain (order 0) and the {@code /mcp} chain (order 1) do not
     * match, i.e. the static demo page and the actuator. It is open, with hardening headers (defence in depth).
     */
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                                + "style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'")))
                .build();
    }
}
