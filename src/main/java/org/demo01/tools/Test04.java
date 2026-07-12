package org.demo01.tools;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public class Test04 {
    public interface Translator {
        @UserMessage("将以下文本翻译成 {{language}}:{{text}}")
        String translate(@V("language") String language, @V("text") String text);
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

        Translator agent = AiServices.builder(Translator.class)
                .chatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        System.out.println(agent.translate("English", "你好呀，我是小白"));
    }
}
