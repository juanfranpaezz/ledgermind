package com.ledgermind.ledger.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the ledger tools in the Spring AI MCP server: the read tools (scope ledger.read)
 * and the administration tools (scope ledger.admin, e.g. unfreeze_account). None of them moves money.
 * The MCP server (webmvc starter) exposes them over the MCP protocol on HTTP.
 */
@Configuration
class McpConfig {

    @Bean
    ToolCallbackProvider ledgerToolCallbacks(LedgerMcpTools ledgerMcpTools, LedgerAdminMcpTools adminTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ledgerMcpTools, adminTools)
                .build();
    }
}
