package demo.demo01.service;

import demo.demo01.dto.ExchangeSlotState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SlotExtractor {
    private static final String EXTRACT_PROMPT = """
            你是换货业务的槽位抽取器。从用户这条消息理提取订单号和新尺码：
            - orderId：以DD开头的订单号；没提到则为 null。
            - newSize：要更换到的尺码（S/M/L/XL……）；没提到则为 null
            只输出 JSON，字段没有就填 null。
            """;

    @Autowired
    @Qualifier("assistantChatClient")
    public ChatClient liteChatClient;

    public ExchangeSlotState applyTo(String userMessage, ExchangeSlotState current) {
        SlotValues extracted = liteChatClient.prompt()
                .system(EXTRACT_PROMPT)
                .user(userMessage)
                .call()
                .entity(SlotValues.class, ChatClient.EntityParamSpec::validateSchema);

        return new ExchangeSlotState(
                current.sessionId(),
                extracted.orderId != null ? extracted.orderId : current.orderId(),
                extracted.newSize != null ? extracted.newSize : current.newSize(),
                current.status()
        );
    }

    public record SlotValues(String orderId, String newSize) {
    }
}
