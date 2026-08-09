package demo.demo03.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/demo03")
public class ChatController {
    @Autowired
    private ChatClient client;

    @GetMapping("/chat")
    public Map<String, Object> chat(String sessionId, String prompt) {
        ChatResponse response = client.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call()
                .chatResponse();

        Usage usage = response.getMetadata().getUsage();

        return Map.of("result", response.getResult().getOutput().getText(),
                "promptTokens", usage.getPromptTokens(),
                "totalTokens", usage.getTotalTokens()
        );
    }
}
