package org.demo01.stream;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.concurrent.CountDownLatch;

public class Test01 {
    public static void main(String[] args) throws InterruptedException {
        // 对话模型
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .logRequests(true)
                .build();

        CountDownLatch latch = new CountDownLatch(1);

        model.chat("给我生成一个1000字的侦探小说", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                System.out.print(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                System.out.println("\n完成！！！");
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                error.printStackTrace();
                latch.countDown();
            }
        });

        latch.await();
    }
}
