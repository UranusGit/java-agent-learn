package org.demo06.workflows.chaining;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;
import java.util.function.BiFunction;

public abstract class ChainingService {

    protected final ChatClient chatClient;

    protected ChainingService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    protected abstract List<BiFunction<String, String, String>> steps();

    public String run(String input, String sessionId) {
        String payload = input;
        for (BiFunction<String, String, String> step : steps()) {
            payload = step.apply(payload, sessionId);
            if (payload == null) {
                return "[CHAIN TERMINATED]";
            }
        }
        return payload;
    }

    protected String call(String systemPrompt, String userText, String sessionId) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userText)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }
}
