package demo.demo02.service;

import demo.demo02.entity.ChunkEntity;
import demo.demo02.event.EventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
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
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    private EventBus bus;


    private static final String SESSION_EKY = "session:%s";
    private static final String SESSION_TO_RUN_EKY = "session-to-run:%s";

    public Flux<String> sessions() {
        return redisTemplate.keys("session:*")
                .flatMap(key -> redisTemplate.opsForValue().get(key))
                .flatMap(value -> Flux.just(value + "\n"));
    }

    public Mono<String> session() {
        String sessionId = createRunId();
        return redisTemplate.opsForValue()
                .set(SESSION_EKY.formatted(sessionId), sessionId)
                .thenReturn(sessionId);
    }

    public Mono<Void> chat(String sessionId, String prompt) {
        String runId = createRunId();
        return redisTemplate.opsForValue()
                .set(SESSION_TO_RUN_EKY.formatted(sessionId), runId)
                .then(client.prompt()
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
                        })
                        .then());
    }


    public Mono<String> getRunId(String sessionId) {
        return redisTemplate.opsForValue().get(SESSION_TO_RUN_EKY.formatted(sessionId));
    }

    public Flux<ChunkEntity> stream(String runId, long lastSeq) {
        return bus.subscribe(runId, lastSeq);
    }


    public String createRunId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
