# Kafka 核心概念与 Spring Boot 实战

> **配套文档**：[35-管数分离实战](../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 9 章把 chunk 总线从 Redis Streams 升级到了 Kafka，用到了消费组、分区、offset。但主线聚焦管数分离，没系统讲 Kafka 本身。本篇从零讲透 Kafka——它是什么、核心概念、Spring Boot 怎么用、和 Redis Stream 怎么选。
>
> **难度假设**：你听过 Kafka 但没真正用过，不清楚 topic/partition/consumer group 这些词到底什么意思。

---

## 第 1 章：Kafka 是什么，为什么存在

### 1.1 一句话

**Kafka 是一个分布式的、高吞吐的、持久化的"消息日志"系统。**

把它想象成一个**巨型录播库**：

- 生产者往里**塞节目**（写消息）。
- 消费者按自己的节奏**来看**（读消息），看过的进度自己记。
- 节目**存很久**（默认 7 天，可配 30 天/永久），随时能回看。
- **吞吐极高**（百万级消息/秒），因为有多个"书架"（分区）并行。

### 1.2 它解决什么问题

传统点对点调用（A 直接调 B）的问题：

- **耦合**：A 必须知道 B 在哪、B 挂了 A 也失败。
- **削峰难**：A 瞬间发 10 万请求，B 处理不过来就崩。
- **多个下游各自对接**：A 要通知审计、计费、分析三个服务，得调三次。

Kafka 的解法：**A 把消息丢进 Kafka 就完事**，审计/计费/分析各自消费，互不影响；B 慢了消息在 Kafka 里排队，不丢。

```
A(生产者) ──→ [Kafka topic] ──→ 审计服务（消费组 audit）
                          ──→ 计费服务（消费组 billing）
                          ──→ 分析服务（消费组 analytics）
```

管数分离文档第 9 章正是这个场景：生成器把 chunk 写进 Kafka，**审计/计费/分析各起一个消费组**，独立消费、独立记进度。

---

## 第 2 章：五个核心概念（必须分清）

这是 Kafka 的认知地基，分不清后面全乱。

### 2.1 Topic（主题）——消息的分类

类似"频道"或"文件夹"。`gen-chunks`、`user-events`、`orders` 都是 topic。生产者指定往哪个 topic 发，消费者指定从哪个 topic 收。

### 2.2 Partition（分区）——并行与扩展的单位

**这是 Kafka 高吞吐的秘诀，也是最该理解的概念。**

一个 topic 被切成多个 partition（分区），每个 partition 是一个**独立的、有序的追加日志**：

```
topic: gen-chunks (3 个分区)
├── partition-0:  [msg1] [msg4] [msg7] ...
├── partition-1:  [msg2] [msg5] [msg8] ...
└── partition-2:  [msg3] [msg6] [msg9] ...
```

**关键规则**：

- **单个分区内消息有序**（partition-0 里 msg1 一定在 msg4 前）。
- **跨分区不保证顺序**（msg2 和 msg3 谁先到不一定）。
- 生产者发消息时指定一个 **key**：**相同 key 的消息一定进同一分区**（保序）。

> **管数分离文档第 9 章为什么用 `key=runId`**：保证同一个 run 的所有 chunk 进同一分区——这样这个 run 的 chunk **严格按生成顺序**排列，消费者读出来顺序不会乱。这是 Kafka 保序的标准做法。

**分区数 = 最大并行度**：一个分区同时只能被消费组内**一个**消费者消费。所以 3 个分区最多 3 个消费者并行。想扩到 10 个消费者？topic 得有 ≥10 个分区。

### 2.3 Offset（位移）——消费者读到哪了

每个消息在分区里有个**单调递增的编号** offset（从 0 开始）：

```
partition-0:  [msg(offset=0)] [msg(offset=1)] [msg(offset=2)] ...
```

消费者记录"我读到 offset=N 了"，下次从 N+1 续读。**这就是管数分离文档第 5 章 seq 的 Kafka 版**——单调编号 + 从编号续读。

**Offset 由消费组托管**：消费者不用自己存 offset，Kafka 帮你记在内部 topic（`__consumer_offsets`）里。

### 2.4 Consumer Group（消费组）——消费者协作单位

**最容易混，讲清楚**：

- **同一个消费组内**的多个消费者：**分摊**分区（每条消息只被组内一个消费者处理）。3 分区 + 3 消费者 = 每人 1 分区。
- **不同消费组**之间：**各自独立消费全量**。审计组和计费组都能看到所有消息，互不干扰。

```
topic (3 分区)          消费组A (audit)        消费组B (billing)
├── partition-0 ──→ 消费者A1               消费者B1
├── partition-1 ──→ 消费者A2               消费者B2
└── partition-2 ──→ 消费者A3               消费者B3
   （A 组内分担）         （B 组内分担）
   （A、B 组各自看到全量消息）
```

> **对比 Redis Stream 消费组**：原理完全一样（同组分担、不同组独立）。Kafka 的更成熟、支持海量。

### 2.5 Broker（代理）——Kafka 服务器

一个 Kafka 集群由多个 broker 组成。topic 的分区分布在各 broker 上，broker 之间复制数据做高可用。单机学习用一个 broker 即可（KRaft 模式）。

---

## 第 3 章：Spring Boot 实战

### 3.1 依赖与配置

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all           # 所有副本确认才算成功（不丢消息）
      retries: 3
    consumer:
      group-id: my-group
      auto-offset-reset: earliest   # 新组从头读；latest=只读新的
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

> **`auto-offset-reset`**：当消费组**第一次**消费（还没 offset 记录）时，从头读还是只读新的。`earliest`（从头）适合"新消费者要历史"，`latest`（只新的）适合"只关心实时"。管数分离文档用 `earliest`——晚加入的服务能看到历史 chunk。

### 3.2 生产者：发消息

```java
@Service
public class ChunkProducer {
    private final KafkaTemplate<String, String> kafka;

    public ChunkProducer(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    public void send(String runId, String chunk) {
        // topic, key, value。同 key 进同分区保序
        kafka.send("gen-chunks", runId, chunk);
    }
}
```

`kafka.send()` 是异步的（返回 `ListenableFuture`），不等确认就返回。要确保不丢，配 `acks: all` + `retries`。

### 3.3 消费者：收消息（注解版，最常用）

```java
@Component
public class ChunkConsumer {

    @KafkaListener(topics = "gen-chunks", groupId = "audit")
    public void handle(ConsumerRecord<String, String> record) {
        // record.key() = runId，record.value() = chunk，record.offset() = 位移
        System.out.println("审计: run=" + record.key() + " chunk=" + record.value());
    }
}
```

> **`@KafkaListener` 是最简单的消费方式**：Spring 自动帮你创建消费者、订阅 topic、循环拉取、把每条消息交给你的方法。**这是传统 Spring Boot 项目的首选写法。**

### 3.4 消费者：收消息（容器版，管数分离文档用的）

管数分离文档第 9 章用的是**容器手动配置**，因为要"N 个 SSE 连接共享一个消费者、按 key 分发"：

```java
@Bean
public ConcurrentMessageListenerContainer<String, String> container(
        ConsumerFactory<String, String> cf, KafkaChunkBus bus) {
    ContainerProperties props = new ContainerProperties("gen-chunks");
    props.setMessageListener((MessageListener<String, String>) record -> bus.dispatch(record));
    ConcurrentMessageListenerContainer<String, String> container =
            new ConcurrentMessageListenerContainer<>(cf, props);
    container.getContainerProperties().setGroupId("research-sse");
    return container;
}
```

> **两种写法的区别**：`@KafkaListener` 简单直接，适合"每条消息独立处理"；容器版灵活，适合"要把消息再分发出去"（如扇出给多个 SSE 连接）。

---

## 第 4 章：可靠性——ack、retries、消费确认

### 4.1 生产端不丢消息

- `acks: all`（或 `-1`）：消息写入**所有副本**才算成功。最高可靠，略慢。
- `acks: 1`：leader 写入即成功（默认）。
- `acks: 0`：发了就算成功，不等任何确认。最快但可能丢。
- `retries: 3`：失败自动重试。

> **管数分离文档第 9 章配 `acks: all`**：chunk 是重要数据，宁可慢点也不能丢。

### 4.2 消费端：自动提交 vs 手动提交 offset

消费者读完消息要"提交 offset"告诉 Kafka"我处理到这了"。两种方式：

- **自动提交**（默认 `enable.auto.commit=true`）：消费者每隔几秒自动提交当前 offset。简单，但有个坑——**消息拉下来了、还没处理完就自动提交了，这时崩溃 = 消息丢**（因为下次从已提交 offset 续读，跳过了没处理完的）。
- **手动提交**：处理完业务逻辑后**显式提交** offset。更可靠。

```java
@KafkaListener(topics = "gen-chunks")
public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    try {
        process(record.value());   // 业务逻辑
        ack.acknowledge();          // 处理成功才提交 offset
    } catch (Exception e) {
        // 不提交，下次重投（at-least-once）
    }
}
```

（需配 `listener.ack-mode: manual`）

### 4.3 at-least-once 与幂等

Kafka 默认保证 **at-least-once（至少一次）**：消息不会丢，但**可能重复**（处理完、提交前崩溃，重启重投）。所以**消费者必须幂等**——和 Redis Stream 消费组一样的要求。

---

## 第 5 章：本地起 Kafka（KRaft，无需 ZooKeeper）

旧版 Kafka 依赖 ZooKeeper，新版（3.3+）用 **KRaft 模式**，单机学习一个容器搞定：

```bash
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9092 \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  confluentinc/cp-kafka:latest
```

**命令行验证**：

```bash
# 建一个 topic（3 分区）
docker exec kafka kafka-topics --create --bootstrap-server localhost:9092 \
  --topic gen-chunks --partitions 3 --replication-factor 1

# 命令行生产者
docker exec -it kafka kafka-console-producer --bootstrap-server localhost:9092 --topic gen-chunks

# 命令行消费者
docker exec -it kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic gen-chunks --from-beginning --group test
```

---

## 第 6 章：Kafka vs Redis Stream（选型）

| 维度 | Redis Stream | Kafka |
|------|-------------|-------|
| 吞吐 | 万级/秒 | 百万级/秒 |
| 持久 | 内存（RDB/AOF） | 磁盘原生 |
| 长期保留 | 内存贵，靠 MAXLEN/TTL | 磁盘便宜，配保留 N 天 |
| 运维 | 一个 Redis | broker 集群 + 控制器 |
| 分区 | 单实例（Cluster 分片） | 原生多分区多 broker |
| 学习成本 | 低 | 中 |
| 适用 | 轻量、低延迟、短期 | 海量、跨服务、长期保留 |

**经验**：

- **别一上来就 Kafka**。多数场景 Redis Stream 够用，且省一个中间件。
- 当出现这些信号才考虑 Kafka：吞吐过万、要跨多个独立服务消费、数据要长期保留（审计/回溯）、要水平扩展到很多分区。
- 管数分离文档的演进正体现这点：第 4-8 章用 Redis Stream（够用），第 9 章因"跨服务消费 + 保留 30 天"才升级。

---

## 第 7 章：常见坑

### 坑 1：分区数 < 消费者数，多余消费者闲置

**现象**：扩了 5 个消费者，但只有 3 个收到消息。
**原因**：一个分区同时只能被组内一个消费者消费。
**解决**：topic 分区数 ≥ 消费者数。生产环境按预期并发规划分区（建 topic 时指定，如 6/12）。

### 坑 2：自动提交 offset 导致消息丢失

见第 4.2。**解决**：关键业务用手动提交，处理完才提交。

### 坑 3：消息乱序

**原因**：跨分区不保序；或同分区内因重试乱序。
**解决**：需要保序的消息用相同 key（进同分区）。

### 坑 4：消费者不幂等，重复消费出问题

**原因**：at-least-once 必然可能重复。
**解决**：消费者用去重（如业务唯一 ID + 去重表）或设计成幂等。

### 坑 5：`auto-offset-reset` 配错

新消费组 `latest`（默认在某些版本）会导致新消费者**丢掉历史**。要历史就配 `earliest`。

### 坑 6：topic 不存在 + auto-create 关闭，生产直接报错

**解决**：显式建 topic，或开启 `auto.create.topics.enable=true`（学习用）。

### 坑 7：单分区瓶颈

单分区 = 单消费者 = 吞吐上限。**解决**：按 key 合理分区，分区数配合消费者数。

---

## 总结

- **Topic**：消息分类。**Partition**：并行单位，单分区内有序。**Key**：同 key 进同分区保序。
- **Offset**：消费进度，消费组托管。
- **消费组**：同组分担、不同组独立。
- **可靠性**：`acks: all` + 手动提交 offset + 消费者幂等 = at-least-once。
- **选型**：轻量用 Redis Stream，海量用 Kafka。

学完本篇，[管数分离文档第 9 章](../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 的"为什么 key=runId"、"为什么一个消费者 N 个 SSE 共享"、"消费组怎么跨服务消费"就全通了。
