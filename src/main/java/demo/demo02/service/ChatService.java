package demo.demo02.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    @Autowired
    private ChatClient client;

    public String chat(String prompt) {
        return client.prompt()
                .user(prompt)
                .advisors(spec->spec.param(ChatMemory.CONVERSATION_ID,"default"))
                .call()
                .content();
    }
}
