package com.bxv.sysmindagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.bxv.sysmindagent.SysmindProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WebClientMcpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void initializePostsJsonRpcRequestToConfiguredMcpEndpoint() {
        TestExchangeFunction exchangeFunction = new TestExchangeFunction(body -> jsonRpcResult(body, """
                {
                  "protocolVersion": "2025-06-18",
                  "capabilities": {},
                  "serverInfo": {
                    "name": "sysmind-mcp",
                    "version": "1.0.0"
                  }
                }
                """));
        WebClientMcpClient client = client(exchangeFunction);

        StepVerifier.create(client.initialize())
                .assertNext(result -> {
                    assertThat(result.protocolVersion()).isEqualTo("2025-06-18");
                    assertThat(result.serverInfo().get("name").asText()).isEqualTo("sysmind-mcp");
                })
                .verifyComplete();

        assertThat(exchangeFunction.paths()).containsExactly("/mcp");
        JsonNode request = read(exchangeFunction.bodies().getFirst());
        assertThat(request.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(request.get("method").asText()).isEqualTo("initialize");
        assertThat(request.at("/params/protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(request.at("/params/clientInfo/name").asText()).isEqualTo("sysmind-agent");
    }

    @Test
    void listToolsCachesToolDefinitions() {
        TestExchangeFunction exchangeFunction = new TestExchangeFunction(body -> jsonRpcResult(body, """
                {
                  "tools": [
                    {
                      "name": "machine_status",
                      "description": "Returns machine status.",
                      "inputSchema": {
                        "type": "object"
                      }
                    }
                  ]
                }
                """));
        WebClientMcpClient client = client(exchangeFunction);

        StepVerifier.create(client.listTools())
                .assertNext(tools -> {
                    assertThat(tools).hasSize(1);
                    assertThat(tools.getFirst().name()).isEqualTo("machine_status");
                    assertThat(tools.getFirst().inputSchema().get("type").asText()).isEqualTo("object");
                })
                .verifyComplete();

        StepVerifier.create(client.listTools())
                .assertNext(tools -> assertThat(tools.getFirst().name()).isEqualTo("machine_status"))
                .verifyComplete();

        assertThat(exchangeFunction.bodies()).hasSize(1);
        assertThat(read(exchangeFunction.bodies().getFirst()).get("method").asText()).isEqualTo("tools/list");
    }

    @Test
    void callToolPrimesToolCacheAndPostsToolCall() {
        TestExchangeFunction exchangeFunction = new TestExchangeFunction(body -> {
            JsonNode request = read(body);
            String method = request.get("method").asText();
            if ("tools/list".equals(method)) {
                return jsonRpcResult(body, """
                        {
                          "tools": [
                            {
                              "name": "latest_news",
                              "description": "Fetches news.",
                              "inputSchema": {
                                "type": "object"
                              }
                            }
                          ]
                        }
                        """);
            }

            return jsonRpcResult(body, """
                    {
                      "content": [
                        {
                          "type": "text",
                          "text": "news result"
                        }
                      ],
                      "isError": false
                    }
                    """);
        });
        WebClientMcpClient client = client(exchangeFunction);

        StepVerifier.create(client.callTool("latest_news", Map.of("limit", 2)))
                .assertNext(result -> {
                    assertThat(result.isError()).isFalse();
                    assertThat(result.content().get(0).get("text").asText()).isEqualTo("news result");
                })
                .verifyComplete();

        assertThat(exchangeFunction.bodies()).hasSize(2);
        assertThat(read(exchangeFunction.bodies().get(0)).get("method").asText()).isEqualTo("tools/list");

        JsonNode toolCall = read(exchangeFunction.bodies().get(1));
        assertThat(toolCall.get("method").asText()).isEqualTo("tools/call");
        assertThat(toolCall.at("/params/name").asText()).isEqualTo("latest_news");
        assertThat(toolCall.at("/params/arguments/limit").asInt()).isEqualTo(2);
    }

    private WebClientMcpClient client(ExchangeFunction exchangeFunction) {
        URI baseUri = URI.create("http://sysmind-mcp.test");
        SysmindProperties properties = new SysmindProperties(
                null,
                new SysmindProperties.Mcp(baseUri, "/mcp"),
                null
        );
        WebClient webClient = WebClient.builder()
                .baseUrl(baseUri.toString())
                .exchangeFunction(exchangeFunction)
                .build();

        return new WebClientMcpClient(webClient, properties, objectMapper);
    }

    private String jsonRpcResult(String requestBody, String resultJson) {
        long id = read(requestBody).get("id").asLong();
        return """
                {
                  "jsonrpc": "2.0",
                  "id": %d,
                  "result": %s
                }
                """.formatted(id, resultJson);
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static class TestExchangeFunction implements ExchangeFunction {

        private final List<String> paths = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final Function<String, String> responder;
        private final ExchangeStrategies exchangeStrategies = ExchangeStrategies.withDefaults();

        TestExchangeFunction(Function<String, String> responder) {
            this.responder = responder;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            MockClientHttpRequest mockRequest = new MockClientHttpRequest(request.method(), request.url());

            BodyInserter.Context context = new BodyInserter.Context() {
                @Override
                public List<HttpMessageWriter<?>> messageWriters() {
                    return exchangeStrategies.messageWriters();
                }

                @Override
                public Optional<ServerHttpRequest> serverRequest() {
                    return Optional.empty();
                }

                @Override
                public Map<String, Object> hints() {
                    return Map.of();
                }
            };

            return request.body()
                    .insert(mockRequest, context)
                    .then(Mono.defer(mockRequest::getBodyAsString))
                    .map(body -> {
                        paths.add(request.url().getPath());
                        bodies.add(body);
                        return ClientResponse.create(HttpStatus.OK)
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .body(responder.apply(body))
                                .build();
                    });
        }

        List<String> paths() {
            return paths;
        }

        List<String> bodies() {
            return bodies;
        }
    }
}
