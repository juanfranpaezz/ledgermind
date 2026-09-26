package com.ledgermind.ledger.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate-limit GLOBAL simple (ventana fija, 30 pedidos por 10 s) sobre {@code /api/demo/**} (los cinco endpoints de la
 * demo, anonimos solo bajo el perfil {@code demo}) y {@code /api/journal/**} (con X-API-Key; verify/audit son O(n)).
 * Corre despues de Spring Security: solo cuenta pedidos que pasaron la autenticacion (un 401 no cuenta; los anonimos
 * de la demo si). No es por cliente: un solo llamador, con clave o anonimo en la demo, puede agotar la ventana y dejar
 * en 429 las auditorias de todos. /api/transfers, /api/accounts y /api/reconciliation no tienen limite. El paso
 * siguiente es un contador por clave.
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
        String path = routedPath(request);
        if ((path.startsWith("/api/demo/") || path.startsWith("/api/journal/")) && !allow()) {
            response.setStatus(429);                          // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"detail\":\"Rate limit: demasiadas solicitudes, intenta de nuevo en unos segundos.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The path the container routes: decoded and normalized ({@code %6A} -> {@code j}, dot segments and path
     * parameters removed). Matching the raw {@link HttpServletRequest#getRequestURI()} let
     * {@code /api/%6Aournal/audit} reach the audit handler without being counted (gate measurement, 2026-09-25).
     */
    static String routedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? servletPath : servletPath + pathInfo;
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
