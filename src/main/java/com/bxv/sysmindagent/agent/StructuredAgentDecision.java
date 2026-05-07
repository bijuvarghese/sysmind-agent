package com.bxv.sysmindagent.agent;

import java.util.Map;

record StructuredAgentDecision(
        String type,
        String answer,
        String toolName,
        Map<String, Object> arguments
) {

    StructuredAgentDecision {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    boolean isToolCall() {
        return "tool_call".equals(type);
    }

    boolean isFinal() {
        return "final".equals(type);
    }
}
