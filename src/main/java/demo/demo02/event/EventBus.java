package demo.demo02.event;

import demo.demo02.entity.ChunkEntity;
import demo.demo02.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

@Component
@Slf4j
public class EventBus {
    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    private ReactiveRedisMessageListenerContainer listener;

    private static final String RUN_EKY = "run:%s";
    private static final String END_EKY = "__END__";
    private static final String SEQ_KEY = "seq:%s";
    private static final String TOPIC_KEY = "topic:%s";

    public void write(String runId, String chunk) {
        redisTemplate.opsForValue()
                .increment(SEQ_KEY.formatted(runId))
                .doOnNext(seq -> {
                    StringRecord record = StreamRecords.string(Map.of("seq", String.valueOf(seq), "chunk", chunk))
                            .withStreamKey(RUN_EKY.formatted(runId));
                    redisTemplate.opsForStream().add(record).subscribe();
                    redisTemplate.convertAndSend(TOPIC_KEY.formatted(runId), JsonUtil.json(new ChunkEntity(seq, chunk))).subscribe();
                })
                .subscribe();
    }

    public void writeEnd(String runId) {
        write(runId, END_EKY);
    }


    public Flux<ChunkEntity> subscribe(String runId, long lastSeq) {
        Flux<ChunkEntity> history = redisTemplate.opsForStream().range(RUN_EKY.formatted(runId), Range.unbounded())
                .flatMap(record -> {
                    long seq = Long.parseLong(record.getValue().get("seq").toString());
                    String chunk = record.getValue().get("chunk").toString();
                    return Flux.just(new ChunkEntity(seq, chunk));
                }).filter(chunk -> chunk.seq() > lastSeq);


        Flux<ChunkEntity> live = listener.receive(ChannelTopic.of(TOPIC_KEY.formatted(runId)))
                .map(ReactiveSubscription.Message::getMessage)
                .map(chunk -> (ChunkEntity) JsonUtil.entity(chunk, ChunkEntity.class));

        return history.concatWith(live)
                .doOnNext(chunk -> log.info("[RESP]: {}", chunk))
                .takeWhile(chunk -> !END_EKY.equals(chunk.chunk()));
    }
}
