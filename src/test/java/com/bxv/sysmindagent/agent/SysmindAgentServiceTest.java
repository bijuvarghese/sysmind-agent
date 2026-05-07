package com.bxv.sysmindagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bxv.sysmindagent.SysmindProperties;
import com.bxv.sysmindagent.agent.model.AgentStep;
import com.bxv.sysmindagent.agent.model.ChatRequest;
import com.bxv.sysmindagent.lmstudio.LmStudioClient;
import com.bxv.sysmindagent.lmstudio.LmStudioMessage;
import com.bxv.sysmindagent.mcp.McpClient;
import com.bxv.sysmindagent.mcp.McpInitializeResult;
import com.bxv.sysmindagent.mcp.ToolCallResult;
import com.bxv.sysmindagent.mcp.ToolDefinition;
import java.time.Duration;
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
        AgentService agentService = new SysmindAgentService(mcpClient, lmStudioClient, objectMapper, properties());

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
        AgentService agentService = new SysmindAgentService(mcpClient, lmStudioClient, objectMapper, properties());

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

    @Test
    void sendsUnknownToolFailureBackToLmStudioWithoutCallingMcp() {
        FakeLmStudioClient lmStudioClient = new FakeLmStudioClient(List.of(
                """
                        {
                          "type": "tool_call",
                          "toolName": "missing_tool",
                          "arguments": {}
                        }
                        """,
                """
                        {
                          "type": "final",
                          "answer": "I cannot use that tool."
                        }
                        """
        ));
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(machineStatusTool()));
        AgentService agentService = new SysmindAgentService(mcpClient, lmStudioClient, objectMapper, properties());

        StepVerifier.create(agentService.chat(new ChatRequest("Use the missing tool.")))
                .assertNext(response -> {
                    assertThat(response.answer()).isEqualTo("I cannot use that tool.");
                    assertThat(response.steps()).extracting(AgentStep::type)
                            .containsExactly("tool_call", "tool_result", "final");
                    assertThat(response.steps().get(1).toolResult().error()).isTrue();
                    assertThat(response.steps().get(1).toolResult().errorMessage()).contains("not available");
                })
                .verifyComplete();

        assertThat(mcpClient.toolCalls).isEmpty();
        assertThat(lmStudioClient.requests).hasSize(2);
        assertThat(lmStudioClient.requests.get(1)).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.content()).contains("not available");
        });
    }

    @Test
    void convertsMcpFailureToToolResultFailure() {
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
                          "answer": "The tool failed, so I cannot check that right now."
                        }
                        """
        ));
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(machineStatusTool()));
        mcpClient.error = new IllegalStateException("MCP backend unavailable");
        AgentService agentService = new SysmindAgentService(mcpClient, lmStudioClient, objectMapper, properties());

        StepVerifier.create(agentService.chat(new ChatRequest("Check my machine status.")))
                .assertNext(response -> {
                    assertThat(response.answer()).contains("tool failed");
                    assertThat(response.steps().get(1).toolResult().error()).isTrue();
                    assertThat(response.steps().get(1).toolResult().errorMessage()).contains("MCP backend unavailable");
                })
                .verifyComplete();

        assertThat(mcpClient.toolCalls).containsExactly(new RecordedToolCall("machine_status", Map.of()));
        assertThat(lmStudioClient.requests.get(1)).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("tool");
            assertThat(message.content()).contains("MCP backend unavailable");
        });
    }

    @Test
    void rejectsNonObjectToolArguments() {
        FakeLmStudioClient lmStudioClient = new FakeLmStudioClient(List.of(
                """
                        {
                          "type": "tool_call",
                          "toolName": "machine_status",
                          "arguments": []
                        }
                        """,
                """
                        {
                          "type": "final",
                          "answer": "The arguments were invalid."
                        }
                        """
        ));
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(machineStatusTool()));
        AgentService agentService = new SysmindAgentService(mcpClient, lmStudioClient, objectMapper, properties());

        StepVerifier.create(agentService.chat(new ChatRequest("Check my machine status.")))
                .assertNext(response -> {
                    assertThat(response.answer()).isEqualTo("The arguments were invalid.");
                    assertThat(response.steps().get(1).toolResult().error()).isTrue();
                    assertThat(response.steps().get(1).toolResult().errorMessage()).contains("JSON object");
                })
                .verifyComplete();

        assertThat(mcpClient.toolCalls).isEmpty();
    }

    @Test
    void convertsToolTimeoutToToolResultFailure() {
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
                          "answer": "The tool timed out."
                        }
                        """
        ));
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(machineStatusTool()));
        mcpClient.neverComplete = true;
        AgentService agentService = new SysmindAgentService(
                mcpClient,
                lmStudioClient,
                objectMapper,
                properties(Duration.ofMillis(1), 3)
        );

        StepVerifier.create(agentService.chat(new ChatRequest("Check my machine status.")))
                .assertNext(response -> {
                    assertThat(response.answer()).isEqualTo("The tool timed out.");
                    assertThat(response.steps().get(1).toolResult().error()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void enforcesMaxToolCallCount() {
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
                          "type": "tool_call",
                          "toolName": "machine_status",
                          "arguments": {}
                        }
                        """
        ));
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(machineStatusTool()));
        mcpClient.toolResult = new ToolCallResult(
                objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("text", "ok")),
                null,
                false
        );
        AgentService agentService = new SysmindAgentService(
                mcpClient,
                lmStudioClient,
                objectMapper,
                properties(Duration.ofSeconds(10), 1)
        );

        StepVerifier.create(agentService.chat(new ChatRequest("Check my machine status.")))
                .assertNext(response -> {
                    assertThat(response.answer()).contains("tool call limit");
                    assertThat(response.steps()).extracting(AgentStep::type)
                            .containsExactly("tool_call", "tool_result", "tool_call", "tool_result", "final");
                    assertThat(response.steps().get(3).toolResult().errorMessage()).contains("Tool call limit");
                })
                .verifyComplete();

        assertThat(mcpClient.toolCalls).containsExactly(new RecordedToolCall("machine_status", Map.of()));
    }

    @Test
    void promptUsesPlaceholderInsteadOfHardCodedToolExample() {
        FakeLmStudioClient lmStudioClient = new FakeLmStudioClient(List.of("""
                {
                  "type": "final",
                  "answer": "ok"
                }
                """));
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(machineStatusTool()));
        AgentService agentService = new SysmindAgentService(mcpClient, lmStudioClient, objectMapper, properties());

        StepVerifier.create(agentService.chat(new ChatRequest("Hello.")))
                .expectNextCount(1)
                .verifyComplete();

        String systemPrompt = lmStudioClient.requests.getFirst().getFirst().content();
        assertThat(systemPrompt).contains("\"toolName\":\"<tool_name_from_available_tools>\"");
        assertThat(systemPrompt).doesNotContain("\"toolName\":\"machine_status\"");
    }

    private ToolDefinition machineStatusTool() {
        return new ToolDefinition(
                "machine_status",
                "Returns computer name, OS, CPU, RAM, storage, and uptime details.",
                objectMapper.createObjectNode().put("type", "object"),
                null
        );
    }

    private SysmindProperties properties() {
        return properties(Duration.ofSeconds(10), 3);
    }

    private SysmindProperties properties(Duration toolTimeout, int maxToolCalls) {
        return new SysmindProperties(
                null,
                null,
                new SysmindProperties.Agent(toolTimeout, Duration.ofSeconds(60), maxToolCalls)
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
        private RuntimeException error;
        private boolean neverComplete;

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
            if (neverComplete) {
                return Mono.never();
            }
            if (error != null) {
                return Mono.error(error);
            }
            return Mono.just(toolResult);
        }
    }

    private record RecordedToolCall(String name, Map<String, Object> arguments) {

        private RecordedToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }
}
