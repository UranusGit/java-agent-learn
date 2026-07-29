# SSE 协议详解（Server-Sent Events）

> **配套文档**：[35-管数分离实战](../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 从第 1 章到第 11 章全程用 SSE 做流式推送——`ServerSentEvent`、`id`、`event`、`Last-Event-ID`、心跳。但主线没把 SSE 协议本身讲透。本篇把 SSE 从协议层讲到生产细节。
>
> **难度假设**：你用过 `return Flux<String>` 做接口，但不清楚 SSE 协议长什么样、和 WebSocket 啥区别、断线重连怎么自动工作。

---

## 第 1 章：SSE 到底是什么

### 1.1 一句话

**SSE（Server-Sent Events）是一种基于 HTTP 的、服务器到客户端的单向流式推送协议。**

注意三个关键词：

- **基于 HTTP**：就是普通的 HTTP 响应，只是不结束、一直推。不需要新协议、不需要握手升级。
- **服务器→客户端单向**：只能服务器推给客户端，客户端**不能**通过这个连接往回发（要发就另起一个普通 HTTP 请求）。
- **流式**：服务器随时可以推一段，客户端立刻收到，不等全部完成。

### 1.2 和 WebSocket 的本质区别（高频面试题）

| 维度 | SSE | WebSocket |
|------|-----|-----------|
| 方向 | **单向**（服务器→客户端） | **双向** |
| 协议 | HTTP | 独立的 ws:// 协议（握手后升级） |
| 复杂度 | 低（就是 HTTP 响应） | 高（协议栈、心跳、状态管理） |
| 浏览器支持 | `EventSource` 原生 | `WebSocket` 原生 |
| 断线重连 | **浏览器自动重连** + 带 `Last-Event-ID` | 要自己写重连逻辑 |
| 代理/防火墙友好 | 好（就是 HTTP） | 差（很多代理不支持升级） |
| 适合 | 服务器吐、客户端只读 | 需要客户端中途发指令（聊天、游戏） |

**选型经验**：

- **LLM 流式输出、通知推送、日志流、股票行情**——服务器单向吐、客户端只读 → **SSE**。这就是为什么 ChatGPT、OpenAI/Anthropic API 都用 SSE。
- **聊天室、协同编辑、实时游戏**——客户端要中途发消息 → **WebSocket**。

> **管数分离文档为什么用 SSE**：生成结果是"服务器吐、客户端只读"，中途取消可以用"客户端断连 → 服务端感知"实现，不需要双向。SSE 够用且更轻。文档前言也明确标注了"如果要中途发指令才升级 WebSocket"。

---

## 第 2 章：SSE 协议长什么样（抓包视角）

很多人用 SSE 但从没看过它的原始字节。其实极简单。一个 SSE 响应就是一段长这样的文本流：

```
HTTP/1.1 200 OK
Content-Type: text/event-stream

id: 1
event: token
data: 你

id: 2
event: token
data: 好

: ping

```

**规则**：

- 每行是 `字段: 值` 格式。字段有 `id`、`event`、`data`、`retry`、以及 `:`（注释）。
- **一个空行分隔两个事件**。没有空行，多行会合并成同一个事件。
- `data:` 的值是实际数据。如果数据有多行，要用多个 `data:` 行。
- `:` 开头的行是**注释**（心跳用它，浏览器不触发事件）。

### 2.1 字段详解

| 字段 | 作用 |
|------|------|
| `data:` | 消息内容（前端 `e.data` 拿到） |
| `event:` | 事件类型（前端 `addEventListener('xxx', ...)` 分别处理） |
| `id:` | 事件 ID（浏览器自动记录，断线重连带上） |
| `retry:` | 断线重连前等多少毫秒 |
| `:` (注释) | 不触发事件，用于心跳/keep-alive |

### 2.2 浏览器端怎么收

```javascript
const es = new EventSource("/api/runs/run_xxx/stream");

// 默认监听（没有 event 字段的消息）
es.onmessage = e => console.log(e.data);

// 监听特定事件类型
es.addEventListener("token", e => console.log("收到字:", e.data));
es.addEventListener("done",  e => console.log("结束了"));

// es.lastEventId  ← 浏览器自动记录的最后 id（重连会带上）
```

---

## 第 3 章：SSE 最强大的特性——自动断线重连

### 3.1 浏览器原生行为

`EventSource` 有个杀手锏：**连接断开时，浏览器会自动重连，并自动带上 `Last-Event-ID` 请求头**，值是它记录的最后那个 `id`。

```
正常:  服务器推 id=1, id=2, ... id=15
断网:  连接断开
重连:  浏览器自动发 GET，带请求头 Last-Event-ID: 15
服务端: 读这个头，从 id=15 之后继续推
```

**前端一行重连代码都不用写**。这是 SSE 相比手撸 WebSocket 流式最大的优势。

### 3.2 服务端配合（管数分离文档第 5 章）

```java
@GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(@PathVariable String runId,
        @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {

    long fromSeq = lastEventId != null ? lastEventId : 0;   // 从上次之后续推
    return service.subscribe(runId, fromSeq).map(s -> {
        int idx = s.indexOf("::");
        long seq = Long.parseLong(s.substring(0, idx));
        return ServerSentEvent.<String>builder()
                .id(String.valueOf(seq))     // 让浏览器记录
                .event("token")
                .data(s.substring(idx + 2))
                .build();
    });
}
```

**关键**：每条消息带 `id`，服务端读 `Last-Event-ID` 头续推。这就是"断线不重复、不漏"的标准实现。

### 3.3 retry 字段控制重连节奏

```java
ServerSentEvent.<String>builder().retry(3000L).build()   // 断线后 3 秒重连
```

不设的话浏览器默认约 3 秒。

---

## 第 4 章：Spring Boot 实现 SSE 的三种姿势

### 4.1 最简单：返回 Flux + produces

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream() {
    return Flux.interval(Duration.ofMillis(100)).map(i -> "tick " + i);
}
```

**适合**：纯数据、不需要 id/event 分类。**缺点**：没法设 id、event、retry。

### 4.2 推荐：返回 Flux<ServerSentEvent>

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream() {
    return Flux.interval(Duration.ofMillis(100))
            .map(i -> ServerSentEvent.<String>builder()
                    .id(String.valueOf(i))
                    .event("tick")
                    .data("tick " + i)
                    .build());
}
```

**适合**：需要 id/event/retry 控制的产品级场景。**管数分离文档全程用这种。**

### 4.3 SseEmitter（传统 MVC，非响应式）

```java
@GetMapping("/stream")
public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L);   // 0=不超时
    executor.submit(() -> {
        try {
            for (int i = 0; i < 10; i++) {
                emitter.send(SseEmitter.event().name("tick").data("tick " + i).id(String.valueOf(i)));
                Thread.sleep(100);
            }
            emitter.complete();
        } catch (Exception e) { emitter.completeWithError(e); }
    });
    return emitter;
}
```

**适合**：传统 Spring MVC（非 WebFlux）项目。WebFlux 项目不要用 SseEmitter，用 `Flux<ServerSentEvent>`。

> **踩坑提示**：`SseEmitter` 和 `Flux<ServerSentEvent>` **别混用**。WebFlux 用 Flux，传统 MVC 用 SseEmitter。管数分离文档是 WebFlux，全程 Flux。

---

## 第 5 章：生产级细节（这才是重点）

很多教程到第 4 章就结束了，但真实生产 SSE 还有这些坑。

### 5.1 心跳（防代理掐断）

**问题**：nginx、云负载均衡、浏览器默认会在连接**空闲 60 秒**后掐断长连接。如果服务器暂时没数据推（比如 LLM 还在想），连接就被掐了。

**解决**：定时发**注释行**（`: ping`）保活。注释不触发前端事件，但让代理觉得"连接还活着"：

```java
Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(1))
        .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
return data.mergeWith(heartbeat);
```

> 管数分离文档第 5 章就是这么做的——每秒一个心跳注释。

### 5.2 客户端断开感知（防资源泄漏）

**问题**：客户端关了浏览器，但服务端不知道，还在生成、还在推。生成器白跑、token 白烧。

**解决**：用 `doFinally` 感知取消信号：

```java
return service.subscribe(runId).doFinally(sig -> {
    if (sig == SignalType.CANCEL) {
        log.info("客户端断开，可触发取消生成");
    }
});
```

### 5.3 多行数据/特殊字符

**问题**：`data:` 值里如果有换行，单行 `data:` 会破坏协议。

**解决**：换行用多个 `data:` 行，或把数据 JSON 化：

```java
// 数据是 JSON 最省心
ServerSentEvent.<String>builder().data(objectMapper.writeValueAsString(obj)).build();
```

### 5.4 CORS（跨域）

`EventSource` 默认不带 cookie，跨域要配 CORS + `withCredentials`：

```javascript
const es = new EventSource(url, { withCredentials: true });
```

服务端配 `@CrossOrigin` 或全局 CORS。

### 5.5 浏览器连接数限制

**问题**：HTTP/1.1 下，浏览器对**同一域名**的 SSE 连接数有限（Chrome 通常 6 个）。开 6 个标签页第 7 个就连不上。

**解决**：用 HTTP/2（多路复用，无此限制），或不同域名分摊。

---

## 第 6 章：SSE 经网关/反向代理

### 6.1 nginx 配置（生产常见）

nginx 默认会缓冲响应，导致 SSE 变成"攒一批再发"。必须关缓冲：

```nginx
location /api/ {
    proxy_pass http://backend;
    proxy_buffering off;            # 关键：关缓冲，SSE 才能实时
    proxy_cache off;
    proxy_read_timeout 3600s;        # 读超时加大（SSE 是长连接）
    proxy_set_header Connection '';  # 允许 HTTP/1.1 长连接
    chunked_transfer_encoding on;
}
```

### 6.2 Spring Cloud Gateway

Spring Cloud Gateway 是响应式的，**原生支持 SSE 流式透传**，一般不用额外配置。但要确认没在过滤器里缓冲 body。

---

## 第 7 章：常见坑总结

### 坑 1：接口不流式，一次性吐完

**原因**：`produces` 不是 `text/event-stream`，或返回的是会"收集完再发"的类型。
**解决**：必须 `produces = MediaType.TEXT_EVENT_STREAM_VALUE` + 返回 `Flux`。

### 坑 2：curl 不流式，浏览器流式

**原因**：curl 默认缓冲输出。
**解决**：`curl -N` 关闭缓冲。

### 坑 3：断线重连丢内容 / 重复

**解决**：每条带 `id`，服务端读 `Last-Event-ID` 续推（见第 3 章）。

### 坑 4：连接被代理 60 秒掐断

**解决**：加心跳注释行（见 5.1）。

### 坑 5：客户端断开，服务端还在跑

**解决**：`doFinally` 感知 CANCEL，触发取消（见 5.2）。

### 坑 6：浏览器同域 6 连接限制

**解决**：HTTP/2，或分域名。

### 坑 7：SseEmitter 和 Flux 混用

**解决**：WebFlux 用 `Flux<ServerSentEvent>`，传统 MVC 用 `SseEmitter`，别混。

---

## 总结

- **SSE = 基于 HTTP 的单向流式**，适合"服务器吐、客户端只读"。LLM 流式的事实标准。
- **比 WebSocket 轻**，且浏览器**自动重连 + 带 Last-Event-ID**——这是最大优势。
- **协议极简**：`id/event/data/retry/注释`，空行分隔事件。
- **生产要点**：心跳防掐断、`doFinally` 防泄漏、`id` 配合续传、nginx 关缓冲。
- **Spring Boot**：WebFlux 用 `Flux<ServerSentEvent>`，传统 MVC 用 `SseEmitter`。

学完本篇，[管数分离文档](../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 里所有的 `ServerSentEvent`、`Last-Event-ID`、心跳、CANCEL 就都通透了。
