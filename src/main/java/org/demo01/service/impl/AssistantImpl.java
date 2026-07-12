package org.demo01.service.impl;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.demo01.service.Assistant;
import org.demo01.stream.Test02;
import org.demo01.util.TimeTools;
import org.springframework.stereotype.Service;

@Service
public class AssistantImpl implements Assistant {
    @Override
    public TokenStream chat(String message) {
        // 对话模型
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .logRequests(true)
                .build();

        Test02.Assistant agent = AiServices.builder(Test02.Assistant.class)
                .streamingChatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .tools(new TimeTools())
                .build();
        return agent.chat(message);
    }
}
