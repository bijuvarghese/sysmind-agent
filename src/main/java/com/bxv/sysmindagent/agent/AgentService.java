package com.bxv.sysmindagent.agent;

import com.bxv.sysmindagent.agent.model.ChatRequest;
import com.bxv.sysmindagent.agent.model.ChatResponse;
import reactor.core.publisher.Mono;

public interface AgentService {

    Mono<ChatResponse> chat(ChatRequest request);
}
