package demo.demo01.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {
    @Autowired
    private ChatClient client;

    public String chat(String prompt) {
        return client.prompt()
                .user(prompt)
                .call()
                .content();
    }

    public Flux<String> stream(String prompt) {
        return client.prompt()
                .user(prompt)
                .stream()
                .content();
    }
}
