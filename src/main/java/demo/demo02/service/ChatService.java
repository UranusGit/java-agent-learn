package demo.demo02.service;

import demo.demo02.entity.ChunkEntity;
import demo.demo02.event.EventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@Slf4j
public class ChatService {
    @Autowired
    private ChatClient client;

    @Autowired
    private EventBus bus;

    public Mono<String> chat(String prompt) {
        String runId = createRunId();

        client.prompt()
                .user(prompt)
                .stream()
                .content()
                .doOnNext(chunk -> bus.write(runId, chunk))
                .doOnComplete(() -> {
                    log.info("生成完成：{}", runId);
                    bus.writeEnd(runId);
                })
                .doOnError(error -> {
                    log.error("{} 执行报错：{}", runId, error.getMessage());
                    bus.writeEnd(runId);
                }).subscribe();

        return Mono.just(runId);
    }

    public Flux<ChunkEntity> stream(String runId, long lastSeq) {
        return bus.subscribe(runId, lastSeq);
    }


    public String createRunId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
