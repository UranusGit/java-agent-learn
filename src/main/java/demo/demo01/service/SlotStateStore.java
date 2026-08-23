package demo.demo01.service;

import demo.demo01.dto.ExchangeSlotState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

@Service
public class SlotStateStore {
    private static final String KEY_PROXY = "slot:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private StringRedisTemplate redis;

    public ExchangeSlotState get(String sessionId) {
        String key = KEY_PROXY + sessionId;
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return new ExchangeSlotState(sessionId, null, null, "COLLECTING");
        }
        Map<String, String> doc = mapper.readValue(json, Map.class);
        return new ExchangeSlotState(sessionId,
                doc.get("orderId"),
                doc.get("newSize"),
                doc.getOrDefault("status", "COLLECTING")
        );
    }

    // 覆盖写回（槽位是整份工单状态，用覆盖语义，勿追加）
    public void save(ExchangeSlotState state) {
        String key = KEY_PROXY + state.sessionId();
        Map<String, String> doc = Map.of(
                "orderId", state.orderId(),
                "newSize", state.newSize(),
                "status", state.status()
        );
        redis.opsForValue().set(key, mapper.writeValueAsString(doc), TTL);
    }

    // 删除（业务办结或用户放弃）
    public void delete(String sessionId) {
        redis.delete(KEY_PROXY + sessionId);
    }
}
