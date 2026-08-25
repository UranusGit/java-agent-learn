package demo.demo01.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

@Slf4j
public class SimpleLogAdvisor implements StreamAdvisor {

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        log.info("REQ: {}", request.prompt().getUserMessage().getText());
        return chain.nextStream(request)
                .doOnNext(resp -> log.info("[RESP]: {}", resp.chatResponse().getResult().getOutput().getText()));
    }


    @Override
    public String getName() {
        return "SimpleLogAdvisor";
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 1500;
    }
}
