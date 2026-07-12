package org.demo01.tools;


import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

public class Test03 {
    public record Sentiment(String emotion, double score, String reason) {
    }

    public interface SentimentAnalyzer {
        @SystemMessage("分析文本的情感倾向，必须返回 JSON 格式")
        Sentiment analyzer(String text);
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

        SentimentAnalyzer agent = AiServices.builder(SentimentAnalyzer.class)
                .chatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();

        Sentiment sentiment = agent.analyzer("你真的是个废物");
        System.out.println("sentiment = " + sentiment);
    }
}
