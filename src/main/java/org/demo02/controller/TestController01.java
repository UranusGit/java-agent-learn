package org.demo02.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo02")
public class TestController01 {
    @Autowired
    private ChatClient client;

    @Autowired
    private ChatModel chatModel;

    @GetMapping("/hello")
    private String chat(@RequestParam(defaultValue = "你好") String prompt) {
        return client.prompt()
                .user(prompt)
                .call()
                .content();
    }

    @GetMapping("/raw")
    private Flux<String> raw(String prompt) {
        return chatModel.stream(prompt);
    }
}
