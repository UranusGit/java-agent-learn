package org.demo02.config;

import org.demo02.advisor.SimpleLogAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {
        return builder.defaultSystem("你是一个友好的助手")
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory)
                                .conversationId("default")
                                .build(),
                        new SimpleLogAdvisor()
                ).build();
    }
}
