package org.demo05.controller;


import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/demo05/graph")
public class TestController {
    @Autowired
    private ChatClient client;

    @Autowired
    private CompiledGraph routerGraph;

    @GetMapping("/chat")
    public String test(@RequestParam String prompt, @RequestHeader String sessionId) {
        return client.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
    }

    @GetMapping("/route")
    public String route(@RequestParam String prompt, @RequestHeader String sessionId) {
        OverAllState result = routerGraph
                .invoke(Map.of("user_query", prompt, "sessionId", sessionId))
                .orElseThrow(() -> new IllegalStateException("graph returned empty state"));

        return result.value("answer").orElse("no answer").toString();
    }
}
