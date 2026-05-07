package com.bxv.sysmindagent.mcp;

public class JsonRpcException extends RuntimeException {

    private final JsonRpcError error;

    public JsonRpcException(JsonRpcError error) {
        super(error == null ? "JSON-RPC request failed." : error.message());
        this.error = error;
    }

    public JsonRpcError getError() {
        return error;
    }
}
