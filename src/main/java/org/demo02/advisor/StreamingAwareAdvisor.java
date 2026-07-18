package org.demo02.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;


@Slf4j
public class StreamingAwareAdvisor implements StreamAdvisor {
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        // 必须返回聚合器装饰后的 Flux，否则回调不会被触发（聚合器内部是装饰而非旁路订阅）
        return flux;
    }

    @Override
    public String getName() {
        return "StreamingAwareAdvisor";
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 150;
    }
}
