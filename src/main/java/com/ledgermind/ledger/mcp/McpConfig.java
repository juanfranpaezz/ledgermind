package com.ledgermind.ledger.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra las tools del ledger en el MCP server de Spring AI: las de lectura (scope ledger.read)
 * y las de administracion (scope ledger.admin, p. ej. unfreeze_account). Ninguna mueve dinero.
 * El MCP server (starter webmvc) las expone por el protocolo MCP sobre HTTP.
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
