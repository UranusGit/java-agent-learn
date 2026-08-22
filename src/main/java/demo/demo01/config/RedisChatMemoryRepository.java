package demo.demo01.config;

import demo.demo01.util.MessageJsonUtil;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {
    private static final String KEY_PREFIX = "chatmemory:";

    @Autowired
    private RedisTemplate<String, String> redis;

    @Override
    public List<String> findConversationIds() {
        return redis.keys(KEY_PREFIX + "*").stream().toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> entities = redis.opsForList().range(KEY_PREFIX + conversationId, 0, -1);
        return entities.stream()
                .map(e -> (Message) MessageJsonUtil.toObj(e)).toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = KEY_PREFIX + conversationId;
        // 先删掉旧的（覆盖语义），再写入本次窗口 —— 不再 rightPushAll 追加
        redis.delete(key);
        List<String> values = messages.stream()
                .map(MessageJsonUtil::json)
                .toList();
        redis.opsForList().rightPushAll(key, values);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(KEY_PREFIX + conversationId);
    }
}
