package com.ledgermind.ledger.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-caller rate limit (fixed window, 30 requests per 10 s) on {@code /api/demo/**} (the five demo endpoints,
 * anonymous only under the {@code demo} profile) and {@code /api/journal/**} (keyed; verify/audit are O(n)).
 * Runs after Spring Security, so only requests that passed authentication are counted (a 401 is not). Each API key
 * (its key_id) has its own window; every anonymous demo caller shares ONE window. So one caller cannot put another
 * key's audits in 429, and the anonymous demo cannot put keyed callers in 429. Windows are keyed by the key_ids of the
 * keys file, so there are at most (keys in the file + 1) of them. /api/transfers, /api/accounts and
 * /api/reconciliation are not rate limited. Paths are matched on the decoded routed path, not on the resolved handler.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_PER_WINDOW = 30;
    private static final long WINDOW_MS = 10_000;

    /** The bucket every unauthenticated (anonymous demo) caller shares. Key buckets are prefixed, so no key_id collides. */
    static final String ANONYMOUS_BUCKET = "anonymous";

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = routedPath(request);
        if ((path.startsWith("/api/demo/") || path.startsWith("/api/journal/")) && !allow(callerBucket())) {
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

    /**
     * The caller's bucket: {@code key:<key_id>} for a request authenticated with an API key, the shared
     * {@link #ANONYMOUS_BUCKET} otherwise (the anonymous demo endpoints).
     */
    static String callerBucket() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()
                || auth.getName() == null) {
            return ANONYMOUS_BUCKET;
        }
        return "key:" + auth.getName();
    }

    private boolean allow(String bucket) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(bucket, b -> new Window(now));
        synchronized (window) {
            if (now - window.start > WINDOW_MS) {
                window.start = now;
                window.count = 0;
            }
            return ++window.count <= MAX_PER_WINDOW;
        }
    }

    private static final class Window {
        private long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }
}
