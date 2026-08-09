# 附录：SSE 与 WebSocket

> 流式输出底层协议卡壳时来这补基础。
>
> **企业级技术选型结论**：Agent 流式输出统一使用 **SSE**。WebSocket 仅在真正需要双向实时通信（如多人协作编辑）时才考虑，Agent 场景没有这个需求。

---

## 技术选型对比

| 维度 | SSE ✅ | WebSocket |
|------|--------|-----------|
| **方向** | 服务器 → 客户端（单向） | 双向 |
| **协议** | HTTP（标准 80/443） | WS（需 Upgrade 握手） |
| **浏览器 API** | EventSource（内置） | WebSocket API |
| **自动重连** | ✅ 原生内置（断线自动重连） | ❌ 需手动实现 |
| **断点续传** | ✅ Last-Event-ID 请求头 | ❌ 无标准机制 |
| **代理/防火墙** | ✅ 兼容所有 HTTP 基础设施 | ❌ 部分代理不支持 Upgrade |
| **CDN/Nginx** | ✅ 零额外配置 | ❌ 需特殊配置 |
| **Spring AI 对接** | ✅ `.stream()` 返回 `Flux<String>`，天然映射 `text/event-stream` | ❌ 需额外 Handler 封装 |
| **适用场景** | AI 对话流式输出、通知推送、进度更新 | 聊天室、协作编辑、游戏 |

**结论**：Agent 对话是"用户 POST 一条消息 → 服务端流式返回 Token"的单向流。SSE 是企业级 AI 对话的标准选型——WebSocket 的双向能力在这里是浪费，还会增加部署复杂度。

---

## SSE 详细用法

### 服务端（Spring AI + SSE）

```java
@GetMapping(value = "/chat/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(@RequestParam String question) {
    return chatClient.prompt()
        .user(question)
        .stream()
        .content()
        .map(chunk -> ServerSentEvent.<String>builder()
            .id(UUID.randomUUID().toString())  // Last-Event-ID 支持
            .event("token")
            .data(chunk)
            .build())
        .concatWith(Flux.just(ServerSentEvent.<String>builder()
            .event("done")
            .data("[DONE]")
            .build()));
}
```

### 客户端（浏览器 EventSource）

```javascript
// SSE 订阅——原生 EventSource，自动重连
const eventSource = new EventSource("/chat/stream?question=你好");

eventSource.addEventListener("token", (event) => {
    appendToChat(event.data);  // 流式追加 Token
});

eventSource.addEventListener("done", (event) => {
    eventSource.close();  // Agent 完成
});

// 断线时自动重连——无需手动处理
eventSource.onerror = () => {
    // SSE 原生重连机制会自动恢复
    // 可选：显示"重新连接中..."提示
};
```

### 多标签页同步（SSE 广播）

```java
/**
 * 同一 session 的多个标签页同步接收流式输出
 */
@Service
public class SessionSseManager {

    private final Map<String, Set<SseEmitter>> sessionEmitters =
        new ConcurrentHashMap<>();

    public SseEmitter subscribe(String sessionId) {
        var emitter = new SseEmitter(0L);
        sessionEmitters.computeIfAbsent(sessionId,
            k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        return emitter;
    }

    /**
     * 广播到同一 session 的所有标签页
     */
    public void broadcast(String sessionId, String chunk) {
        var emitters = sessionEmitters.get(sessionId);
        if (emitters == null) return;
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(chunk));
            } catch (IOException e) {
                emitter.complete();
            }
        }
    }
}
```

---

## 断线重连（Last-Event-ID）

SSE 原生支持断点续传：

```java
@GetMapping(value = "/chat/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam String sessionId,
        @RequestHeader(value = "Last-Event-ID",
                       required = false) String lastEventId) {

    var emitter = new SseEmitter(0L);

    // 如果有 Last-Event-ID，从断点继续
    var startFrom = lastEventId != null
        ? Long.parseLong(lastEventId)
        : 0;

    // 发送从 startFrom 开始的消息
    var messages = historyService.getMessagesSince(sessionId, startFrom);
    for (var msg : messages) {
        emitter.send(SseEmitter.event()
            .id(msg.id().toString())
            .data(msg.content()));
    }

    return emitter;
}
```

---

## SSE 在本项目中的应用

| 场景 | 使用方式 |
|------|---------|
| Agent 对话流式输出 | `ChatClient.stream()` → `ServerSentEvent` |
| 多标签页同步 | `SessionSseManager` 广播到 `Set<SseEmitter>` |
| 摄入进度推送 | `IngestPipeline` → SSE 事件流 |
| 流程引擎节点进度 | DAG 引擎 → SSE 节点状态推送 |
| 审批通知 | `ConfirmationNotifier` → SSE 确认请求 |
| 监控看板 | 定时 SSE 推送健康指标 |
| 飞轮事件 | 采集/标注/评估/部署事件 SSE 流 |

---

## 相关文档

- 流式输出：`阶段2-核心能力/05-流式输出.md`
- 历史持久化与会话广播：`阶段4-生产化/26-历史持久化与会话广播.md`
