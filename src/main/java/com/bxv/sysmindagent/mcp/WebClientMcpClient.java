package com.bxv.sysmindagent.mcp;

import com.bxv.sysmindagent.SysmindProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebClientMcpClient implements McpClient {

    static final String PROTOCOL_VERSION = "2025-06-18";

    private final WebClient webClient;
    private final SysmindProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicReference<List<ToolDefinition>> cachedTools = new AtomicReference<>();

    public WebClientMcpClient(
            @Qualifier("mcpWebClient") WebClient webClient,
            SysmindProperties properties,
            ObjectMapper objectMapper
    ) {
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<McpInitializeResult> initialize() {
        Map<String, Object> params = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                        "name", "sysmind-agent",
                        "version", "0.0.1-SNAPSHOT"
                )
        );

        return post("initialize", params)
                .map(result -> convert(result, McpInitializeResult.class));
    }

    @Override
    public Mono<List<ToolDefinition>> listTools() {
        List<ToolDefinition> tools = cachedTools.get();
        if (tools != null) {
            return Mono.just(tools);
        }

        return refreshTools();
    }

    @Override
    public Mono<List<ToolDefinition>> refreshTools() {
        return post("tools/list", Map.of())
                .map(result -> convert(result, ToolsListResult.class))
                .map(ToolsListResult::tools)
                .map(tools -> tools == null ? List.<ToolDefinition>of() : List.copyOf(tools))
                .doOnNext(cachedTools::set);
    }

    @Override
    public Mono<ToolCallResult> callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> params = Map.of(
                "name", name,
                "arguments", arguments == null ? Map.of() : arguments
        );

        return listTools()
                .then(post("tools/call", params))
                .map(result -> convert(result, ToolCallResult.class));
    }

    private Mono<JsonNode> post(String method, Object params) {
        JsonRpcRequest request = new JsonRpcRequest(requestIds.incrementAndGet(), method, params);

        return webClient.post()
                .uri(properties.mcp().endpointPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonRpcResponse.class)
                .flatMap(this::unwrap);
    }

    private Mono<JsonNode> unwrap(JsonRpcResponse response) {
        if (response.error() != null) {
            return Mono.error(new JsonRpcException(response.error()));
        }
        return Mono.justOrEmpty(response.result());
    }

    private <T> T convert(JsonNode result, Class<T> type) {
        try {
            return objectMapper.treeToValue(result, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to parse MCP response as " + type.getSimpleName() + ".", exception);
        }
    }
}
