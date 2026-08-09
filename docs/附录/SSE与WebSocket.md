# 附录：SSE 与 WebSocket

> 流式输出底层协议卡壳时来这补基础。

## SSE（Server-Sent Events）

| 特点 | 说明 |
|------|------|
| 方向 | 服务器 → 客户端（单向） |
| 协议 | HTTP |
| 浏览器支持 | EventSource API（内置） |
| Spring AI | 流式输出默认用 SSE |

```java
// Spring 返回 SSE
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream() { ... }
```

```javascript
// 浏览器消费
const es = new EventSource("/stream");
es.onmessage = (e) => console.log(e.data);
```

## WebSocket

| 特点 | 说明 |
|------|------|
| 方向 | 双向 |
| 协议 | WS |
| 适用 | 实时双向通信（聊天室、协作） |

> AI 流式输出用 SSE 就够了——只需要服务器→客户端单向推送。

## 相关文档
- 流式输出：`阶段2-核心能力/05-流式输出.md`
