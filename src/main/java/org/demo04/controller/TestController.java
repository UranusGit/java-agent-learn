package org.demo04.controller;

import org.demo04.entity.ResultEntity;
import org.demo04.service.SimpleIngestionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/demo04/rag")
public class TestController {
    @Autowired
    private ChatClient client;

    @Autowired
    private SimpleIngestionService ingestionService;

    @GetMapping("/ingestion")
    private ResultEntity ingestion(@RequestParam String path) {
        int n = ingestionService.ingestPdf(new FileSystemResource("/Volumes/data/电子书/P020230302379013322085.pdf"));
        return ResultEntity.of(Map.of("chunks", n));
    }


    @GetMapping("/ask")
    private ResultEntity ask(@RequestParam String prompt, @RequestHeader String sessionId) {
        String content = client.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .content();
        return ResultEntity.of(Map.of("content", content));
    }
}
