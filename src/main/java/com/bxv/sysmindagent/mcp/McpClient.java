package com.bxv.sysmindagent.mcp;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

public interface McpClient {

    Mono<McpInitializeResult> initialize();

    Mono<List<ToolDefinition>> listTools();

    Mono<List<ToolDefinition>> refreshTools();

    Mono<ToolCallResult> callTool(String name, Map<String, Object> arguments);
}
