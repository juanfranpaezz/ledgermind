package com.ledgermind.ledger.web;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code X-API-Key} authentication for the {@code /api/**} security chain (not a servlet-level filter: it is created
 * by {@link ApiSecurityConfig}, not registered as a bean).
 *
 * <ul>
 *   <li>No header: the request continues unauthenticated; the chain's allow-list or its entry point
 *       ({@code 401 auth_missing}) decides.</li>
 *   <li>A header that matches no key: {@code 401 auth_invalid}, on every path, allow-listed or not.</li>
 *   <li>A matching key: the principal is its key_id (never the key).</li>
 * </ul>
 * The presented value is never logged, echoed or put into an exception.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    static final String CHALLENGE = "ApiKey header=\"" + HEADER + "\"";

    private final ApiKeyStore keys;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public ApiKeyAuthenticationFilter(ApiKeyStore keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> keyId = keys.authenticate(presented);
        if (keyId.isEmpty()) {
            writeProblem(response, "auth_invalid", "API key not accepted",
                    "The X-API-Key header does not match any configured key.");
            return;
        }
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                keyId.get(), null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))));
        contexts.setContext(context);
        chain.doFilter(request, response);
    }

    /** RFC 7807 body, same codes and challenge as the FastAPI service. Fixed text: nothing from the request. */
    static void writeProblem(HttpServletResponse response, String code, String title, String detail)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", CHALLENGE);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":401,"
                + "\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
