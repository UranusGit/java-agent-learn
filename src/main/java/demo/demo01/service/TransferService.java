package demo.demo01.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

@Service
public class TransferService {
    private static final String KEY_PREFIX = "transfer:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final ObjectMapper mapper = new ObjectMapper();
    @Autowired
    private StringRedisTemplate redis;

    public void enqueue(String sessionId, String summary, String missingInfo) {
        Map<String, String> doc = Map.of("sessionId", sessionId,
                "summary", summary,
                "missingInfo", missingInfo);

        redis.opsForValue().set(KEY_PREFIX + sessionId, mapper.writeValueAsString(doc), TTL);
    }
}
