package com.bxv.sysmindagent.mcp;

import tools.jackson.databind.JsonNode;

public record McpInitializeResult(
        String protocolVersion,
        JsonNode capabilities,
        JsonNode serverInfo,
        String instructions
) {
}
