package com.bxv.sysmindagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AgentModelsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chatRequestCreatesUserMessageFromSingleMessage() {
        ChatRequest request = new ChatRequest("Check my machine status.");

        assertThat(request.message()).isEqualTo("Check my machine status.");
        assertThat(request.messages()).containsExactly(ChatMessage.user("Check my machine status."));
    }

    @Test
    void chatRequestCreatesUserMessageWhenJsonOnlyProvidesMessage() throws Exception {
        ChatRequest request = objectMapper.readValue("""
                {
                  "message": "Check disk status."
                }
                """, ChatRequest.class);

        assertThat(request.message()).isEqualTo("Check disk status.");
        assertThat(request.messages()).containsExactly(ChatMessage.user("Check disk status."));
    }

    @Test
    void chatResponseIncludesFinalAnswerStepByDefault() {
        ChatResponse response = new ChatResponse("Your machine looks healthy.");

        assertThat(response.answer()).isEqualTo("Your machine looks healthy.");
        assertThat(response.steps()).containsExactly(AgentStep.finalAnswer("Your machine looks healthy."));
    }

    @Test
    void toolCallUsesEmptyArgumentsWhenMissing() {
        ToolCall toolCall = new ToolCall("machine_status", null);

        assertThat(toolCall.arguments()).isEmpty();
    }

    @Test
    void toolDefinitionCanBeAdaptedFromMcpToolDefinition() throws Exception {
        var inputSchema = objectMapper.readTree("""
                {
                  "type": "object"
                }
                """);
        var mcpTool = new com.bxv.sysmindagent.mcp.ToolDefinition(
                "machine_status",
                "Returns machine health.",
                inputSchema,
                null
        );

        ToolDefinition toolDefinition = ToolDefinition.fromMcp(mcpTool);

        assertThat(toolDefinition.name()).isEqualTo("machine_status");
        assertThat(toolDefinition.description()).isEqualTo("Returns machine health.");
        assertThat(toolDefinition.inputSchema().get("type").asText()).isEqualTo("object");
    }

    @Test
    void stepsAndEventsCaptureToolLifecycle() {
        ToolCall toolCall = new ToolCall("machine_status", Map.of());
        ToolResult toolResult = ToolResult.success("machine_status", objectMapper.valueToTree(List.of("ok")));

        assertThat(AgentStep.toolCall(toolCall).type()).isEqualTo("tool_call");
        assertThat(AgentStep.toolResult(toolResult).toolResult()).isEqualTo(toolResult);
        assertThat(ChatEvent.toolCall(toolCall).toolCall()).isEqualTo(toolCall);
        assertThat(ChatEvent.toolResult(toolResult).toolResult()).isEqualTo(toolResult);
        assertThat(ChatEvent.messageStarted().type()).isEqualTo("message.started");
        assertThat(ChatEvent.toolStarted(toolCall).type()).isEqualTo("tool.started");
        assertThat(ChatEvent.toolFinished(toolResult).type()).isEqualTo("tool.finished");
        assertThat(ChatEvent.messageDelta("ok").type()).isEqualTo("message.delta");
        assertThat(ChatEvent.messageFinished("ok").type()).isEqualTo("message.finished");
    }
}
