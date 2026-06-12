package com.ledgermind.ledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prueba que el scope per-tool ({@code @PreAuthorize("hasAuthority('SCOPE_ledger.read')")}) se ENFORZA de
 * verdad al INVOCAR la tool MCP — no solo que la anotacion esta puesta. Se inyecta el bean PROXEADO de
 * Spring (el mismo que recibe el MethodToolCallbackProvider), asi que la llamada pasa por la AOP de
 * method-security igual que el dispatch real del MCP server.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@Testcontainers
class LedgerMcpToolsSecurityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerMcpTools tools;

    @Test
    @WithMockUser(authorities = "SCOPE_ledger.read")
    void con_el_scope_correcto_la_auditoria_se_ejecuta() {
        assertThat(tools.verifyJournalIntegrity()).isNotNull();
    }

    @Test
    @WithMockUser(authorities = "SCOPE_otra")
    void sin_el_scope_ledger_read_la_tool_es_denegada() {
        assertThatThrownBy(tools::verifyJournalIntegrity)
                .isInstanceOf(AccessDeniedException.class);
    }
}
