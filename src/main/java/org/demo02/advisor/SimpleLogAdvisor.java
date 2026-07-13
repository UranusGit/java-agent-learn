package org.demo02.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.util.ObjectUtils;
import reactor.core.publisher.Flux;

public class SimpleLogAdvisor implements CallAdvisor, StreamAdvisor {
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        System.out.println("[REQ]：" + request.prompt().getUserMessage().getText());

        ChatClientResponse response = chain.nextCall(request);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[RESP]：" + elapsed + " ms");
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        System.out.println("[REQ]：" + request.prompt().getUserMessage().getText());

        return chain.nextStream(request)
                .doOnSubscribe(subscription -> System.out.println("开始消费了！！！！"))
                .filter(word -> !ObjectUtils.isEmpty(word.chatResponse().getResult().getOutput().getText()))
                .doOnNext(chunk -> System.out.println(chunk.chatResponse().getResult().getOutput().getText()))
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println("[RESP]：" + elapsed + " ms");
                });
    }

    @Override
    public String getName() {
        return "SimpleLogAdvisor";
    }

    @Override
    public int getOrder() {
        return 0; // 越小越先执行 before
    }
}
