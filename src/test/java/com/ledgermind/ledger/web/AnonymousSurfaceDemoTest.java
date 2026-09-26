package com.ledgermind.ledger.web;

import java.util.Set;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** AUTH-4, demo profile: exactly the five demo pairs are anonymous. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class AnonymousSurfaceDemoTest extends AnonymousSurfaceContract {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Override
    Set<String> expectedAnonymous() {
        return Set.of("POST /api/demo/reset", "POST /api/demo/idempotency", "POST /api/demo/tamper",
                "POST /api/demo/reconcile", "GET /api/demo/audit");
    }
}
