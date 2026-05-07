package com.bxv.sysmindagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bxv.sysmindagent.agent.AgentService;
import com.bxv.sysmindagent.agent.model.AgentStep;
import com.bxv.sysmindagent.agent.model.ChatRequest;
import com.bxv.sysmindagent.agent.model.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class ChatControllerTest {

    @Test
    void delegatesChatRequestToAgentService() {
        FakeAgentService agentService = new FakeAgentService();
        WebTestClient webTestClient = WebTestClient.bindToController(new ChatController(agentService)).build();

        webTestClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "message": "Check my machine status."
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.answer").isEqualTo("Your machine looks healthy.")
                .jsonPath("$.steps[0].type").isEqualTo("final")
                .jsonPath("$.steps[0].message").isEqualTo("Your machine looks healthy.");

        assertThat(agentService.requests).hasSize(1);
        assertThat(agentService.requests.getFirst().message()).isEqualTo("Check my machine status.");
    }

    private static class FakeAgentService implements AgentService {

        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public Mono<ChatResponse> chat(ChatRequest request) {
            requests.add(request);
            return Mono.just(new ChatResponse(
                    "Your machine looks healthy.",
                    List.of(AgentStep.finalAnswer("Your machine looks healthy."))
            ));
        }
    }
}
