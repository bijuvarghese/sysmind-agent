package com.bxv.sysmindagent.web;

import com.bxv.sysmindagent.agent.AgentService;
import com.bxv.sysmindagent.agent.model.ChatEvent;
import com.bxv.sysmindagent.agent.model.ChatRequest;
import com.bxv.sysmindagent.agent.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ChatResponse> chat(@RequestBody ChatRequest request) {
        return agentService.chat(request);
    }

    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEvent>> stream(@RequestBody ChatRequest request) {
        return agentService.stream(request)
                .map(event -> ServerSentEvent.builder(event)
                        .event(event.type())
                        .build());
    }
}
