package com.ledgermind.ledger.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate-limit GLOBAL simple (ventana fija) sobre los endpoints destructivos o caros que estan abiertos:
 * {@code /api/demo/**} (reset/tamper) y {@code /api/journal/**} (verify/audit son O(n)). Acota el abuso
 * en bucle (DoS / wipe) sin requerir auth, que rompria el caracter publico de la demo. En produccion la
 * version seria un token-bucket por cliente; aca un contador global alcanza para frenar el loop.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_PER_WINDOW = 30;
    private static final long WINDOW_MS = 10_000;

    private long windowStart = System.currentTimeMillis();
    private int count = 0;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ((path.startsWith("/api/demo/") || path.startsWith("/api/journal/")) && !allow()) {
            response.setStatus(429);                          // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"detail\":\"Rate limit: demasiadas solicitudes, intenta de nuevo en unos segundos.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allow() {
        long now = System.currentTimeMillis();
        if (now - windowStart > WINDOW_MS) {
            windowStart = now;
            count = 0;
        }
        return ++count <= MAX_PER_WINDOW;
    }
}
