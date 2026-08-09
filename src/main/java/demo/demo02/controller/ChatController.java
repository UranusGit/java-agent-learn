package demo.demo02.controller;

import demo.demo02.entity.ChunkEntity;
import demo.demo02.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/demo02")
public class ChatController {
    @Autowired
    private ChatService service;

    @GetMapping("/chat")
    public Mono<String> chat(String prompt) {
        return service.chat(prompt);
    }

    @GetMapping("/stream")
    public Flux<String> stream(String runId, long lastSeq) {
        Flux<ChunkEntity> stream = service.stream(runId, lastSeq);
        return stream.flatMap(chunkEntity -> Flux.just(chunkEntity.chunk()));
    }
}
