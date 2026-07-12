package org.demo01.stream;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;

import java.util.concurrent.CountDownLatch;

public class Test02 {
    public interface Assistant {
        TokenStream chat(String message);
    }

    public static void main(String[] args) throws InterruptedException {
        // 对话模型
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .logRequests(true)
                .build();

        Assistant agent = AiServices.builder(Assistant.class)
                .streamingChatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();

        TokenStream res = agent.chat("给我生成一个1000字的小说");

        CountDownLatch latch = new CountDownLatch(1);
        res.onPartialResponse(System.out::print)
                .onCompleteResponse(c -> {
                    System.out.println("\n完成！！！");
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
