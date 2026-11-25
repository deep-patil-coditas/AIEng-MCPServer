package com.aieng.productmcpserver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * Turns the registered ToolCallbackProvider into MCP sync tool specifications (as the starter does) and calls
 * tools with a real exchange, returning real CallToolResults.
 */
final class McpToolTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A null exchange makes every call fail, so a real one is built (plan §1, probe). */
    static final McpSyncServerExchange EXCHANGE = new McpSyncServerExchange(new McpAsyncServerExchange(
            "test", null, ClientCapabilities.builder().build(), new Implementation("test", "1"),
            McpTransportContext.EMPTY));

    private final Map<String, SyncToolSpecification> specs = new LinkedHashMap<>();

    McpToolTestSupport(ToolCallbackProvider provider) {
        for (SyncToolSpecification spec : McpToolUtils.toSyncToolSpecification(List.of(provider.getToolCallbacks()))) {
            specs.put(spec.tool().name(), spec);
        }
    }

    Map<String, SyncToolSpecification> specs() {
        return specs;
    }

    /** Calls a tool with arguments given as a JSON object string. */
    CallToolResult call(String tool, String argsJson) {
        try {
            Map<String, Object> args = JSON.readValue(argsJson, new TypeReference<>() {
            });
            return specs.get(tool).callHandler().apply(EXCHANGE, new CallToolRequest(tool, args));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
