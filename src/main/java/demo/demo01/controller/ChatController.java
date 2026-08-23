package demo.demo01.controller;

import demo.demo01.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo01")
public class ChatController {
    @Autowired
    private ChatService service;

    @GetMapping("/stream")
    public Flux<ServerSentEvent<String>> stream(@RequestParam String prompt, @RequestHeader String sessionId) {
        return service.stream(prompt, sessionId)
                .map(token -> {
                    if (token.startsWith("【TRANSFER】")) {
                        return ServerSentEvent.<String>builder()
                                .event("TRANSFER")
                                .data(token.substring("【TRANSFER:".length(), token.length() - 1))
                                .build();
                    }
                    if (token.startsWith("\\n[TRANSFER_OPTIONAL:")) {
                        return ServerSentEvent.<String>builder()
                                .event("TRANSFER_OPTIONAL")
                                .data("1")
                                .build();
                    }
                    return ServerSentEvent.<String>builder()
                            .event("token")
                            .data(token)
                            .build();
                }).concatWith(Flux.just(ServerSentEvent.<String>builder().event("done").data("【DONE】").build()));
    }
}
