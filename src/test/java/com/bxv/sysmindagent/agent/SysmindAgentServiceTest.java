package com.bxv.sysmindagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bxv.sysmindagent.agent.model.AgentStep;
import com.bxv.sysmindagent.agent.model.ChatRequest;
import com.bxv.sysmindagent.lmstudio.LmStudioClient;
import com.bxv.sysmindagent.lmstudio.LmStudioMessage;
import com.bxv.sysmindagent.mcp.McpClient;
import com.bxv.sysmindagent.mcp.McpInitializeResult;
import com.bxv.sysmindagent.mcp.ToolCallResult;
import com.bxv.sysmindagent.mcp.ToolDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class SysmindAgentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsDirectFinalAnswerFromLmStudio() {
        FakeLmStudioClient lmStudioClient = new FakeLmStudioClient(List.of("""
                {
                  "type": "final",
                  "answer": "I can help with that."
                }
                """));
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(machineStatusTool()));
        AgentService agentService = new SysmindAgentService(mcpClient, lmStudioClient, objectMapper);

        StepVerifier.create(agentService.chat(new ChatRequest("Hello.")))
                .assertNext(response -> {
                    assertThat(response.answer()).isEqualTo("I can help with that.");
                    assertThat(response.steps()).containsExactly(AgentStep.finalAnswer("I can help with that."));
                })
                .verifyComplete();

        assertThat(mcpClient.listToolsCalls).isEqualTo(1);
        assertThat(mcpClient.toolCalls).isEmpty();
        assertThat(lmStudioClient.requests).hasSize(1);
        assertThat(lmStudioClient.requests.getFirst().getFirst().content()).contains("machine_status");
    }

    @Test
    void callsMcpToolAndAsksLmStudioForFinalAnswer() throws Exception {
        FakeLmStudioClient lmStudioClient = new FakeLmStudioClient(List.of(
                """
                        {
                          "type": "tool_call",
                          "toolName": "machine_status",
                          "arguments": {}
                        }
                        """,
                """
                        {
                          "type": "final",
                          "answer": "Your machine looks healthy."
                        }
                        """
        ));
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(machineStatusTool()));
        mcpClient.toolResult = new ToolCallResult(
                objectMapper.readTree("""
                        [
                          {
                            "type": "text",
                            "text": "RAM and disk are healthy."
                          }
                        ]
                        """),
                null,
                false
        );
        AgentService agentService = new SysmindAgentService(mcpClient, lmStudioClient, objectMapper);

        StepVerifier.create(agentService.chat(new ChatRequest("Check my machine status.")))
                .assertNext(response -> {
                    assertThat(response.answer()).isEqualTo("Your machine looks healthy.");
                    assertThat(response.steps()).extracting(AgentStep::type)
                            .containsExactly("tool_call", "tool_result", "final");
                    assertThat(response.steps().getFirst().toolCall().toolName()).isEqualTo("machine_status");
                })
                .verifyComplete();

        assertThat(mcpClient.toolCalls).containsExactly(new RecordedToolCall("machine_status", Map.of()));
        assertThat(lmStudioClient.requests).hasSize(2);
        assertThat(lmStudioClient.requests.get(1)).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.content()).contains("RAM and disk are healthy.");
        });
    }

    private ToolDefinition machineStatusTool() {
        return new ToolDefinition(
                "machine_status",
                "Returns computer name, OS, CPU, RAM, storage, and uptime details.",
                objectMapper.createObjectNode().put("type", "object"),
                null
        );
    }

    private static class FakeLmStudioClient implements LmStudioClient {

        private final Queue<String> responses;
        private final List<List<LmStudioMessage>> requests = new ArrayList<>();

        FakeLmStudioClient(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public Mono<String> complete(List<LmStudioMessage> messages) {
            requests.add(List.copyOf(messages));
            return Mono.just(responses.remove());
        }
    }

    private static class FakeMcpClient implements McpClient {

        private final List<ToolDefinition> tools;
        private final List<RecordedToolCall> toolCalls = new ArrayList<>();
        private int listToolsCalls;
        private ToolCallResult toolResult;

        FakeMcpClient(List<ToolDefinition> tools) {
            this.tools = tools;
        }

        @Override
        public Mono<McpInitializeResult> initialize() {
            return Mono.empty();
        }

        @Override
        public Mono<List<ToolDefinition>> listTools() {
            listToolsCalls++;
            return Mono.just(tools);
        }

        @Override
        public Mono<List<ToolDefinition>> refreshTools() {
            return Mono.just(tools);
        }

        @Override
        public Mono<ToolCallResult> callTool(String name, Map<String, Object> arguments) {
            toolCalls.add(new RecordedToolCall(name, arguments));
            return Mono.just(toolResult);
        }
    }

    private record RecordedToolCall(String name, Map<String, Object> arguments) {

        private RecordedToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }
}
