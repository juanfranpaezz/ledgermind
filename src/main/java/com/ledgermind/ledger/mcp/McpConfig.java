package com.ledgermind.ledger.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra las tools de solo lectura del ledger en el MCP server de Spring AI.
 * El MCP server (starter webmvc) las expone por el protocolo MCP sobre HTTP.
 */
@Configuration
class McpConfig {

    @Bean
    ToolCallbackProvider ledgerToolCallbacks(LedgerMcpTools ledgerMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ledgerMcpTools)
                .build();
    }
}
