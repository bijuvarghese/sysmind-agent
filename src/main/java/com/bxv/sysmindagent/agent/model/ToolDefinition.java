package com.bxv.sysmindagent.agent.model;

import tools.jackson.databind.JsonNode;

public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema
) {

    public static ToolDefinition fromMcp(com.bxv.sysmindagent.mcp.ToolDefinition toolDefinition) {
        return new ToolDefinition(
                toolDefinition.name(),
                toolDefinition.description(),
                toolDefinition.inputSchema()
        );
    }
}
