package com.bxv.sysmindagent.mcp;

import tools.jackson.databind.JsonNode;

public record JsonRpcResponse(
        String jsonrpc,
        long id,
        JsonNode result,
        JsonRpcError error
) {
}
