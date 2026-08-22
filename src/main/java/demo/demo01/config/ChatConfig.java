package demo.demo01.config;

import demo.demo01.tools.FaqTool;
import demo.demo01.tools.OrderTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    // 完整链路：记忆 + RAG + 工具（FAQ 与业务办理共用）
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory, VectorStore vectorStore) {
        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .similarityThreshold(0.3)
                        .build())
                .build();
        return builder
                .defaultSystem("你是电商客服小智，只能依据工具查询结果回答，不要编造订单/物流信息。")
                .defaultTools(new FaqTool(), new OrderTool())
                .defaultAdvisors(ragAdvisor, MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    // 轻量链路：只保留人格与记忆，闲聊不检索、不挂工具
    @Bean
    public ChatClient liteChatClient(ChatClient.Builder builder, ChatMemory memory) {
        return builder
                .defaultSystem("你是电商客户小智。用户在和你寒暄，简短友好地回应即可，不要主动推销。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    /**
     * 无记忆辅助链：无 RAG / 无工具 / 无记忆——态度分类、槽位抽取、历史摘要三类单轮无状态任务共用。
     * <p>
     * 关键：不挂 MessageChatMemoryAdvisor、不依赖 chatMemory，因此既不与 SummarizingChatMemory
     * 构成构造器循环依赖，也不背会话历史。分类/抽取/摘要都不需要历史——它们只判定「这一句」。
     * 分类器、SlotExtractor、SummaryService 三者注入的都是它（同一个 prototype Builder 的多次 build 之一）。
     */
    @Bean
    public ChatClient assistantChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是电商客服的辅助判定模型。只依据给定指令分析输入，不主动编造上下文。")
                .build();
    }
}
