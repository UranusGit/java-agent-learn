package org.demo01.memory;

import dev.langchain4j.chain.ConversationalChain;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

public class Test02 {
    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("API_KEY"))
                .modelName("deepseek-v4-flash")
                .temperature(0.7)
                .build();

        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        String test01 = "test01";

        ChatMemoryProvider provider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(store)
                .build();

        ChatMemory memory = provider.get(test01);
        ConversationalChain chain = ConversationalChain.builder()
                .chatMemory(memory)
                .chatModel(model)
                .build();

        String s1 = chain.execute("你好");
        String s2 = chain.execute("我刚刚问了什么");
        System.out.println(s2);
    }
}
