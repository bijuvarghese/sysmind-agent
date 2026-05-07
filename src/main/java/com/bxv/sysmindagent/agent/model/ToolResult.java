package com.bxv.sysmindagent.agent.model;

import tools.jackson.databind.JsonNode;

public record ToolResult(
        String toolName,
        JsonNode content,
        boolean error,
        String errorMessage
) {

    public static ToolResult success(String toolName, JsonNode content) {
        return new ToolResult(toolName, content, false, null);
    }

    public static ToolResult failure(String toolName, String errorMessage) {
        return new ToolResult(toolName, null, true, errorMessage);
    }
}
