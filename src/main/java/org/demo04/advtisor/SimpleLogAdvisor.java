package org.demo04.advtisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

public class SimpleLogAdvisor implements BaseAdvisor {
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String prompt = request.prompt().getUserMessage().getText();
        System.out.println("[REQ]: " + prompt);
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String resp = response.chatResponse().getResult().getOutput().getText();
        System.out.println("[RESP]: " + resp);
        return response;
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 500;
    }
}
