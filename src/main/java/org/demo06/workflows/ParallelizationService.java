package org.demo06.workflows;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.BiFunction;

public abstract class ParallelizationService {
    protected final ChatClient client;

    /**
     * 子类声明若干 worker（入参 input + sessionId，出参是单 worker 结果）。
     */
    protected abstract List<BiFunction<String, String, String>> works();

    /**
     * 子类声明聚合策略（入参 worker 结果列表，出参是最终输出）。
     */
    protected abstract BiFunction<List<String>, String, String> aggregator();

    protected ParallelizationService(ChatClient client) {
        this.client = client;
    }

    public String run(String input, String sessionId) {
        List<String> results = Flux.fromIterable(works())
                .flatMap(work ->
                        Mono.fromCallable(() -> work.apply(input, sessionId))
                                .subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .block();
        return aggregator().apply(results, sessionId);
    }

    protected String call(String system, String prompt, String sid) {
        return client.prompt()
                .system(system)
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                .call()
                .content();
    }
}
