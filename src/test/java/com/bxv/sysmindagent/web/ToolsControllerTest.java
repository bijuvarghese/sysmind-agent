package com.bxv.sysmindagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bxv.sysmindagent.mcp.McpClient;
import com.bxv.sysmindagent.mcp.McpInitializeResult;
import com.bxv.sysmindagent.mcp.ToolCallResult;
import com.bxv.sysmindagent.mcp.ToolDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class ToolsControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listsAvailableMcpTools() {
        FakeMcpClient mcpClient = new FakeMcpClient(List.of(
                new ToolDefinition(
                        "machine_status",
                        "Return host status details.",
                        objectMapper.createObjectNode().put("type", "object"),
                        null
                )
        ));
        WebTestClient webTestClient = WebTestClient.bindToController(new ToolsController(mcpClient)).build();

        webTestClient.get()
                .uri("/api/tools")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("machine_status")
                .jsonPath("$[0].description").isEqualTo("Return host status details.")
                .jsonPath("$[0].inputSchema.type").isEqualTo("object");

        assertThat(mcpClient.listToolsCalls).isEqualTo(1);
    }

    private static class FakeMcpClient implements McpClient {

        private final List<ToolDefinition> tools;
        private int listToolsCalls;

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
            return Mono.empty();
        }
    }
}
