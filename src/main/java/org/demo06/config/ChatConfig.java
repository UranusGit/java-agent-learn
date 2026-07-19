package org.demo06.config;

import org.demo06.advisor.SimpleLogAdvisor;
import org.demo06.util.IdUtils;
import org.demo06.workflows.chaining.PromptChainingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ChatConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {
        return builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).build(),
                        new SimpleLogAdvisor())
                .build();
    }

    @Bean
    public PromptChainingAdvisor promptChainingAdvisor(ChatClient client) {
        return new PromptChainingAdvisor(List.of(
                input -> client.prompt()
                        .user(input)
                        .system("改写用户的prompt。生成prompt对应的大纲，只输出大纲")
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, IdUtils.getId()))
                        .call()
                        .content(),
                input -> client.prompt()
                        .user(input)
                        .system("改写用户的prompt。根据提供的大纲生成草稿，只输出草稿")
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, IdUtils.getId()))
                        .call()
                        .content(),
                input -> client.prompt()
                        .user(input)
                        .system("改写用户的prompt。润色草稿，让它更流畅，只输出prompt")
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, IdUtils.getId()))
                        .call()
                        .content()
        ));
    }

}
