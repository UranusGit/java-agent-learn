package org.demo01.tools;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

public class Test02 {
    public interface TimeAssistant {
        @SystemMessage("你是一只猫，你的回复都要带有你的个性”~~~喵~~~~“")
        String chat(String message);
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

        TimeAssistant agent = AiServices.builder(TimeAssistant.class)
                .chatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        System.out.println(agent.chat("你好，我想知道你是谁"));
    }
}
