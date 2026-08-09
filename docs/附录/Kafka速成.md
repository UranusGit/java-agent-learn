# 附录：Kafka 速成

> AI 原生架构中 Kafka 的核心用途：事件总线 / 数据管道。

## 在 AI 应用中的用途

| 用途 | 说明 |
|------|------|
| **事件总线** | AI 原生架构的核心——所有组件通过事件通信 |
| **审计日志** | append-only 审计日志写入 Kafka |
| **数据飞轮** | 生产 trace → Kafka → 标注 → 评估集 |
| **实时流处理** | Flink + Kafka 做实时总结 Agent |

## 核心概念

| 概念 | 解释 |
|------|------|
| **Topic** | 消息分类（如 "chat-events"） |
| **Producer** | 发消息的 |
| **Consumer** | 收消息的 |
| **Partition** | 分区并行 |
| **Offset** | 消费位置 |

## 在 Event Sourcing 中的应用

```java
// 事件存储到 Kafka（append-only）
@Component
public class KafkaEventStore {

    private final KafkaTemplate<String, Object> kafka;

    public void append(String sessionId, SessionEvent event) {
        kafka.send("session-events", sessionId, event);
    }

    @KafkaListener(topics = "session-events", groupId = "session-rebuilder")
    public void onEvent(SessionEvent event) {
        // 消费事件，重建会话状态
        sessionState.apply(event);
    }
}
```

## 相关文档
- AI 原生架构：`阶段5-架构师/04-AI原生架构设计.md`
- 审计合规：`阶段5-架构师/03-审计与合规.md`
