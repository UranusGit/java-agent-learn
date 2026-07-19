package org.demo06.workflows;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;
import java.util.function.BiFunction;

public abstract class ChainingService {

    protected final ChatClient chatClient;

    protected ChainingService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 子类声明步骤链。每步入参 (上一步输出, sessionId)，出参是本步输出。 */
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

    protected String call(String system, String prompt, String sid) {
        return chatClient.prompt()
                .system(system)
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                .call()
                .content();
    }
}
