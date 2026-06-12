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
 * Seguridad del MCP server: lo convierte en un OAuth2.1 Resource Server (Spring Security estandar).
 *
 * <p>DOS cadenas a proposito: la del MCP (/mcp) exige un JWT Bearer valido (y el scope por-tool via
 * {@code @PreAuthorize}); la default deja /api y /actuator abiertos. Asi el OAuth cae SOLO sobre el MCP.
 *
 * <p>El JWT se valida con firma + expiracion + AUDIENCIA: un token firmado por el mismo IdP pero emitido
 * para OTRO recurso (sin {@code aud=ledgermind-mcp}) se rechaza. Eso cierra el confused-deputy / token-reuse
 * clasico de OAuth/MCP (espiritu de RFC 8707, resource indicators). El decoder es LAZY (jwk-set-uri): no
 * hace fetch al arranque, evitando el problema con el Authorization Server co-ubicado del perfil demo.
 *
 * <p>NOTA: se descarto org.springaicommunity:mcp-server-security:0.0.6 porque su decoder hace fetch EAGER
 * del issuer al crear el filtro, incompatible con el AS co-ubicado. Se difiere el endpoint RFC 9728.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class McpServerSecurityConfig {

    /** El endpoint MCP es un Resource Server OAuth2.1: sin JWT valido (firma+exp+aud) -> 401. */
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
     * Decoder JWT del resource server: lazy (jwk-set-uri) + validacion de timestamp (default) + AUDIENCIA.
     * Lo toma automaticamente {@code oauth2ResourceServer().jwt()} al ser un bean {@link JwtDecoder}.
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

    /** El resto (API REST y actuator) queda abierto, pero con headers de endurecimiento (defensa en profundidad). */
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
