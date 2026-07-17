package org.demo02.controller;

import org.demo02.tools.EmailToos;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/demo02/email")
public class TestController03 {
    @Autowired
    private ChatClient chatClient;


    @Autowired
    private EmailToos emailToos;


    @GetMapping("/send-email")
    public String sendEmail(@RequestParam String prompt, @RequestHeader String sessionId) {
        return chatClient.prompt()
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .user(prompt)
                .tools(emailToos)
                .toolContext(Map.of("id", "你好"))
                .call()
                .content();
    }
}
