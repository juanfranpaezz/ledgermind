package com.ledgermind.ledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.ledger.JournalCheckpointService;
import com.ledgermind.ledger.LedgerService;
import com.ledgermind.ledger.reconciliation.ReconciliationService;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Tests that the ledger's READ-ONLY tools are exposed by the MCP server with the expected
 * names — including the Layer 3 post-quantum audit. It only inspects definitions (it does not invoke),
 * so it needs neither Spring nor Postgres: it builds the provider the same way as {@code McpConfig}.
 */
class McpToolsRegistrationTest {

    @Test
    void expone_las_tres_tools_de_solo_lectura() {
        LedgerMcpTools tools = new LedgerMcpTools(
                (LedgerService) null, (JournalCheckpointService) null, (ReconciliationService) null);
        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();

        var names = Arrays.stream(provider.getToolCallbacks())
                .map(tc -> tc.getToolDefinition().name())
                .toList();

        assertThat(names).contains("get_balance", "list_transactions",
                "verify_journal_integrity", "explain_reconciliation_discrepancy");
    }
}
