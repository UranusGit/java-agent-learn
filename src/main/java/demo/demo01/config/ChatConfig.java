package demo.demo01.config;

import demo.demo01.tools.TimeTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(" 你必须严格遵守：在发起任何工具调用之前，先输出一段以【调用说明】开头的话，解释你需要什么信息、为什么调用该工具。输出【调用说明】之后才允许调用工具。")
                .defaultTools(new TimeTool())
                .defaultAdvisors(new SimpleLogAdvisor())
                .build();
    }
}
