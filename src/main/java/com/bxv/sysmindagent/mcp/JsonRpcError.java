package com.bxv.sysmindagent.mcp;

import tools.jackson.databind.JsonNode;

public record JsonRpcError(
        int code,
        String message,
        JsonNode data
) {
}
