package com.ledgermind.ledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.JournalCheckpointService;
import com.ledgermind.ledger.LedgerService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Prueba que las tools de SOLO LECTURA del ledger quedan expuestas por el MCP server con los nombres
 * esperados — incluida la auditoria post-cuantica de Capa 3. Solo inspecciona definiciones (no invoca),
 * asi que no necesita Spring ni Postgres: construye el provider igual que {@code McpConfig}.
 */
class McpToolsRegistrationTest {

    @Test
    void expone_las_tres_tools_de_solo_lectura() {
        LedgerMcpTools tools = new LedgerMcpTools((LedgerService) null, (JournalCheckpointService) null);
        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();

        var names = Arrays.stream(provider.getToolCallbacks())
                .map(tc -> tc.getToolDefinition().name())
                .toList();

        assertThat(names).contains("get_balance", "list_transactions", "verify_journal_integrity");
    }
}
