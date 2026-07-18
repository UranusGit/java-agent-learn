package org.demo02.controller;

import org.demo02.entity.User;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo02/user")
public class TestController04 {
    @Autowired
    private ChatClient chatClient;


    @GetMapping("/create-user")
    public User createUser(@RequestParam String prompt, @RequestHeader String sessionId) {
        return chatClient.prompt()
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .user(prompt)
                .call()
                .entity(User.class);
    }
}
