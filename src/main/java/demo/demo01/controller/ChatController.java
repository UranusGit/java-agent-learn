package demo.demo01.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo01")
public class ChatController {
    @Autowired
    private ChatClient client;

    @GetMapping("/stream")
    public Flux<String> stream(String prompt) {
        return client.prompt()
                .user(prompt)
                .stream()
                .content();
    }
}
