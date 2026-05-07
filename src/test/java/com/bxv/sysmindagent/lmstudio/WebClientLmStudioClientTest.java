package com.bxv.sysmindagent.lmstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.bxv.sysmindagent.SysmindProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
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

class WebClientLmStudioClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completePostsNonStreamingChatCompletionRequest() {
        TestExchangeFunction exchangeFunction = new TestExchangeFunction(body -> """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Hello from LM Studio."
                      }
                    }
                  ]
                }
                """);
        LmStudioClient client = client(exchangeFunction);

        StepVerifier.create(client.complete(List.of(
                        new LmStudioMessage("system", "You are SysMind agent."),
                        new LmStudioMessage("user", "Say hello.")
                )))
                .expectNext("Hello from LM Studio.")
                .verifyComplete();

        assertThat(exchangeFunction.paths()).containsExactly("/v1/chat/completions");
        assertThat(exchangeFunction.authorizationHeaders()).containsExactly("Bearer lm-studio");

        JsonNode request = read(exchangeFunction.bodies().getFirst());
        assertThat(request.get("model").asText()).isEqualTo("local-model");
        assertThat(request.get("stream").asBoolean()).isFalse();
        assertThat(request.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(request.at("/messages/1/content").asText()).isEqualTo("Say hello.");
    }

    private WebClientLmStudioClient client(ExchangeFunction exchangeFunction) {
        SysmindProperties properties = new SysmindProperties(
                new SysmindProperties.LmStudio(URI.create("http://lmstudio.test"), "lm-studio", "local-model"),
                null,
                null
        );
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.lmStudio().baseUrl().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.lmStudio().apiKey())
                .exchangeFunction(exchangeFunction)
                .build();

        return new WebClientLmStudioClient(webClient, properties);
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
        private final List<String> authorizationHeaders = new ArrayList<>();
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
                        authorizationHeaders.add(request.headers().getFirst(HttpHeaders.AUTHORIZATION));
                        return ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
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

        List<String> authorizationHeaders() {
            return authorizationHeaders;
        }
    }
}
