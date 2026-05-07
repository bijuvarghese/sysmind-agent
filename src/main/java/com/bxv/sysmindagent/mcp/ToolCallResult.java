package com.bxv.sysmindagent.mcp;

import tools.jackson.databind.JsonNode;

public record ToolCallResult(
        JsonNode content,
        JsonNode structuredContent,
        Boolean isError
) {
}
