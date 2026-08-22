package demo.demo01.controller;

import demo.demo01.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo01")
public class ChatController {
    @Autowired
    private ChatService service;

    @GetMapping("/chat")
    public String chat(@RequestParam String prompt, @RequestHeader String sessionId) {
        return service.chat(prompt, sessionId);
    }

    @GetMapping("/stream")
    public Flux<String> stream(@RequestParam String prompt, @RequestHeader String sessionId) {
        return service.stream(prompt, sessionId);
    }
}
