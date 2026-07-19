package org.demo03.config;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
public class ChatConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 List<ToolCallbackProvider> mcpToolProviders) {
        // 收集 MCP server 已经声明的 @McpTool
        List<ToolCallback> all = mcpToolProviders.stream()
                .map(ToolCallbackProvider::getToolCallbacks)
                .flatMap(Arrays::stream)
                .toList();

        return builder
                .defaultTools(all)
                .build();
    }

    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return new DefaultMcpToolNamePrefixGenerator();
    }

    @Bean
    public CommandLineRunner ask(ChatClient client, List<McpAsyncClient> mcpClients) {
        return ask -> {
            McpAsyncClient mcpClient = mcpClients.stream()
                    .filter(mcp -> "demo03-mcp-client - time-tool".equals(mcp.getClientInfo().name()))
                    .findFirst()
                    .get();

            String userJson = mcpClient.readResource(
                    new McpSchema.ReadResourceRequest("user://user-list")
            ).map(result -> result.contents().stream()
                    .filter(McpSchema.TextResourceContents.class::isInstance)
                    .map(McpSchema.TextResourceContents.class::cast)
                    .map(McpSchema.TextResourceContents::text)
                    .findFirst()
                    .orElse("")
            ).block();

            McpSchema.TextContent content = (McpSchema.TextContent) mcpClient
                    .getPrompt(
                            new McpSchema.GetPromptRequest(
                                    "planRoute",Map.of("name", "北京", "to", "上海")))
                    .block().messages().get(0).content();
            System.out.println(content.text());

            System.out.println("读取到的用户列表: " + userJson);
            String res = client.prompt()
                    .system("以下是系统当前的用户列表数据，回答用户问题时请基于这些数据：\n" + userJson)
                    .user("我有几个用户")
                    .call()
                    .content();
            System.out.println(res);
        };
    }
}
