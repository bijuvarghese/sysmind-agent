package com.bxv.sysmindagent.agent;

record StructuredAgentDecision(
        String type,
        String answer,
        String toolName,
        Object arguments
) {

    boolean isToolCall() {
        return "tool_call".equals(type);
    }

    boolean isFinal() {
        return "final".equals(type);
    }
}
