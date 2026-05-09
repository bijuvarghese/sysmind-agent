package com.bxv.sysmindagent.web;

import com.bxv.sysmindagent.mcp.McpClient;
import com.bxv.sysmindagent.mcp.ToolDefinition;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/tools")
public class ToolsController {

    private final McpClient mcpClient;

    public ToolsController(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<ToolDefinition>> tools() {
        return mcpClient.listTools();
    }
}
