package com.bxv.sysmindagent.agent.model;

public record ChatEvent(
        String type,
        String message,
        ToolCall toolCall,
        ToolResult toolResult
) {

    public static ChatEvent message(String message) {
        return new ChatEvent("message", message, null, null);
    }

    public static ChatEvent toolCall(ToolCall toolCall) {
        return new ChatEvent("tool_call", null, toolCall, null);
    }

    public static ChatEvent toolResult(ToolResult toolResult) {
        return new ChatEvent("tool_result", null, null, toolResult);
    }

    public static ChatEvent error(String message) {
        return new ChatEvent("error", message, null, null);
    }
}
