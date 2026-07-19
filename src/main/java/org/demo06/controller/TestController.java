package org.demo06.controller;

import org.demo06.workflows.chaining.PromptChainingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo06/workflow")
public class TestController {
    @Autowired
    private ChatClient client;

    @Autowired
    private PromptChainingAdvisor promptChainingAdvisor;

    @GetMapping("/chat")
    public String chat(@RequestParam String prompt, @RequestHeader String sessionId) {
        return client.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .advisors(promptChainingAdvisor)
                .call()
                .content();
    }
}
