package demo.demo01.config;

import demo.demo01.tools.FaqQueryTool;
import demo.demo01.tools.OrderQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是电商客户小智，只能依据工具查询结果回答，不要编造订单/物流信息")
                .defaultTools(new FaqQueryTool(), new OrderQueryTool())
                .build();
    }
}
