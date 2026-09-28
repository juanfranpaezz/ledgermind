package com.ledgermind.ledger.web;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Security chain for the REST API: {@code /api/**} needs an {@code X-API-Key} in EVERY profile.
 *
 * <p>Only under the {@code demo} profile, five method+path pairs are reachable anonymously, listed literally (no
 * {@code /api/demo/**} wildcard, so a future demo endpoint is not anonymous by accident). In every other profile the
 * anonymous set is empty.
 *
 * <p>Outside the {@code demo} profile this chain also matches the actuator {@code prometheus} endpoint, so metrics need
 * an X-API-Key there (security gate r3, A2). Under {@code demo} they stay anonymous for the bundled observability stack.
 *
 * <p>Order 0: before the {@code /mcp} chain (1, disjoint matcher) and before the catch-all default chain (2), which
 * keeps serving the static page and the rest of the actuator.
 */
@Configuration
class ApiSecurityConfig {

    static final String[] DEMO_ANONYMOUS_POST = {
            "/api/demo/reset", "/api/demo/idempotency", "/api/demo/tamper", "/api/demo/reconcile"};
    static final String[] DEMO_ANONYMOUS_GET = {"/api/demo/audit"};

    @Bean
    @Order(0)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ApiKeyStore keys, Environment environment)
            throws Exception {
        boolean demo = environment.acceptsProfiles(Profiles.of("demo"));
        return http
                .securityMatchers(matchers -> {
                    matchers.requestMatchers("/api/**");
                    if (!demo) {
                        matchers.requestMatchers(EndpointRequest.to("prometheus"));
                    }
                })
                .authorizeHttpRequests(auth -> {
                    if (demo) {
                        auth.requestMatchers(HttpMethod.POST, DEMO_ANONYMOUS_POST).permitAll()
                                .requestMatchers(HttpMethod.GET, DEMO_ANONYMOUS_GET).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(new ApiKeyAuthenticationFilter(keys), AnonymousAuthenticationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) ->
                        ApiKeyAuthenticationFilter.writeProblem(response, "auth_missing", "API key required",
                                "Send an API key in the X-API-Key header.")))
                .build();
    }
}
