package org.demo06.workflows;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.BiFunction;

public abstract class OrchestratorService {
    private final ChatClient client;

    protected abstract BiFunction<String, String, String> worker();

    protected OrchestratorService(ChatClient client) {
        this.client = client;
    }


    public String run(String input, String sid) {
        // 1. orchestrator 决定子任务
        List<String> subtasks = client.prompt()
                .system("""
                        把任务拆成若干独立子任务，输出 JSON：
                        ["任务1", "任务2", ...]
                        只输出 JSON。
                        """)
                .user(input)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sid))
                .call()
                .entity(new ParameterizedTypeReference<>() {
                });

        // 2. 并行执行 worker
        List<String> results = Flux.fromIterable(subtasks)
                .flatMap(subtask ->
                                Mono.fromCallable(() ->
                                                worker().apply(subtask, sid))
                                        .subscribeOn(Schedulers.boundedElastic()),
                        Math.min(subtasks.size(), 5))
                .collectList()
                .block();
        // 3. 聚合
        return call("把以下子任务结果整合为完整的报告",
                "原任务： %s \n子任务结果：%s".formatted(input, String.join("\n---\n", results)),
                sid);
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
