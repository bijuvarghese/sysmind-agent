package com.bxv.sysmindagent.agent.model;

import java.util.Map;

public record ToolCall(
        String toolName,
        Map<String, Object> arguments
) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
