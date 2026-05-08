package com.bxv.sysmindagent.agent.model;

public record ChatEvent(
        String type,
        String message,
        ToolCall toolCall,
        ToolResult toolResult
) {

    public static ChatEvent messageStarted() {
        return new ChatEvent("message.started", null, null, null);
    }

    public static ChatEvent messageDelta(String message) {
        return new ChatEvent("message.delta", message, null, null);
    }

    public static ChatEvent messageFinished(String message) {
        return new ChatEvent("message.finished", message, null, null);
    }

    public static ChatEvent toolStarted(ToolCall toolCall) {
        return new ChatEvent("tool.started", null, toolCall, null);
    }

    public static ChatEvent toolFinished(ToolResult toolResult) {
        return new ChatEvent("tool.finished", null, null, toolResult);
    }

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
