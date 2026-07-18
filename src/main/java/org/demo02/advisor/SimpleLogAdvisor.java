package org.demo02.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

public class SimpleLogAdvisor implements BaseAdvisor {
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        System.out.println("[REQ]：" + request.prompt().getUserMessage().getText());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
//        System.out.println("[RESP]：" + response.chatResponse().getResult().getOutput().getText());
        return response;
    }

    @Override
    public String getName() {
        return "SimpleLogAdvisor";
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 150; // 越小越先执行 before
    }
}
