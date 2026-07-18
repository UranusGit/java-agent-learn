# 08 - WebSocket 重连：seq + 断线重放

> 本章目标：前端网络抖动 / 关闭浏览器后重开时，能从断点继续。
> 完成后：弱网体验稳定，长会话不丢消息。
>
> **重要升级**：本章只重放 `messages`。
> 完整版应当重放 [17 章的 agent_events](./17-全链路可观测前端.md)（12 类事件），
> 让前端重连后能恢复完整活动流，而不只是消息列表。
> 重放范围建议：`SELECT * FROM agent_events WHERE session_id=? AND seq>?`。
>
> **Web 项目专项升级**（[22 章](./22-跨标签页与实时协作.md)）：
> - 跨 tab 同步用 BroadcastChannel（22 §3）；
> - iOS Safari 后台冻结用 Visibility API + 25s 心跳（22 §2）；
> - localStorage 5MB 不够用，活动流上 IndexedDB（22 §2）；
> - 离线消息队列（22 §2）；
> - 跨节点 WebSocket 广播用 Redis pub/sub（[27 章 §3](./27-生产部署深度.md)）。
>
> 本章先做"单 tab 单进程"的最简重连，Web 工程层在 22 章补回。

---

## 本章五步地图

| 步 | 节 | 你要带走什么 |
|----|----|---------|
| ① 痛点 | §1 | 07 章虽然持久化了，但 WS 一断消息就丢——没有"重连重放"机制 |
| ② 最小实现 | §2–§4 | Redis seq 分配 + WS 协议加 `?last_seq=` + 前端指数退避重连 |
| ③ 验证 | §5 | 拔网线 / 关浏览器再开，消息一条不丢 |
| ④ 对照 | §6 | 与 07 章的"消息可达性"对比 |
| ⑤ 避坑 | §7 | 退避风暴 / 多设备 / seq 跳跃 / iOS 后台冻结 |

---

## 1. 痛点：07 章只解决了"服务端存住"，没解决"客户端看到"

07 章让消息落了 DB / JSONL，但只在**前端恰好在线**时才能收到——一旦下面任一情况发生，**当前正在流式的消息就丢了**：

- 网络抖动：手机切 4G/Wi-Fi
- 浏览器 tab 切到后台：iOS Safari 30s 后冻结 WS
- 服务端重启发布：连接被踢
- 笔记本盖上盖子：休眠后 WS 已死

更尴尬的是——**用户看不到"丢了"，他以为还没生成完**。要么干等，要么刷新页面从头开始（既浪费 token 又丢上下文）。

> 这一章的解决思路其实只有一句：**给每条 state 发一个单调递增的 seq，前端重连时带上 `last_seq`，服务端把 `seq > last_seq` 的全部重放**。
>
> 但实施起来有很多细节：seq 放哪（Redis）？怎么原子自增？退避策略？多设备怎么处理？这些就是本章要回答的。

## 2. 设计要点

- 每个 session 维护一个单调递增的 `seq`；
- 服务端把每个 seq 对应的 state 落地（DB 索引 + JSONL）；
- 前端重连时带上 `last_seq`，服务端重放 `seq > last_seq` 的所有 state。

---

## 3. 后端：Seq 落地

### 3.1 给 messages 表加 seq

新增 `V2__add_seq.sql`：

```sql
-- 本代码仅作学习材料参考
ALTER TABLE messages ADD COLUMN seq BIGINT;
CREATE INDEX idx_messages_seq ON messages(session_id, seq);
```

### 3.2 SessionSeqAllocator

新建 `src/main/java/org/demo02/webclaude/session/SessionSeqAllocator.java`：

```java
// 本代码仅作学习材料参考
package org.demo02.webclaude.session;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SessionSeqAllocator {

    private final StringRedisTemplate redis;

    public SessionSeqAllocator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long next(UUID sessionId) {
        return redis.opsForValue().increment("seq:" + sessionId);
    }

    public long current(UUID sessionId) {
        String v = redis.opsForValue().get("seq:" + sessionId);
        return v == null ? 0 : Long.parseLong(v);
    }
}
```

---

## 4. WebSocket 协议升级

### 4.1 服务端处理 resume_from

修改 `SessionWebSocketHandler`：

```java
// 本代码仅作学习材料参考
@Override
public void afterConnectionEstablished(WebSocketSession ws) {
    UUID sessionId = extractSessionId(ws);
    long lastSeq = extractLastSeq(ws);  // from query string ?last_seq=42

    if (lastSeq > 0) {
        replaySince(ws, sessionId, lastSeq);
    }
}

private void replaySince(WebSocketSession ws, UUID sessionId, long lastSeq) {
    // 1. 从 DB 查 seq > lastSeq 的所有 messages
    List<MessageEntity> missed = messageRepo.findBySessionIdAndSeqGreaterThan(sessionId, lastSeq);
    // 2. 依次发送
    for (MessageEntity e : missed) {
        sendState(ws, e);
    }
}
```

### 4.2 状态广播带 seq

每次发 state：

```java
// 本代码仅作学习材料参考
private void sendState(WebSocketSession ws, State s) {
    long seq = seqAllocator.next(s.sessionId());
    Map<String, Object> wire = toWire(s);
    wire.put("seq", seq);
    ws.sendMessage(new TextMessage(om.writeValueAsString(wire)));
}
```

---

## 5. 前端：断线重连 + seq 跟踪

### 5.1 改造 SessionWS

```typescript
// 本代码仅作学习材料参考
export class SessionWS {
  private ws: WebSocket | null = null;
  private lastSeq = 0;
  private listeners: ((msg: WSMessage) => void)[] = [];
  private reconnectAttempts = 0;

  constructor(
    private baseUrl: string,
    private sessionId: string,
  ) {}

  connect(onOpen?: () => void) {
    const url = `${this.baseUrl}?session_id=${this.sessionId}&last_seq=${this.lastSeq}`;
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.reconnectAttempts = 0;
      onOpen?.();
    };

    this.ws.onmessage = (e) => {
      const msg: WSMessage = JSON.parse(e.data);
      if ('seq' in msg && typeof msg.seq === 'number') {
        this.lastSeq = Math.max(this.lastSeq, msg.seq);
      }
      this.listeners.forEach((l) => l(msg));
    };

    this.ws.onclose = () => {
      this.reconnectAttempts++;
      const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
      setTimeout(() => this.connect(onOpen), delay);
    };

    this.ws.onerror = () => {
      this.ws?.close();
    };
  }

  send(msg: WSMessage) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    } else {
      // 队列化，等重连后发
    }
  }

  onMessage(l: (msg: WSMessage) => void) {
    this.listeners.push(l);
  }
}
```

### 5.2 状态持久化

```typescript
// 本代码仅作学习材料参考
// 把 lastSeq 也存 localStorage，关浏览器后能恢复
const saveSeq = (sessionId: string, seq: number) => {
  localStorage.setItem(`seq:${sessionId}`, String(seq));
};

const loadSeq = (sessionId: string): number => {
  const v = localStorage.getItem(`seq:${sessionId}`);
  return v ? Number(v) : 0;
};
```

---

## 6. 验证：测试重连

### 6.1 流程

1. 打开浏览器开始对话；
2. 在对话过程中关掉 Wi-Fi（或用 Chrome DevTools 的 "Offline"）；
3. 等几秒，重新联网；
4. 浏览器自动重连，从断点继续。

**检查点 08-1**：断网期间发的消息，重连后能恢复。

### 6.2 关闭浏览器场景

1. 关闭浏览器（不点 abort）；
2. 后端继续跑（如果是后台任务）；
3. 重新打开浏览器 → 自动重连 → 重放 missed states。

**检查点 08-2**：关闭再打开，状态完整恢复。

---

## 7. 对照：与 07 章的消息可达性差异

| 维度 | 07 章 | 08 章 |
|------|-------|-------|
| 服务端存储 | ✅ JSONL + DB 索引 | ✅ 同 |
| 在线接收 | ✅ WS 推送 | ✅ 同 |
| 离线期间的消息 | ❌ 丢 | ✅ 重放 |
| 网络抖动 | ❌ 干等 / 刷新 | ✅ 自动重连 + 重放 |
| 关浏览器再开 | ❌ 接不上 | ✅ last_seq 续传 |
| 多设备同时打开 | ❌ 只一个收到 | ⚠️ 都收（v1 简化） |

## 8. 避坑：重连机制常踩的雷

| 雷 | 现象 | 规避 |
|----|------|------|
| 退避风暴（多客户端同时重连） | 服务端瞬时连接暴涨 | 退避加 jitter（随机抖动）|
| seq 用 MySQL 自增 | 单点性能瓶颈 / 主从延迟 | Redis `INCR` 单线程原子 |
| `last_seq` 持久在 sessionStorage | 关浏览器就丢 | 用 localStorage |
| seq 跳跃检测缺位 | 中间消息永久丢 | 检测跳跃时触发全量重放 |
| iOS Safari 后台冻结 30s | 重连不及时 | 本章不解决，22 章 Visibility + 心跳 |
| 多设备同时连 | seq 推送冲突 / 一台收到另一台没收到 | v1 限制单 session 单连接 |
| 重连时旧订阅没清 | Flux 泄漏 / 重复消息 | `onclose` 必须取消订阅 |
| 退避上界太小 | 长断网时一直重试失败 | 上界 30s，重连超过 10 次提示"网络异常" |

> 边界 case 速查：

| 场景 | 行为 |
|------|------|
| last_seq 大于服务端 current | 不重放，正常进入实时模式 |
| 服务端 session 已被销毁 | 返回 error 事件，前端提示"会话不存在" |
| seq 不连续（中间丢） | 检测跳跃，强制全量重放 |
| 多设备同时打开同一 session | 都能收到广播（v1 单 session 单连接更安全）|

---

## 9. 本章产出

```
后端：
  ✅ seq 分配器（Redis）
  ✅ messages.seq 字段
  ✅ 重放接口

前端：
  ✅ 指数退避重连
  ✅ last_seq 跟踪 + localStorage
```

## 10. Web 项目专项升级（22 章预告）

本章的重连是单 tab 单进程，生产 Web 项目要补的：

| 升级 | 章节 | 解决问题 |
|------|------|---------|
| BroadcastChannel 跨 tab | 22 §3 | 多 tab 状态不同步 |
| Visibility API + 25s 心跳 | 22 §2 | iOS Safari 后台 30s 冻结 |
| IndexedDB 缓存 events | 22 §2 | localStorage 5MB 限制 |
| 离线消息队列 | 22 §2 | 断网期间消息丢失 |
| Leader 选举 | 22 §3 | 每 tab 都连 WS 浪费 |
| Redis pub/sub 跨节点广播 | 27 §3 | K8s 多 Pod 部署 |

## 11. 下一步

进入 [09-Artifacts](./09-Artifacts.md)，让 Agent 写的代码 / Markdown 在浏览器侧实时渲染。
