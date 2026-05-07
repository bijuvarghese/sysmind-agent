package com.bxv.sysmindagent.agent;

import com.bxv.sysmindagent.SysmindProperties;
import com.bxv.sysmindagent.agent.model.AgentStep;
import com.bxv.sysmindagent.agent.model.ChatMessage;
import com.bxv.sysmindagent.agent.model.ChatRequest;
import com.bxv.sysmindagent.agent.model.ChatResponse;
import com.bxv.sysmindagent.agent.model.ToolCall;
import com.bxv.sysmindagent.agent.model.ToolResult;
import com.bxv.sysmindagent.lmstudio.LmStudioClient;
import com.bxv.sysmindagent.lmstudio.LmStudioMessage;
import com.bxv.sysmindagent.mcp.McpClient;
import com.bxv.sysmindagent.mcp.ToolCallResult;
import com.bxv.sysmindagent.mcp.ToolDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SysmindAgentService implements AgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SysmindAgentService.class);

    private static final String RESPONSE_CONTRACT = """
            Respond with JSON only.
            To call a tool, use: {"type":"tool_call","toolName":"<tool_name_from_available_tools>","arguments":{}}
            Choose toolName only from the Available tools list.
            Do not copy placeholder text.
            Do not invent tool names.
            To answer finally, use: {"type":"final","answer":"Your answer."}
            """;

    private final McpClient mcpClient;
    private final LmStudioClient lmStudioClient;
    private final ObjectMapper objectMapper;
    private final SysmindProperties properties;

    public SysmindAgentService(
            McpClient mcpClient,
            LmStudioClient lmStudioClient,
            ObjectMapper objectMapper,
            SysmindProperties properties
    ) {
        this.mcpClient = mcpClient;
        this.lmStudioClient = lmStudioClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        return mcpClient.listTools()
                .flatMap(tools -> {
                    List<LmStudioMessage> initialMessages = initialMessages(request, tools);
                    return lmStudioClient.complete(initialMessages)
                            .flatMap(rawDecision -> handleDecision(tools, initialMessages, rawDecision, 0, List.of()));
                });
    }

    private Mono<ChatResponse> handleDecision(
            List<ToolDefinition> tools,
            List<LmStudioMessage> initialMessages,
            String rawDecision,
            int toolCallCount,
            List<AgentStep> steps
    ) {
        StructuredAgentDecision decision = parseDecision(rawDecision);
        if (decision.isFinal()) {
            return Mono.just(finalResponse(decision.answer(), steps));
        }
        if (!decision.isToolCall()) {
            return Mono.just(finalResponse("I could not understand the model response.", steps));
        }

        ToolCall toolCall = new ToolCall(decision.toolName(), safeArguments(decision.arguments()));
        if (toolCallCount >= properties.agent().maxToolCallsPerUserRequest()) {
            ToolResult toolResult = ToolResult.failure(
                    toolCall.toolName(),
                    "Tool call limit reached before executing " + toolCall.toolName() + "."
            );
            return Mono.just(finalResponse(
                    "I could not complete the request because the tool call limit was reached.",
                    appendToolSteps(steps, toolCall, toolResult)
            ));
        }

        ToolResult validationFailure = validateToolCall(decision, tools);
        if (validationFailure != null) {
            return continueAfterToolResult(
                    tools,
                    initialMessages,
                    rawDecision,
                    toolCall,
                    validationFailure,
                    toolCallCount + 1,
                    steps
            );
        }

        return executeTool(toolCall)
                .flatMap(toolResult -> continueAfterToolResult(
                        tools,
                        initialMessages,
                        rawDecision,
                        toolCall,
                        toolResult,
                        toolCallCount + 1,
                        steps
                ));
    }

    private Mono<ChatResponse> continueAfterToolResult(
            List<ToolDefinition> tools,
            List<LmStudioMessage> initialMessages,
            String rawDecision,
            ToolCall toolCall,
            ToolResult toolResult,
            int toolCallCount,
            List<AgentStep> steps
    ) {
        List<AgentStep> updatedSteps = appendToolSteps(steps, toolCall, toolResult);
        List<LmStudioMessage> followUpMessages = followUpMessages(
                initialMessages,
                rawDecision,
                toolResult
        );
        return lmStudioClient.complete(followUpMessages)
                .flatMap(nextDecision -> handleDecision(tools, followUpMessages, nextDecision, toolCallCount, updatedSteps));
    }

    private List<LmStudioMessage> initialMessages(ChatRequest request, List<ToolDefinition> tools) {
        List<LmStudioMessage> messages = new ArrayList<>();
        messages.add(new LmStudioMessage("system", systemPrompt(tools)));
        request.messages().stream()
                .map(this::toLmStudioMessage)
                .forEach(messages::add);
        return List.copyOf(messages);
    }

    private List<LmStudioMessage> followUpMessages(
            List<LmStudioMessage> initialMessages,
            String rawDecision,
            ToolResult toolResult
    ) {
        List<LmStudioMessage> messages = new ArrayList<>(initialMessages);
        messages.add(new LmStudioMessage("assistant", rawDecision));
        messages.add(new LmStudioMessage("tool", toolResultMessage(toolResult)));
        messages.add(new LmStudioMessage("system", """
                Use the tool result to produce the final response.
                %s
                """.formatted(RESPONSE_CONTRACT)));
        return List.copyOf(messages);
    }

    private String systemPrompt(List<ToolDefinition> tools) {
        return """
                You are SysMind agent. Decide whether to answer directly or call one available tool.

                Available tools:
                %s

                %s
                """.formatted(toolDescriptions(tools), RESPONSE_CONTRACT);
    }

    private String toolDescriptions(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return "No tools are available.";
        }

        StringBuilder builder = new StringBuilder();
        for (ToolDefinition tool : tools) {
            builder.append("- ")
                    .append(tool.name())
                    .append(": ")
                    .append(tool.description() == null ? "" : tool.description())
                    .append(" inputSchema=")
                    .append(tool.inputSchema() == null ? "{}" : tool.inputSchema().toString())
                    .append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private LmStudioMessage toLmStudioMessage(ChatMessage message) {
        return new LmStudioMessage(message.role(), message.content());
    }

    private StructuredAgentDecision parseDecision(String rawDecision) {
        try {
            return objectMapper.readValue(rawDecision, StructuredAgentDecision.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("LM Studio response was not valid structured agent JSON.", exception);
        }
    }

    private ToolResult validateToolCall(StructuredAgentDecision decision, List<ToolDefinition> tools) {
        if (decision.toolName() == null || decision.toolName().isBlank()) {
            return ToolResult.failure("unknown", "Tool call did not include a toolName.");
        }
        if ("<tool_name_from_available_tools>".equals(decision.toolName())) {
            return ToolResult.failure(decision.toolName(), "Tool call used the placeholder tool name.");
        }
        List<ToolDefinition> availableTools = tools == null ? List.of() : tools;
        if (availableTools.stream().noneMatch(tool -> decision.toolName().equals(tool.name()))) {
            return ToolResult.failure(decision.toolName(), "Tool is not available: " + decision.toolName());
        }
        if (decision.arguments() != null && !(decision.arguments() instanceof Map<?, ?>)) {
            return ToolResult.failure(decision.toolName(), "Tool arguments must be a JSON object.");
        }
        if (!isJsonCompatible(decision.arguments())) {
            return ToolResult.failure(decision.toolName(), "Tool arguments contain non-JSON values.");
        }
        return null;
    }

    private Map<String, Object> safeArguments(Object arguments) {
        if (!(arguments instanceof Map<?, ?> map)) {
            return Map.of();
        }

        Map<String, Object> safeArguments = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                safeArguments.put(key, entry.getValue());
            }
        }
        return safeArguments;
    }

    private boolean isJsonCompatible(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return true;
        }
        if (value instanceof List<?> list) {
            return list.stream().allMatch(this::isJsonCompatible);
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .allMatch(entry -> entry.getKey() instanceof String && isJsonCompatible(entry.getValue()));
        }
        return false;
    }

    private Mono<ToolResult> executeTool(ToolCall toolCall) {
        Duration timeout = properties.agent().toolTimeout();
        long startedAtNanos = System.nanoTime();
        LOGGER.info("Starting MCP tool call toolName={} arguments={}", toolCall.toolName(), toolCall.arguments());

        return mcpClient.callTool(toolCall.toolName(), toolCall.arguments())
                .timeout(timeout)
                .map(toolCallResult -> {
                    ToolResult toolResult = toToolResult(toolCall.toolName(), toolCallResult);
                    logToolCompletion(toolCall, toolResult, startedAtNanos);
                    return toolResult;
                })
                .onErrorResume(error -> {
                    long durationMs = elapsedMillis(startedAtNanos);
                    LOGGER.warn(
                            "MCP tool call failed toolName={} durationMs={} error={}",
                            toolCall.toolName(),
                            durationMs,
                            error.toString()
                    );
                    return Mono.just(ToolResult.failure(toolCall.toolName(), error.getMessage()));
                });
    }

    private ToolResult toToolResult(String toolName, ToolCallResult toolCallResult) {
        JsonNode content = toolCallResult.structuredContent() == null
                ? toolCallResult.content()
                : toolCallResult.structuredContent();
        if (Boolean.TRUE.equals(toolCallResult.isError())) {
            return ToolResult.failure(toolName, content == null ? "Tool call failed." : content.toString());
        }
        return ToolResult.success(toolName, content);
    }

    private void logToolCompletion(ToolCall toolCall, ToolResult toolResult, long startedAtNanos) {
        long durationMs = elapsedMillis(startedAtNanos);
        if (toolResult.error()) {
            LOGGER.warn(
                    "MCP tool call returned error toolName={} durationMs={} error={}",
                    toolCall.toolName(),
                    durationMs,
                    toolResult.errorMessage()
            );
            return;
        }
        LOGGER.info("MCP tool call succeeded toolName={} durationMs={}", toolCall.toolName(), durationMs);
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private String toolResultMessage(ToolResult toolResult) {
        if (toolResult.error()) {
            return """
                    Tool %s failed:
                    %s
                    """.formatted(toolResult.toolName(), toolResult.errorMessage());
        }
        return """
                Tool %s result:
                %s
                """.formatted(toolResult.toolName(), toolResult.content() == null ? "null" : toolResult.content().toString());
    }

    private ChatResponse finalResponse(String answer, List<AgentStep> steps) {
        List<AgentStep> responseSteps = new ArrayList<>(steps);
        responseSteps.add(AgentStep.finalAnswer(answer));
        return new ChatResponse(
                answer,
                responseSteps
        );
    }

    private List<AgentStep> appendToolSteps(List<AgentStep> steps, ToolCall toolCall, ToolResult toolResult) {
        List<AgentStep> updatedSteps = new ArrayList<>(steps);
        updatedSteps.add(AgentStep.toolCall(toolCall));
        updatedSteps.add(AgentStep.toolResult(toolResult));
        return List.copyOf(updatedSteps);
    }
}
