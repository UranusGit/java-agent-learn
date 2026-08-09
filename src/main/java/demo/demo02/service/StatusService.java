package demo.demo02.service;

import demo.demo02.content.KeyContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatusService {
    @Autowired
    private StringRedisTemplate redisTemplate;

    public void session(String sessionId, String status) {
        redisTemplate.opsForValue().set(KeyContent.SESSION_KEY.formatted(sessionId), status);
    }

    public String session(String sessionId) {
        return redisTemplate.opsForValue().get(sessionId);
    }
}
