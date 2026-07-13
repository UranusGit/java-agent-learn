package org.demo02.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo02/devisor")
public class TestController02 {

    @Autowired
    public ChatClient chatClient;

    @GetMapping("/chat")
    public String chat(String prompt) {
        return chatClient.prompt().user(prompt).call().content();
    }

    @GetMapping("/muti-chat")
    public String mutiChat(@RequestParam String prompt, @RequestParam String sessionId) {
        return chatClient.prompt().user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call().content();
    }

    @GetMapping(value = "/muti-chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> mutiChatStream(@RequestParam String prompt, @RequestParam String sessionId) {
        return chatClient.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }
}
