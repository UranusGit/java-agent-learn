package demo.demo01.util;

import org.springframework.ai.chat.messages.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class MessageJsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String json(Message message) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", message.getMessageType().toString());
        map.put("content", message.getText());
        return MAPPER.writeValueAsString(map);
    }

    public static Object toObj(String json) {

        Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {
            @Override
            public Type getType() {
                return super.getType();
            }
        });

        String type = (String) map.get("type");
        String content = (String) map.get("content");
        Map<String, Object> metadata = (Map<String, Object>) map.getOrDefault("metadata", Map.of());
        switch (MessageType.valueOf(type)) {
            case USER:
                return UserMessage.builder().text(content).metadata(metadata).build();
            case ASSISTANT:
                return AssistantMessage.builder().content(content).properties(metadata).build();
            case SYSTEM:
                return SystemMessage.builder().text(content).metadata(metadata).build();
            default:
                throw new IllegalStateException("未知消息类型: " + type);
        }
    }
}
