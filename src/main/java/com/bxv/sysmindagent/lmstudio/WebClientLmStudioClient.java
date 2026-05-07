package com.bxv.sysmindagent.lmstudio;

import com.bxv.sysmindagent.SysmindProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class WebClientLmStudioClient implements LmStudioClient {

    private final WebClient webClient;
    private final SysmindProperties properties;

    public WebClientLmStudioClient(@Qualifier("lmStudioWebClient") WebClient webClient, SysmindProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public Mono<String> complete(List<LmStudioMessage> messages) {
        var request = new LmStudioChatRequest(
                properties.lmStudio().model(),
                messages,
                false
        );
        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LmStudioChatResponse.class)
                .map(this::firstMessageContent);
    }

    private String firstMessageContent(LmStudioChatResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("LM Studio response did not include any choices.");
        }
        LmStudioMessage message = response.choices().getFirst().message();
        if (message == null) {
            throw new IllegalStateException("LM Studio response choice did not include a message.");
        }
        return message.content();
    }
}
