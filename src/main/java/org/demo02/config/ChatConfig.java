package org.demo02.config;

import org.demo02.advisor.SimpleLogAdvisor;
import org.demo02.tools.TimeTools;
import org.demo02.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    @Autowired
    private TimeTools timeTools;

    @Autowired
    private WeatherTools weatherTools;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {
        return builder.defaultSystem("你是一个友好的助手")
                .defaultTools(timeTools, weatherTools)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).order(0).build(),
                        new SimpleLogAdvisor(),
                        ToolSearchToolCallingAdvisor.builder().toolIndex(new RegexToolIndex()).maxResults(5).build()
                ).build();
    }
}
