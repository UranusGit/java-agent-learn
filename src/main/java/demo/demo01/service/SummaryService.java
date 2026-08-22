package demo.demo01.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SummaryService {
    @Autowired
    @Qualifier("liteChatClient")
    private ChatClient client;

    private static final String SUMMARY_PROMPT = """
            你是客服会话压缩器。把下面【历史消息】压缩成一段不超过150字的中文摘要。
            硬性要求（逐条保留，缺一不可）：
            1.订单号、工单号、物流单号等全部编原样保留；
            2.客服已承诺的事项，（如“加急处理” “48小时内回复”）逐条列全，不得遗漏；
            3.用于已提供的关键实体（尺码，收货地址，联系方式，原因）一并不许丢
            4.省略寒暄、客套与事实无关的话。只输出摘要正文，不要任何前缀或“摘要：”字样。
            """;

    public String summarize(List<Message> history) {
        String historyText = history.stream()
                .map(m -> "[" + m.getMessageType() + "]" + m.getText())
                .collect(Collectors.joining("\n"));

        return client.prompt()
                .system(SUMMARY_PROMPT)
                .user(historyText)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID()))
                .call()
                .content();
    }

}
