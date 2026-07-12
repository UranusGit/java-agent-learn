package org.demo01;

import dev.langchain4j.model.openai.OpenAiChatModel;

public class Test01 {
    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .build();

        String res = model.chat("你好，请问你是什么模型");
        System.out.println(res);
    }
}
