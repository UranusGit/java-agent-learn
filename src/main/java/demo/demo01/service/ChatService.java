package demo.demo01.service;

import demo.demo01.dto.IntentResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {
    @Autowired
    @Qualifier("chatClient")
    private ChatClient client;

    @Autowired
    @Qualifier("liteChatClient")
    private ChatClient liteChatClient;

    @Autowired
    private IntentClassifier classifier;

    public Flux<String> stream(String prompt, String sessionId) {
        IntentResult intentResult = classifier.classify(prompt);
        ChatClient target = switch (intentResult.intent()) {
            case CHITCHAT -> liteChatClient;
            case FAQ, BUSINESS -> client;
        };

        return target.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content();
    }
}
