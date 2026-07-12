package org.demo02.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    @Autowired
    private ChatClient client;

    @GetMapping("/hello")
    private String chat(@RequestParam(defaultValue = "你好") String prompt) {
        return client.prompt()
                .user(prompt)
                .call()
                .content();
    }
}
