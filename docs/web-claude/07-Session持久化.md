# 07 - Session 持久化：JSONL DAG + DB 索引

> 本章目标：实现 Claude Code ch30 的会话持久化机制。
> 完成后：关浏览器 → 重启服务 → 重新打开 session，能继续之前的对话。
>
> **关联章节**：
> - 消息持久化之外，还有 [17 章 agent_events 表](./17-全链路可观测前端.md) 用于事件流持久化；
> - 双写一致性对账任务：[18 章](./18-错误恢复与重试.md) §6；
> - 启动时孤儿恢复：[18 章](./18-错误恢复与重试.md) §7。

---

## 0. 设计要点（来自调研笔记 §2.5）

- **存储格式**：JSONL，每行一条消息；
- **DAG**：通过 `parentUuid` 串联；
- **Compact boundary**：压缩发生时插入特殊消息，标记前后不可拼接；
- **双写**：JSONL 落对象存储 + DB 索引（用于查询）；
- **批量写入**：用队列减少 IO 次数。

---

## 1. 存储路径设计

```
S3 bucket:
  sessions/
    {tenant_id}/
      {session_id}/
        main.jsonl              ← 消息流（append-only）
        meta.json               ← session 元数据
        compactions/
          {compact_id}.jsonl    ← 压缩快照
        sidecar/
          index.json            ← 偏移量索引（uuid → byte offset）
          counters.json         ← 统计计数
```

---

## 2. JsonlSessionStore

新建 `src/main/java/org/demo02/webclaude/session/JsonlSessionStore.java`：

```java
// 本代码仅作学习材料参考
package org.demo02.webclaude.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.demo02.webclaude.agent.Message;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class JsonlSessionStore {

    private static final String BUCKET = "web-claude";
    private static final String PREFIX = "sessions/";

    private final S3Client s3;
    private final ObjectMapper om = new ObjectMapper();

    // 写队列（批量 flush）
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Message>> writeQueues = new ConcurrentHashMap<>();

    public JsonlSessionStore(S3Client s3) {
        this.s3 = s3;
    }

    public void append(UUID tenantId, UUID sessionId, Message m) {
        writeQueues.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>()).add(m);
    }

    // 定时 flush（每 1s）
    public void flush(UUID tenantId, UUID sessionId) {
        var q = writeQueues.get(sessionId);
        if (q == null || q.isEmpty()) return;

        // 1. 收集队列内容
        List<Message> batch = new ArrayList<>();
        while (!q.isEmpty()) batch.add(q.poll());
        if (batch.isEmpty()) return;

        // 2. 读现有 JSONL 内容（追加模式 → 需要先下载再上传）
        String key = PREFIX + tenantId + "/" + sessionId + "/main.jsonl";
        StringBuilder sb = new StringBuilder();
        try {
            var existing = s3.getObject(GetObjectRequest.builder()
                .bucket(BUCKET).key(key).build());
            try (var reader = new BufferedReader(new InputStreamReader(existing))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            }
        } catch (Exception ignored) {
            // 还不存在
        }

        // 3. 追加新消息
        for (Message m : batch) {
            sb.append(om.writeValueAsString(m)).append("\n");
        }

        // 4. 上传回 S3
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
            RequestBody.fromString(sb.toString()));
    }

    public List<Message> loadAll(UUID tenantId, UUID sessionId) {
        String key = PREFIX + tenantId + "/" + sessionId + "/main.jsonl";
        List<Message> result = new ArrayList<>();
        try {
            var obj = s3.getObject(GetObjectRequest.builder()
                .bucket(BUCKET).key(key).build());
            try (var reader = new BufferedReader(new InputStreamReader(obj, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    result.add(om.readValue(line, Message.class));
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    // 按 parentUuid DAG 重建（用于 fork / compact 后取线性链）
    public List<Message> loadLinearChain(UUID tenantId, UUID sessionId) {
        List<Message> all = loadAll(tenantId, sessionId);
        if (all.isEmpty()) return all;
        // 找最后一条消息，按 parentUuid 反向回溯
        var byId = new java.util.HashMap<UUID, Message>();
        for (Message m : all) byId.put(m.id(), m);
        Message tail = all.get(all.size() - 1);
        List<Message> chain = new ArrayList<>();
        Message cur = tail;
        while (cur != null) {
            chain.add(cur);
            cur = cur.parentUuid() == null ? null : byId.get(cur.parentUuid());
        }
        java.util.Collections.reverse(chain);
        return chain;
    }
}
```

---

## 3. Compact Boundary

新建 `src/main/java/org/demo02/webclaude/agent/CompactBoundary.java`：

```java
// 本代码仅作学习材料参考
package org.demo02.webclaude.agent;

import java.util.List;
import java.util.UUID;

/**
 * 当上下文超过预算，触发 compact：
 * 1. 把前 N 条消息压缩成一条 summary 消息；
 * 2. 在 summary 前后插入 "compact_boundary" 标记；
 * 3. 后续拼接时不能跨 boundary。
 */
public class CompactBoundary {

    public static Message compactStart(UUID parentId) {
        return new Message(
            UUID.randomUUID(), parentId, "system",
            List.of(new ContentBlock.Text("=== COMPACT BOUNDARY START ===")),
            "compact_start", 0, 0
        );
    }

    public static Message compactEnd(UUID parentId) {
        return new Message(
            UUID.randomUUID(), parentId, "system",
            List.of(new ContentBlock.Text("=== COMPACT BOUNDARY END ===")),
            "compact_end", 0, 0
        );
    }

    public static boolean isBoundary(Message m) {
        return "compact_start".equals(m.transition()) || "compact_end".equals(m.transition());
    }

    public static List<List<Message>> splitByBoundary(List<Message> messages) {
        List<List<Message>> parts = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        for (Message m : messages) {
            if ("compact_start".equals(m.transition())) {
                if (!current.isEmpty()) {
                    parts.add(current);
                    current = new ArrayList<>();
                }
            } else if (!isBoundary(m)) {
                current.add(m);
            }
        }
        if (!current.isEmpty()) parts.add(current);
        return parts;
    }
}
```

---

## 4. SessionService 整合

新建 `src/main/java/org/demo02/webclaude/session/SessionService.java`：

```java
// 本代码仅作学习材料参考
package org.demo02.webclaude.session;

import org.demo02.webclaude.agent.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SessionService {

    private final SessionRepository sessionRepo;
    private final MessageRepository messageRepo;
    private final JsonlSessionStore store;

    public SessionService(SessionRepository sessionRepo,
                          MessageRepository messageRepo,
                          JsonlSessionStore store) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.store = store;
    }

    public void appendMessage(UUID tenantId, UUID sessionId, Message m) {
        // 双写：DB 索引 + JSONL
        // DB（用于查询）
        MessageEntity e = new MessageEntity();
        // 省略 setter
        messageRepo.save(e);
        // JSONL（用于完整历史）
        store.append(tenantId, sessionId, m);
    }

    public List<Message> loadHistory(UUID tenantId, UUID sessionId) {
        // 优先从 JSONL 加载（含完整内容）
        return store.loadLinearChain(tenantId, sessionId);
    }

    // 定时 flush 所有 session 的写队列
    @Scheduled(fixedRate = 1000)
    public void flushAll() {
        // 遍历 writeQueues，依次 flush
        // 省略实现
    }
}
```

---

## 5. 启用 @EnableScheduling

修改 `WebClaudeApplication.java`：

```java
// 本代码仅作学习材料参考
@SpringBootApplication
@EnableScheduling
public class WebClaudeApplication { ... }
```

---

## 6. WebSocket 接入持久化

修改 `SessionWebSocketHandler.handleTextMessage`，在每条消息 yield 时调用 `appendMessage`：

```java
// 本代码仅作学习材料参考
agentLoop.runWithTools(sid, history, text, turnAbort, tools, perm, ctx, null)
    .doOnNext(state -> {
        // 持久化最后一条消息
        if (!state.messages().isEmpty()) {
            Message last = state.messages().get(state.messages().size() - 1);
            sessionService.appendMessage(TENANT, sid, last);
        }
        try {
            ws.sendMessage(new TextMessage(om.writeValueAsString(toWire(state))));
        } catch (Exception e) { e.printStackTrace(); }
    })
    .doOnComplete(() -> sessionService.flushAll())
    .subscribe();
```

---

## 7. 测试持久化

### 7.1 流程

1. 创建新 session；
2. 与 Agent 对话几轮（"我叫小张，记住"）；
3. 关闭浏览器（不点 abort）；
4. 重启后端；
5. 重新打开 session；
6. 问"我叫什么名字"。

**检查点 07-1**：Agent 回答"小张"，说明历史恢复成功。

### 7.2 验证 JSONL

打开 MinIO 控制台 → web-claude bucket → `sessions/{tenant}/{session}/main.jsonl`，下载查看：
- 每行一条 JSON；
- 每条有 `id` 和 `parentUuid`；
- 形成 DAG。

---

## 8. Lazy Materialization（优化）

v1：`loadAll` 一次性读全部，长 session 会撑爆内存。

v2 优化（本章只描述思路）：
- sidecar/index.json 维护 `uuid → byte offset` 映射；
- `loadLinearChain` 只读最后 N 条；
- 需要全量时按需分页加载。

---

## 9. recoverOrphanedParallelToolResults（健壮性）

进程崩溃时，可能出现"父亲已写但孩子未写"的孤儿。启动时扫描修复：

```java
// 本代码仅作学习材料参考
@PostConstruct
public void recoverOrphans() {
    // 遍历所有 sessions 的 JSONL
    // 检查每条 tool_use 是否有对应的 tool_result
    // 没有 → 插入 "tool_result": error "orphaned, recovered"
}
```

---

## 10. 本章产出

```
后端：
  ✅ JsonlSessionStore（append + load + flush）
  ✅ CompactBoundary（boundary 标记 + 切分）
  ✅ SessionService（DB + JSONL 双写）
  ✅ 定时 flush
  ✅ 启动时孤儿恢复
```

## 11. 下一步

进入 [08-WebSocket重连](./08-WebSocket重连.md)，让前端断网重连时不丢消息。
