package demo.demo01.controller;

import demo.demo01.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo01")
public class ChatController {
    @Autowired
    private ChatService service;

    @GetMapping("/chat")
    public String chat(@RequestParam String prompt) {
        return service.chat(prompt);
    }

    @GetMapping("/stream")
    public Flux<String> stream(@RequestParam String prompt) {
        return service.stream(prompt);
    }
}
