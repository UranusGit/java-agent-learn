package org.demo04.config;

import org.demo04.advtisor.SimpleLogAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory, VectorStore vectorStore) {
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build(),
                        new SimpleLogAdvisor(),
                        QuestionAnswerAdvisor.builder(vectorStore).build()
                )
                .build();
    }
}
