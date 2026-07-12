package org.demo01.memory;

import dev.langchain4j.chain.ConversationalChain;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.Scanner;

public class Test01 {
    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .build();
        MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);

        ConversationalChain chain = ConversationalChain.builder()
                .chatMemory(memory)
                .chatModel(model)
                .build();


        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("你 >");
                String input = scanner.nextLine();
                if ("exit".equals(input)) {
                    break;
                }
                String res = chain.execute(input);
                System.out.println("AI >" + res);
            }
        }
    }
}
