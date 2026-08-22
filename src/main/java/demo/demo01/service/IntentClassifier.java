package demo.demo01.service;

import demo.demo01.dto.IntentResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class IntentClassifier {
    private static final String INTENT_PROMPT = """
            你是电商客服消息的意图分类器。把用户消息分类为：
            - CHITCHAT：寒暄、表达情绪、与业务无关的闲聊
            - FAQ：咨询政策，商品参数、使用方法等知识性问题
            - BUSINESS：换货、退款、查订单、改地址等需要执行操作的业务
            confidence 取 0.0~1.0；reason 用一句话给出分类依据。
            """;

    @Autowired
    @Qualifier("chatClient")
    private ChatClient client;

    // validateSchema 让结构化输出前，先使用 schema 校验
    public IntentResult classify(String message) {
        return client.prompt()
                .system(INTENT_PROMPT)
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID()))
                .call()
                .entity(IntentResult.class, ChatClient.EntityParamSpec::validateSchema);
    }
}
