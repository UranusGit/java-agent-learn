# Sprint 3: 边云协同

> **目标**：边缘节点与云端协同——云端训练/管理，边缘推理/执行。

---

## 边云协同架构

```mermaid
flowchart TD
    subgraph Cloud["云端"]
        ModelRepo["模型仓库"]
        ConfigCenter["配置中心"]
        Monitor["监控中心"]
        DataLake["数据湖"]
    end

    subgraph Edge1["边缘节点 A"]
        Sync1["同步引擎"]
        Agent1["本地 Agent"]
        Buffer1["数据缓冲"]
    end

    subgraph Edge2["边缘节点 B"]
        Sync2["同步引擎"]
        Agent2["本地 Agent"]
        Buffer2["数据缓冲"]
    end

    Cloud -->|"模型/配置下发"| Edge1
    Cloud -->|"模型/配置下发"| Edge2
    Edge1 -->|"指标/日志上报"| Cloud
    Edge2 -->|"指标/日志上报"| Cloud
    Edge1 -->|"离线积压同步"| Cloud
    Edge2 -->|"离线积压同步"| Cloud

    style Cloud fill:#e3f2fd
    style Edge1 fill:#e8f5e9
```

---

## V1: 指标上报

```java
@Component
public class MetricsReporter {

    private final Queue<Metric> buffer = new ConcurrentLinkedQueue<>();

    /**
     * 本地记录指标（不阻塞主流程）
     */
    public void record(String name, double value, Map<String, String> tags) {
        buffer.offer(new Metric(name, value, tags, Instant.now()));
    }

    /**
     * 有网络时批量上报
     */
    @Scheduled(fixedRate = 60000)
    public void report() {
        if (!connectionMonitor.isOnline()) return;
        if (buffer.isEmpty()) return;

        List<Metric> batch = new ArrayList<>();
        Metric m;
        while ((m = buffer.poll()) != null && batch.size() < 500) {
            batch.add(m);
        }

        try {
            cloudClient.reportMetrics(batch);
        } catch (Exception e) {
            // 上报失败 → 放回队列
            buffer.addAll(batch);
        }
    }
}
```

---

## V2: 知识库增量同步

```java
/**
 * V2: 云端知识库更新时，增量同步到边缘
 */
@Component
public class KnowledgeSyncEngine {

    private String lastSyncTimestamp;

    /**
     * 定时同步（弱网友好：小批量）
     */
    @Scheduled(initialDelay = 60000, fixedRate = 300000)
    public void sync() {
        if (!connectionMonitor.isOnline()) return;

        // 1. 获取增量变更
        DeltaResponse delta = cloudClient.getKnowledgeDelta(
            config.getAgentId(), lastSyncTimestamp);

        if (delta.isEmpty()) return;

        // 2. 应用变更
        for (DocumentChange change : delta.changes()) {
            switch (change.type()) {
                case ADDED, MODIFIED -> {
                    float[] vec = localEmbedder.embed(change.content());
                    localKB.upsert(change.id(), change.content(), vec,
                                   change.metadata());
                }
                case DELETED -> localKB.delete(change.id());
            }
        }

        lastSyncTimestamp = delta.latestTimestamp();
        log("同步完成: %d 条变更", delta.changes().size());
    }
}
```

---

## V3: 远程管理

```mermaid
sequenceDiagram
    participant Admin as 管理员
    participant Cloud as 云端
    participant Edge as 边缘 Agent

    Admin->>Cloud: 下发配置变更
    Cloud->>Edge: 推送配置（MQTT）
    Edge->>Edge: 热加载配置
    Edge-->>Cloud: 确认 + 新指标
    Cloud-->>Admin: 变更已生效

    Admin->>Cloud: 查看边缘状态
    Cloud->>Edge: 心跳查询
    Edge-->>Cloud: 健康状态 + 资源使用
    Cloud-->>Admin: 边缘看板

    Admin->>Cloud: 远程升级模型
    Cloud->>Edge: 推送模型包（分块）
    Edge->>Edge: 下载 + 验证 + 热切换
    Edge-->>Cloud: 升级完成
```

---

## 通信协议选型

| 协议 | 延迟 | 弱网容忍 | 双向 | 适用 |
|------|------|---------|------|------|
| MQTT | 低 | ✅ 强 | ✅ | 物联网/边缘首选 |
| gRPC | 低 | ❌ 一般 | ✅ | 强网环境 |
| HTTP 轮询 | 中 | ✅ | ❌ | 最简单 |
| WebSocket | 低 | ⚠️ 中 | ✅ | 实时双向 |

---

## 关键收获

| 要点 | 说明 |
|------|------|
| 缓冲是核心 | 断网时数据不丢 |
| 增量同步省带宽 | 只传变更部分 |
| MQTT 适合弱网 | 轻量 + 断线重连 |
| 远程管理必要 | 不可能去现场调试每个边缘节点 |
