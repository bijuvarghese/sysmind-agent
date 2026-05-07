package com.bxv.sysmindagent.mcp;

public record JsonRpcRequest(
        String jsonrpc,
        long id,
        String method,
        Object params
) {

    public JsonRpcRequest(long id, String method, Object params) {
        this("2.0", id, method, params);
    }
}
