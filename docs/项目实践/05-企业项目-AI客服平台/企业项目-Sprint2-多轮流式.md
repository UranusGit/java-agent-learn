# Sprint 2 详细实现：多轮对话 + 记忆 + 流式

> 目标：支持多轮对话（记住上下文），SSE 流式输出（打字机效果）
> 时间：1.5 周 · 前置：Sprint 1 完成

---

## Day 1-3：会话记忆

### Step 1：Redis 持久化记忆

```java
package com.agentforge.config;

import org.springframework.ai.chat.memory.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class MemoryConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    @Bean
    public ChatMemory chatMemory() {
        // 内存版（Sprint 2 先用内存，Sprint 4 换 Redis 持久化）
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }
}
```

### Step 2：配置 ChatClient 带记忆

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {
    return builder
            .defaultSystem(SYSTEM_PROMPT)
            .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(memory).build()
            )
            .build();
}
```

### Step 3：改造聊天接口

```java
@GetMapping("/chat")
public ApiResponse chat(
        @RequestParam String q,
        @RequestParam(defaultValue = "default") String sessionId) {

    if (q == null || q.isBlank()) {
        return ApiResponse.error("问题不能为空");
    }

    String reply = chatClient.prompt()
            .user(q)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
            .call()
            .content();

    return ApiResponse.ok(Map.of("reply", reply, "sessionId", sessionId));
}
```

### Step 4：测试多轮

```bash
curl "http://localhost:8080/api/chat/chat?q=我叫张三&sessionId=u1"
curl "http://localhost:8080/api/chat/chat?q=我叫什么&sessionId=u1"
# ✅ AI 记住了"张三"

curl "http://localhost:8080/api/chat/chat?q=我叫什么&sessionId=u2"
# ✅ 不同 session，不知道
```

---

## Day 4-7：SSE 流式输出

### Step 5：流式接口

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(
        @RequestParam String q,
        @RequestParam(defaultValue = "default") String sessionId) {

    return chatClient.prompt()
            .user(q)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
            .stream()
            .content()
            .onErrorResume(e -> {
                log.error("流式输出失败", e);
                return Flux.just("\n⚠️ 服务暂时不可用，请稍后重试");
            });
}
```

### Step 6：前端改造为 SSE 流式

```javascript
// 把 fetch 改成 EventSource
async function send() {
    // ...显示用户消息...

    const aiDiv = createAiBubble();
    const content = aiDiv.querySelector('.bubble');

    const es = new EventSource(
        `/api/chat/stream?q=${encodeURIComponent(q)}&sessionId=${getCurrentSessionId()}`
    );

    es.onmessage = function(e) {
        content.textContent += e.data;
        scrollDown();
    };

    es.onerror = function() {
        es.close();
        removeTyping();
        btn.disabled = false;
    };
}
```

### Step 7：会话管理

```java
@RestController
@RequestMapping("/api/session")
public class SessionController {

    @PostMapping("/create")
    public ApiResponse createSession(@RequestHeader("X-Tenant-Id") String tenantId) {
        String sessionId = UUID.randomUUID().toString();
        return ApiResponse.ok(Map.of("sessionId", sessionId));
    }

    @GetMapping("/{sessionId}/history")
    public ApiResponse getHistory(@PathVariable String sessionId) {
        // 从 ChatMemory 读取历史
        // ...
    }
}
```

---

## Day 8-10：Advisor 链（计费 + 日志）

### Step 8：TokenBillingAdvisor

```java
@Component
public class TokenBillingAdvisor implements BaseAdvisor {

    private final AtomicLong totalTokens = new AtomicLong();
    private final Map<String, AtomicLong> tenantTokens = new ConcurrentHashMap<>();

    @Override
    public ChatClientResponse after(ChatClientResponse response) {
        var usage = response.chatResponse().getMetadata().getUsage();
        long tokens = usage.getTotalTokens();
        totalTokens.addAndGet(tokens);
        // 按租户累计
        tenantTokens.computeIfAbsent(getCurrentTenant(), k -> new AtomicLong())
                    .addAndGet(tokens);
        return response;
    }

    public Map<String, Object> getReport(String tenantId) {
        var t = tenantTokens.getOrDefault(tenantId, new AtomicLong(0));
        return Map.of("totalTokens", totalTokens.get(), "tenantTokens", t.get());
    }

    @Override public int getOrder() { return 10; }
}
```

### Step 9：注册 Advisor

```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory,
                              TokenBillingAdvisor billing) {
    return builder
            .defaultSystem(SYSTEM_PROMPT)
            .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(memory).build(),
                billing
            )
            .build();
}
```

---

## Sprint 2 验收

- [ ] 同一 sessionId 能多轮对话
- [ ] 不同 sessionId 互不干扰
- [ ] `/api/chat/stream` 能逐 token 流式输出
- [ ] 前端打字机效果流畅
- [ ] TokenBillingAdvisor 能统计 token
- [ ] 有错误兜底（流不会因异常卡死）

---

## 下一步

→ [Sprint 3：RAG 知识库 + 工具调用](企业项目-Sprint3-RAG工具.md)
