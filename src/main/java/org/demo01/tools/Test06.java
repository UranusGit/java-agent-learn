package org.demo01.tools;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;

import java.util.concurrent.CountDownLatch;

public class Test06 {
    public interface StreamingAssistant {
        TokenStream chat(String userMessage);
    }

    public static void main(String[] args) throws InterruptedException {
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .build();


        StreamingAssistant agent = AiServices.builder(StreamingAssistant.class)
                .streamingChatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        TokenStream res = agent.chat("给我生成一个10000字的侦探小说");

        CountDownLatch latch = new CountDownLatch(1);

        res.onPartialResponse(System.out::print)
                .onCompleteResponse(chatResponse -> {
                    System.out.println("\n完成!!!");
                    latch.countDown();
                })
                .onError(throwable -> {
                    throwable.printStackTrace();
                    latch.countDown();
                })
                .start();

        latch.await();

    }
}
