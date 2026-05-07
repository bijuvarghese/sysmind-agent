package com.bxv.sysmindagent.agent.model;

public record AgentStep(
        String type,
        String message,
        ToolCall toolCall,
        ToolResult toolResult
) {

    public static AgentStep thinking(String message) {
        return new AgentStep("thinking", message, null, null);
    }

    public static AgentStep toolCall(ToolCall toolCall) {
        return new AgentStep("tool_call", null, toolCall, null);
    }

    public static AgentStep toolResult(ToolResult toolResult) {
        return new AgentStep("tool_result", null, null, toolResult);
    }

    public static AgentStep finalAnswer(String message) {
        return new AgentStep("final", message, null, null);
    }
}
