package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link RateLimitFilter} as a unit, with an injected clock: the window semantics the README and ADR 0004 state
 * (fixed window; up to twice the limit across a boundary; nested {@code /api/journal/**} paths counted). No Spring
 * context and no sleeping: every request is placed at an exact millisecond. Callers here are anonymous (empty
 * security context), so they all share the anonymous window.
 */
class RateLimitFilterClockTest {

    private static final FilterChain PASS = (request, response) -> { };

    private final AtomicLong now = new AtomicLong();

    @BeforeEach
    void anonymousCaller() {
        SecurityContextHolder.clearContext();
    }

    private static int status(RateLimitFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, PASS);
        return response.getStatus();
    }

    private static int served(RateLimitFilter filter, String path, int requests) throws Exception {
        int served = 0;
        for (int i = 0; i < requests; i++) {
            if (status(filter, path) == 200) {
                served++;
            }
        }
        return served;
    }

    @Test
    void default_fixed_window_allows_up_to_twice_the_limit_across_a_boundary() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(30, 10_000, now::get);

        now.set(0);
        assertThat(status(filter, "/api/demo/audit")).as("request 1 opens the window at t=0").isEqualTo(200);
        now.set(9_999);
        assertThat(served(filter, "/api/demo/audit", 29)).as("requests 2..30 at 9.999 s").isEqualTo(29);
        assertThat(status(filter, "/api/demo/audit")).as("request 31 inside the same window").isEqualTo(429);
        now.set(10_000);
        assertThat(status(filter, "/api/journal/checkpoint/verify"))
                .as("t = start + window-ms is still the same window; nested journal path counted").isEqualTo(429);

        now.set(10_001);
        assertThat(served(filter, "/api/journal/audit", 30)).as("a fresh window 2 ms later").isEqualTo(30);
        assertThat(status(filter, "/api/demo/audit")).as("31st in the new window").isEqualTo(429);
        // 29 + 30 = 59 served between t=9.999 s and t=10.001 s: the fixed window allows ~2x the limit in a short span.
    }

    @Test
    void paths_outside_demo_and_journal_are_not_counted() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 10_000, now::get);
        assertThat(status(filter, "/api/demo/audit")).isEqualTo(200);
        assertThat(status(filter, "/api/demo/audit")).as("limit reached: fires").isEqualTo(429);
        assertThat(served(filter, "/api/transfers", 5)).as("not a limited path: quiet").isEqualTo(5);
    }
}
