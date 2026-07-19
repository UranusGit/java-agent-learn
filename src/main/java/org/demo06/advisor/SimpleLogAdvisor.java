package org.demo06.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

@Slf4j
public class SimpleLogAdvisor implements CallAdvisor {
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String prompt = request.prompt().getUserMessage().getText();
        String system = request.prompt().getSystemMessage().getText();
        log.info("[REQ]: \nsystem: {}\nuser: {}", system, prompt);
        ChatClientResponse response = chain.nextCall(request);
        String resp = response.chatResponse().getResult().getOutput().getText();
        log.info("[RESP]: {}", resp);
        return response;
    }

    @Override
    public String getName() {
        return "SimpleLogAdvisor";
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 500;
    }
}
