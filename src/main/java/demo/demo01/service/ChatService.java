package demo.demo01.service;

import demo.demo01.dto.ConfidenceAssessment;
import demo.demo01.dto.IntentResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

    @Autowired
    private TransferService transferService;
    @Autowired
    private PgVectorStore vectorStore;
    @Autowired
    private ConfidenceEvaluator confidenceEvaluator;

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
                return maybeTransferToHuman(intentResult, prompt, sessionId, () -> client.prompt()
                        .user(prompt)
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                        .stream()
                        .content());
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
                return maybeTransferToHuman(intentResult, prompt, sessionId, () -> client.prompt()
                        .system(turn.directive())           // "槽位已齐全，可以调用 createExchange 工具"
                        .user(prompt)
                        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                        .stream()
                        .content());
            }
            default -> throw new IllegalStateException("未知意图: " + intentResult.intent());
        }
    }

    /**
     * 双信号判定：用检索上下文预判能否负责任回答。
     * 自评中等 正常回复 + 末尾附加转人工提示
     * 低置信度 直接转人工
     * 转份工返回 Flux.just
     */
    private Flux<String> maybeTransferToHuman(IntentResult intentResult, String prompt, String sessionId, Supplier<Flux<String>> normalReply) {
        // 检索上下文（自评信号需要）--与 Advisor 内RAG同库，仅仅多抓一份给判定用
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().topK(3).similarityThreshold(0.3).query(prompt).build()
        );
        String retrieved = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        // 信号2，自评
        ConfidenceAssessment ca = confidenceEvaluator.evaluate(retrieved, prompt);

        if (intentResult.confidence() < 0.6f || ca.confidence() < 0.5f) {
            transferService.enqueue(sessionId, prompt, ca.massingInfo());
            return Flux.just("【TRANSFER:正在为您转接人工，已通知坐席接手】");
        }
        // 自评中等，正常回复后附加转人工入口
        if (ca.confidence() < 0.7f) {
            return normalReply.get().concatWith(Flux.just("\n【TRANSFER_OPTIONAL：若认为解决，可一键转人工】"));
        }
        // 高置信度，正常回复即可
        return normalReply.get();
    }
}
