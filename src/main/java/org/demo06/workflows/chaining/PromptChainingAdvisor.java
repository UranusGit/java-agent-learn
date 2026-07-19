package org.demo06.workflows.chaining;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.function.Function;

public class PromptChainingAdvisor implements BaseAdvisor {
    private final List<Function<String, String>> steps;

    public PromptChainingAdvisor(List<Function<String, String>> steps) {
        this.steps = steps;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String input = request.prompt()
                .getUserMessage()
                .getText();
        String current = input;
        for (Function<String, String> step : steps) {
            current = step.apply(current);
            if (current == null) {
                return request.mutate()
                        .prompt(request.prompt().mutate()
                                .messages(new UserMessage("[CHAIN TERMINATED]"))
                                .build())
                        .build();
            }
        }
        return request.mutate()
                .prompt(request.prompt().mutate()
                        .messages(new UserMessage(current))
                        .build())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 248;
    }
}
