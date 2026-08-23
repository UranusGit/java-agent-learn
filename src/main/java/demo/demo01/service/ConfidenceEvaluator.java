package demo.demo01.service;

import demo.demo01.dto.ConfidenceAssessment;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ConfidenceEvaluator {
    private static final String SELF_CHECK_PROMPT = """
            你是客服回复审计员。对照下面的【参考片段】与用户问题，评估能否负责人地回答：
            - 若参考片段/工具结果足以支撑回答 → hasAnswer=true、confidence 取0.8~1.0
            - 若不足以回答 → hasAnswer=true、confidence 取 0.0~0.4，并 missingInfo 说明缺什么
            - 输出 JSON：confidence（0.0~1.0）、hasAnswer（boolean）、missingInfo（字符串，无则空）
            """;


    @Autowired
    @Qualifier("assistantChatClient")
    private ChatClient client;

    public ConfidenceAssessment evaluate(String retrievedContext, String userMessage) {
        return client.prompt()
                .system(SELF_CHECK_PROMPT)
                .user("【参考片段】\n" + retrievedContext + "\n【用户问题】\n" + userMessage)
                .call()
                .entity(ConfidenceAssessment.class, ChatClient.EntityParamSpec::validateSchema);
    }
}
