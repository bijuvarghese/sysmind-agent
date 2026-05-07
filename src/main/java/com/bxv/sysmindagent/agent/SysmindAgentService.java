package com.bxv.sysmindagent.agent;

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
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SysmindAgentService implements AgentService {

    private static final String RESPONSE_CONTRACT = """
            Respond with JSON only.
            To call a tool, use: {"type":"tool_call","toolName":"machine_status","arguments":{}}
            To answer finally, use: {"type":"final","answer":"Your answer."}
            """;

    private final McpClient mcpClient;
    private final LmStudioClient lmStudioClient;
    private final ObjectMapper objectMapper;

    public SysmindAgentService(McpClient mcpClient, LmStudioClient lmStudioClient, ObjectMapper objectMapper) {
        this.mcpClient = mcpClient;
        this.lmStudioClient = lmStudioClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        return mcpClient.listTools()
                .flatMap(tools -> {
                    List<LmStudioMessage> initialMessages = initialMessages(request, tools);
                    return lmStudioClient.complete(initialMessages)
                            .flatMap(rawDecision -> handleDecision(initialMessages, rawDecision));
                });
    }

    private Mono<ChatResponse> handleDecision(
            List<LmStudioMessage> initialMessages,
            String rawDecision
    ) {
        StructuredAgentDecision decision = parseDecision(rawDecision);
        if (decision.isFinal()) {
            return Mono.just(new ChatResponse(decision.answer()));
        }
        if (!decision.isToolCall()) {
            return Mono.error(new IllegalStateException("LM Studio returned an unsupported agent decision type."));
        }

        ToolCall toolCall = new ToolCall(decision.toolName(), decision.arguments());
        return mcpClient.callTool(toolCall.toolName(), toolCall.arguments())
                .flatMap(toolCallResult -> {
                    ToolResult toolResult = toToolResult(toolCall.toolName(), toolCallResult);
                    List<LmStudioMessage> followUpMessages = followUpMessages(
                            initialMessages,
                            rawDecision,
                            toolResult
                    );
                    return lmStudioClient.complete(followUpMessages)
                            .map(this::parseDecision)
                            .map(finalDecision -> toResponse(finalDecision, toolCall, toolResult));
                });
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

    private ToolResult toToolResult(String toolName, ToolCallResult toolCallResult) {
        JsonNode content = toolCallResult.structuredContent() == null
                ? toolCallResult.content()
                : toolCallResult.structuredContent();
        if (Boolean.TRUE.equals(toolCallResult.isError())) {
            return ToolResult.failure(toolName, content == null ? "Tool call failed." : content.toString());
        }
        return ToolResult.success(toolName, content);
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

    private ChatResponse toResponse(StructuredAgentDecision finalDecision, ToolCall toolCall, ToolResult toolResult) {
        if (!finalDecision.isFinal()) {
            throw new IllegalStateException("LM Studio did not return a final answer after tool execution.");
        }
        return new ChatResponse(
                finalDecision.answer(),
                List.of(
                        AgentStep.toolCall(toolCall),
                        AgentStep.toolResult(toolResult),
                        AgentStep.finalAnswer(finalDecision.answer())
                )
        );
    }
}
