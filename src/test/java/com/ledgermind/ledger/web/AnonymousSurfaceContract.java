package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * AUTH-4: the anonymous surface of {@code /api} equals the allow-list EXACTLY. Every (method, pattern) that Spring MVC
 * registered under {@code /api} is called without a key; the set of answers other than 401 must equal
 * {@link #expectedAnonymous()}.
 */
abstract class AnonymousSurfaceContract {

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    abstract Set<String> expectedAnonymous();

    @Test
    void anonymousSurfaceEqualsTheAllowList() throws Exception {
        ApiTestHttp h = new ApiTestHttp(port);
        Set<String> all = new TreeSet<>();
        Set<String> reachable = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api")) {
                    continue;
                }
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    String pair = m.name() + " " + pattern;
                    all.add(pair);
                    String path = pattern.replaceAll("\\{[^}]+}", "x");
                    String body = (m == RequestMethod.POST || m == RequestMethod.PUT) ? "{}" : null;
                    int status = h.send(m.name(), path, null, body).statusCode();
                    if (status != 401) {
                        reachable.add(pair);
                    }
                }
            }
        }
        System.out.println("[AUTH-4] registered=" + all);
        System.out.println("[AUTH-4] anonymous-reachable=" + reachable);
        // Control: the enumeration is live (it sees the keyed endpoints), so an empty result is not a blind query.
        assertThat(all).contains("GET /api/journal/audit", "POST /api/transfers", "GET /api/accounts/{address}");
        assertThat(reachable).isEqualTo(expectedAnonymous());
    }
}
