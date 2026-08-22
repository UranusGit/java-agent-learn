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

    @Autowired
    private SlotManager slotManager;

    public Flux<String> stream(String prompt, String sessionId) {
        IntentResult intentResult = classifier.classify(prompt);
        switch (intentResult.intent()) {
            case CHITCHAT -> {
                // 闲聊：轻量链，无 RAG/工具/记忆
                return liteChatClient.prompt()
                        .user(prompt)
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                        .stream()
                        .content();
            }
            case FAQ -> {
                // 知识问答：完整链，带 RAG 检索（工具是配角）
                return client.prompt()
                        .user(prompt)
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                        .stream()
                        .content();
            }
            case BUSINESS -> {
                // 业务：先过槽位闭环
                SlotManager.SlotTurn turn = slotManager.handle(sessionId, prompt);   // ① 读→抽→判→写
                if (!turn.ready()) {
                    // ② 缺槽：走轻量链生成自然问句（槽位指令禁止它调工具）
                    return liteChatClient.prompt()
                            .system(turn.directive())       // "还缺订单号/新尺码，必须追问，禁止调工具"
                            .user(prompt)
                            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                            .stream()
                            .content();
                }
                // ③ 齐全：走完整链（完整链才有 createExchange 工具），槽位指令声明可调工具
                return client.prompt()
                        .system(turn.directive())           // "槽位已齐全，可以调用 createExchange 工具"
                        .user(prompt)
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                        .stream()
                        .content();
            }
            default -> throw new IllegalStateException("未知意图: " + intentResult.intent());
        }
    }
}
