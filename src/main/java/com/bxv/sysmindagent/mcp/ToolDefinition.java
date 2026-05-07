package com.bxv.sysmindagent.mcp;

import tools.jackson.databind.JsonNode;

public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema
) {
}
