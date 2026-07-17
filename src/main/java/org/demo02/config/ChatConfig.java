package org.demo02.config;

import org.demo02.advisor.SimpleLogAdvisor;
import org.demo02.tools.TimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    @Autowired
    private TimeTools timeTools;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().build();
    }

    @Bean
    public ToolCallingAdvisor toolCallingAdvisor(ToolCallingManager toolCallingManager) {
        return ToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .advisorOrder(100)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory, ToolCallingAdvisor toolCallingAdvisor) {
        return builder.defaultSystem("你是一个友好的助手")
                .defaultTools(timeTools)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).order(0).build(),
                        toolCallingAdvisor,
                        new SimpleLogAdvisor()
                ).build();
    }
}
