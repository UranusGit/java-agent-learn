package org.demo03.tool;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public class McpResourceToolCallback implements ToolCallback {

    private final ToolDefinition toolDefinition;
    private final McpAsyncClient mcpClient;
    private final String resourceUri;

    public McpResourceToolCallback(String toolName, String description,
                                   McpAsyncClient mcpClient, String resourceUri) {
        this.toolDefinition = ToolDefinition.builder()
                .name(toolName)
                .description(description)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        this.mcpClient = mcpClient;
        this.resourceUri = resourceUri;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        McpSchema.ReadResourceResult result = mcpClient.readResource(
                McpSchema.ReadResourceRequest.builder(resourceUri).build()
        ).block();
        return result.contents().stream()
                .filter(McpSchema.TextResourceContents.class::isInstance)
                .map(McpSchema.TextResourceContents.class::cast)
                .map(McpSchema.TextResourceContents::text)
                .findFirst()
                .orElse("[]");
    }
}
