package com.bxv.sysmindagent.agent;

import com.bxv.sysmindagent.SysmindProperties;
import com.bxv.sysmindagent.agent.model.AgentStep;
import com.bxv.sysmindagent.agent.model.ChatEvent;
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
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
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
            For memory or RAM questions, prefer ram_usage unless the user asks for full machine status.
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
        return chat(request, event -> {
        });
    }

    @Override
    public Flux<ChatEvent> stream(ChatRequest request) {
        return Flux.create(sink -> {
            Consumer<ChatEvent> eventSink = event -> {
                if (!sink.isCancelled()) {
                    sink.next(event);
                }
            };

            emit(eventSink, ChatEvent.messageStarted());
            chat(request, eventSink)
                    .subscribe(
                            response -> {
                                emit(eventSink, ChatEvent.messageDelta(response.answer()));
                                emit(eventSink, ChatEvent.messageFinished(response.answer()));
                                sink.complete();
                            },
                            error -> {
                                emit(eventSink, ChatEvent.error(error.getMessage()));
                                sink.complete();
                            }
                    );
        });
    }

    private Mono<ChatResponse> chat(ChatRequest request, Consumer<ChatEvent> eventSink) {
        return mcpClient.listTools()
                .flatMap(tools -> {
                    List<LmStudioMessage> initialMessages = initialMessages(request, tools);
                    return lmStudioClient.complete(initialMessages)
                            .flatMap(rawDecision -> handleDecision(
                                    tools,
                                    initialMessages,
                                    rawDecision,
                                    0,
                                    List.of(),
                                    eventSink
                            ));
                });
    }

    private Mono<ChatResponse> handleDecision(
            List<ToolDefinition> tools,
            List<LmStudioMessage> initialMessages,
            String rawDecision,
            int toolCallCount,
            List<AgentStep> steps,
            Consumer<ChatEvent> eventSink
    ) {
        StructuredAgentDecision decision = parseDecision(rawDecision);
        if (decision.isFinal()) {
            return Mono.just(finalResponse(decision.answer(), steps));
        }
        if (!decision.isToolCall()) {
            return Mono.just(finalResponse("I could not understand the model response.", steps));
        }

        ToolCall toolCall = new ToolCall(decision.toolName(), safeArguments(decision.arguments()));
        if (isDuplicateSuccessfulToolCall(toolCall, steps)) {
            return Mono.just(finalResponse(
                    "I already checked that. The latest result is shown below.",
                    steps
            ));
        }
        emit(eventSink, ChatEvent.toolStarted(toolCall));
        if (toolCallCount >= properties.agent().maxToolCallsPerUserRequest()) {
            ToolResult toolResult = ToolResult.failure(
                    toolCall.toolName(),
                    "Tool call limit reached before executing " + toolCall.toolName() + "."
            );
            emit(eventSink, ChatEvent.toolFinished(toolResult));
            return Mono.just(finalResponse(
                    "I could not complete the request because the tool call limit was reached.",
                    appendToolSteps(steps, toolCall, toolResult)
            ));
        }

        ToolResult validationFailure = validateToolCall(decision, tools);
        if (validationFailure != null) {
            emit(eventSink, ChatEvent.toolFinished(validationFailure));
            return continueAfterToolResult(
                    tools,
                    initialMessages,
                    rawDecision,
                    toolCall,
                    validationFailure,
                    toolCallCount + 1,
                    steps,
                    eventSink
            );
        }

        return executeTool(toolCall)
                .doOnNext(toolResult -> emit(eventSink, ChatEvent.toolFinished(toolResult)))
                .flatMap(toolResult -> continueAfterToolResult(
                        tools,
                        initialMessages,
                        rawDecision,
                        toolCall,
                        toolResult,
                        toolCallCount + 1,
                        steps,
                        eventSink
                ));
    }

    private Mono<ChatResponse> continueAfterToolResult(
            List<ToolDefinition> tools,
            List<LmStudioMessage> initialMessages,
            String rawDecision,
            ToolCall toolCall,
            ToolResult toolResult,
            int toolCallCount,
            List<AgentStep> steps,
            Consumer<ChatEvent> eventSink
    ) {
        List<AgentStep> updatedSteps = appendToolSteps(steps, toolCall, toolResult);
        List<LmStudioMessage> followUpMessages = followUpMessages(
                initialMessages,
                rawDecision,
                toolResult
        );
        return lmStudioClient.complete(followUpMessages)
                .flatMap(nextDecision -> handleDecision(
                        tools,
                        followUpMessages,
                        nextDecision,
                        toolCallCount,
                        updatedSteps,
                        eventSink
                ));
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
                Do not call the same tool again with the same arguments.
                If the tool result contains enough information to answer, respond with {"type":"final","answer":"..."} now.
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
        String decisionText = rawDecision == null ? "" : rawDecision.trim();
        if (decisionText.isBlank()) {
            return StructuredAgentDecision.finalAnswer("The model returned an empty response.");
        }

        try {
            return objectMapper.readValue(decisionText, StructuredAgentDecision.class);
        } catch (JacksonException exception) {
            String jsonObject = extractJsonObject(decisionText);
            if (jsonObject != null) {
                try {
                    return objectMapper.readValue(jsonObject, StructuredAgentDecision.class);
                } catch (JacksonException nestedException) {
                    LOGGER.warn("Could not parse extracted LM Studio JSON decision: {}", nestedException.getMessage());
                }
            }

            LOGGER.warn("LM Studio response was not structured JSON; treating it as final text.");
            return StructuredAgentDecision.finalAnswer(decisionText);
        }
    }

    private String extractJsonObject(String value) {
        String unfenced = stripMarkdownFence(value);
        int start = unfenced.indexOf('{');
        if (start < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = start; index < unfenced.length(); index += 1) {
            char character = unfenced.charAt(index);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (character == '{') {
                depth += 1;
            } else if (character == '}') {
                depth -= 1;
                if (depth == 0) {
                    return unfenced.substring(start, index + 1);
                }
            }
        }

        return null;
    }

    private String stripMarkdownFence(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
            return trimmed;
        }

        return trimmed.substring(firstLineEnd + 1, closingFence).trim();
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

    private boolean isDuplicateSuccessfulToolCall(ToolCall toolCall, List<AgentStep> steps) {
        for (int index = 0; index < steps.size() - 1; index += 1) {
            ToolCall previousToolCall = steps.get(index).toolCall();
            ToolResult previousToolResult = steps.get(index + 1).toolResult();

            if (previousToolCall != null
                    && previousToolResult != null
                    && toolCall.toolName().equals(previousToolCall.toolName())
                    && toolCall.arguments().equals(previousToolCall.arguments())
                    && !previousToolResult.error()) {
                return true;
            }
        }

        return false;
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

    private void emit(Consumer<ChatEvent> eventSink, ChatEvent event) {
        eventSink.accept(event);
    }
}
