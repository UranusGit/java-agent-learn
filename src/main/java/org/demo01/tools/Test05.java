package org.demo01.tools;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

public class Test05 {
    public interface CustomerService {
        String chat(@MemoryId String memoryId, @UserMessage String message);
    }

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .logRequests(true)
                .logResponses(true)
                .build();

        CustomerService agent = AiServices.builder(CustomerService.class)
                .chatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .build())
                .build();
        System.out.println(agent.chat("test01", "你好"));
        System.out.println(agent.chat("test01", "我刚才问了什么"));
        System.out.println(agent.chat("test02", "我刚才问了什么"));
    }
}
