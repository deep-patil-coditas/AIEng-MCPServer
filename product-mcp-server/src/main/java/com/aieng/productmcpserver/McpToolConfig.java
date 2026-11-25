package com.aieng.productmcpserver;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the product tools with the MCP server (MCP-SERVER-S02). */
@Configuration
public class McpToolConfig {

    @Bean
    ToolCallbackProvider productToolCallbacks(ProductTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
