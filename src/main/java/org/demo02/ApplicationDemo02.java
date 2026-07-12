package org.demo02;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ApplicationDemo02 {
    public static void main(String[] args) {
        SpringApplication.run(ApplicationDemo02.class, args);
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem("使用海盗风格的回复方式").build();
    }
}
