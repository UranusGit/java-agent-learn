package org.demo01.tools;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.demo01.util.CalculatorTools;
import org.demo01.util.TimeTools;

public class Test01 {
    public interface TimeAssistant {
        String chat(String message);
    }

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .logRequests(true) // 如果要看日志打印，必须增加log相关的依赖，如logback等
                .logResponses(true)
                .build();


        TimeAssistant agent = AiServices.builder(TimeAssistant.class)
                .chatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .tools(new TimeTools(), new CalculatorTools())
                .build();

        System.out.println(agent.chat("现在是几点了"));
        System.out.println(agent.chat("我想知道50减32再乘21等于多少"));
    }
}
