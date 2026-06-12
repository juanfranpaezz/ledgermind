package com.ledgermind.ledger.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad del MCP server: lo convierte en un OAuth2.1 Resource Server (Spring Security estandar).
 *
 * <p>DOS cadenas a proposito: la del MCP (/mcp) exige un JWT Bearer valido (y el scope por-tool via
 * {@code @PreAuthorize}); la default deja /api y /actuator como estaban (abiertos). Asi el OAuth cae
 * SOLO sobre el MCP y no rompe la API REST ni sus tests.
 *
 * <p>NOTA DE DISENO: se descarto org.springaicommunity:mcp-server-security:0.0.6 porque construye su
 * decoder con {@code NimbusJwtDecoder.withIssuerLocation(issuer)}, que hace un fetch EAGER del
 * {@code /.well-known/openid-configuration} al crear el filtro — incompatible con un Authorization
 * Server co-ubicado (el server aun no acepta conexiones) y rompe el arranque/tests. Usamos el resource
 * server estandar con un decoder LAZY (jwk-set-uri), que recien busca el JWKS en la 1ra llamada a /mcp.
 * Se difiere el endpoint RFC 9728 (protected-resource-metadata) que aportaba esa libreria.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class McpServerSecurityConfig {

    /** El endpoint MCP es un Resource Server OAuth2.1: sin JWT valido -> 401. Decoder lazy (jwk-set-uri). */
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

    /** El resto (API REST y actuator) queda como hoy: abierto. La auth NO los toca. */
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
