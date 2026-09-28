package com.ledgermind.ledger.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-caller rate limit (fixed window; by default 30 requests per 10 s, set by {@code ledgermind.rate-limit.max-per-window}
 * and {@code ledgermind.rate-limit.window-ms}; a window resets on the first request that arrives more than window-ms
 * after it opened, so up to twice the limit fits in a window-ms span that straddles a boundary) on {@code /api/demo/**} (the five demo endpoints,
 * anonymous only under the {@code demo} profile) and {@code /api/journal/**} (keyed; verify/audit are O(n)).
 * Runs after Spring Security, so only requests that passed authentication are counted (a 401 is not). Each API key
 * (its key_id) has its own window; every anonymous demo caller shares ONE window. So one caller cannot put another
 * key's audits in 429, and the anonymous demo cannot put keyed callers in 429. Windows are keyed by the key_ids of the
 * keys file, so there are at most (keys in the file + 1) of them. /api/transfers, /api/accounts and
 * /api/reconciliation are not rate limited. Paths are matched on the decoded routed path, not on the resolved handler.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final int maxPerWindow;
    private final long windowMs;
    private final LongSupplier clock;

    /** The bucket every unauthenticated (anonymous demo) caller shares. Key buckets are prefixed, so no key_id collides. */
    static final String ANONYMOUS_BUCKET = "anonymous";

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitFilter(@Value("${ledgermind.rate-limit.max-per-window:30}") int maxPerWindow,
                           @Value("${ledgermind.rate-limit.window-ms:10000}") long windowMs) {
        this(maxPerWindow, windowMs, System::currentTimeMillis);
    }

    /** Same filter with an injected millisecond clock, so tests can place requests exactly on a window boundary. */
    RateLimitFilter(int maxPerWindow, long windowMs, LongSupplier clock) {
        if (maxPerWindow < 1 || windowMs < 1) {
            throw new IllegalArgumentException("ledgermind.rate-limit.max-per-window and window-ms must be >= 1");
        }
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = routedPath(request);
        if ((path.startsWith("/api/demo/") || path.startsWith("/api/journal/")) && !allow(callerBucket())) {
            response.setStatus(429);                          // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"detail\":\"Rate limit: too many requests, try again in a few seconds.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The path the container routes: decoded and normalized ({@code %6A} -> {@code j}, dot segments and path
     * parameters removed). Matching the raw {@link HttpServletRequest#getRequestURI()} let
     * {@code /api/%6Aournal/audit} reach the audit handler without being counted.
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
        long now = clock.getAsLong();
        Window window = windows.computeIfAbsent(bucket, b -> new Window(now));
        synchronized (window) {
            if (now - window.start > windowMs) {
                window.start = now;
                window.count = 0;
            }
            return ++window.count <= maxPerWindow;
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
