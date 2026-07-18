package org.demo02.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

public class PiiMaskAdvisor implements BaseAdvisor {
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        var result = response.chatResponse().getResult();
        var output = result.getOutput();
        String masked = output.getText().replace("Anthropic", "******");

        AssistantMessage replaced = AssistantMessage.builder()
                .content(masked)
                .properties(output.getMetadata())
                .toolCalls(output.getToolCalls())
                .media(output.getMedia())
                .build();

        Generation generation = new Generation(replaced, result.getMetadata());

        return response.mutate()
                .chatResponse(ChatResponse.builder()
                        .from(response.chatResponse())
                        .generations(List.of(generation))
                        .build())
                .build();
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 152;
    }
}
