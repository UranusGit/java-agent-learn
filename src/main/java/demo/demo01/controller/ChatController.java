package demo.demo01.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/demo01")
public class ChatController {
    @Autowired
    private ChatClient client;

    @GetMapping("/chat")
    public String chat(String prompt) {
        Flux<ChatClientResponse> flux = client.prompt()
                .user(prompt)
                .stream()
                .chatClientResponse();

        StringBuilder builder = new StringBuilder();
        new ChatClientMessageAggregator().aggregateChatClientResponse(flux, aggregated -> {
            builder.append(aggregated.chatResponse().getResult().getOutput().getText());
            System.out.println(builder);
        }).subscribe();

        return builder.toString();
    }
}
