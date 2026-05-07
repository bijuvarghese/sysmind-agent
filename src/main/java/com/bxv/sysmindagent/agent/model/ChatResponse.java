package com.bxv.sysmindagent.agent.model;

import java.util.List;

public record ChatResponse(
        String answer,
        List<AgentStep> steps
) {

    public ChatResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public ChatResponse(String answer) {
        this(answer, List.of(AgentStep.finalAnswer(answer)));
    }
}
