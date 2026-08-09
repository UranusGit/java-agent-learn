# 附录：Redis 速成

> AI 应用中 Redis 的核心用途：会话持久化 / 缓存 / 限流。

## 在 AI 应用中的用途

| 用途 | 说明 |
|------|------|
| **ChatMemory 持久化** | 重启后会话不丢 |
| **语义缓存** | 缓存 LLM 回复 |
| **幂等性 Key** | 防止工具重复执行 |
| **限流** | Bucket4j + Redis 分布式限流 |

## 基本操作

```java
@Service
public class RedisService {

    private final StringRedisTemplate redis;

    // 设置（带过期）
    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    // 获取
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    // 检查存在（幂等性用）
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }
}
```

## ChatMemory 用 Redis 持久化

```java
@Bean
public ChatMemory chatMemory(RedisTemplate<String, String> redis) {
    // 生产环境：用 Redis 替代内存版 ChatMemory
    // 重启后对话历史不丢
    return new RedisChatMemory(redis);  // 自定义实现
}
```

## 相关文档
- 会话持久化：`阶段4-生产化/02-Agent可靠性工程.md`
- 多租户：`阶段5-架构师/02-多租户与权限.md`
