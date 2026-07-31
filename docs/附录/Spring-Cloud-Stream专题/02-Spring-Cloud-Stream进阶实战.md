# Spring Cloud Stream 进阶实战（从 Kafka 零基础到流式架构师）

> **这份文档是什么**：[Spring Cloud Stream 从入门到架构师](./01-Spring-Cloud-Stream从入门到架构师.md) 的**进阶续篇**。前一篇讲"会用 Spring Cloud Stream 收发消息"，这一篇带你进入**生产调优、流式计算、响应式真相、事件驱动架构**——达到真正的架构师水平。
>
> **写给谁**：**Kafka 零基础也能读**。你说"Kafka 也没学过"——没关系，本文第 1 章专门为你补 Kafka 地基，后面所有高级用法都建立在这个地基上。读完你能分清"哪些是 Kafka 的概念、哪些是 Spring Cloud Stream 的抽象"，不会再混淆。
>
> **前置要求**：先读完 [入门篇](./01-Spring-Cloud-Stream从入门到架构师.md) 的 0-5 章（至少知道 Supplier/Function/Consumer、binding 命名、destination 这些）。
>
> **版本前提（已校验）**：基于 Spring Cloud Stream 4.2.x / 4.3.x + Spring Boot 3.4/3.5。Boot 4.x 用 Spring Cloud 2025.1.x。**重要：2025 年 5 月 Spring 官方宣布 `reactor-kafka` 停止维护、Spring Cloud Stream 的响应式 Kafka Binder 废弃**——本文第 4 章会专门讲这件事，并给出官方推荐替代方案。所有 API 已对照官方文档校验。

---

## 目录

- [进阶 第 1 章：Kafka 核心概念补全（零基础地基）](#进阶-第-1-章kafka-核心概念补全零基础地基)
- [进阶 第 2 章：Spring Cloud Stream 生产调优实战](#进阶-第-2-章spring-cloud-stream-生产调优实战)
- [进阶 第 3 章：Kafka Streams Binder——流式计算](#进阶-第-3-章kafka-streams-binder流式计算)
- [进阶 第 4 章：响应式、背压，以及 reactive binder 为什么废弃了](#进阶-第-4-章响应式背压以及-reactive-binder-为什么废弃了)
- [进阶 第 5 章：事件驱动架构（EDA）——架构师设计思维](#进阶-第-5-章事件驱动架构eda架构师设计思维)
- [进阶 附录：进阶 API 校验表与踩坑](#进阶-附录进阶-api-校验表与踩坑)

---

## 进阶 第 1 章：Kafka 核心概念补全（零基础地基）

> 你用 Spring Cloud Stream 时，底层（用 Kafka binder 时）就是 Kafka。不懂数 Kafka 就调优 Stream，等于盲人摸象。这一章用最短篇幅把 Kafka 的**必须懂的概念**讲透。**注意分清：哪些是 Kafka 自有的（换 RabbitMQ 就不一样），哪些是 Spring Cloud Stream 的抽象（换中间件不变）。**

> **和入门篇的关系（别困惑）**：[入门篇第 6 章](./01-Spring-Cloud-Stream从入门到架构师.md) 为了让你"能用"，简单提了 topic/分区/消费组/offset。那章是**从 Stream 的视角**看它们（destination 对应 topic、partition-key-expression 等），够你跑通。**本章从 Kafka 的视角**把它们讲透——为什么有分区、offset 的 at-least-once 怎么来的、副本怎么保高可用。**两者不矛盾**：入门篇是"怎么用"，本章是"为什么"。如果你入门篇第 6 章看得很顺、不想深挖 Kafka，可以跳到第 2 章；想真正调优和理解原理，本章是地基。

### 1.1 Kafka 是什么——一句话和一个比喻

**Kafka 是一个分布式、持久化、高吞吐的消息日志系统。**

比喻：把 Kafka 想成一个**无限追加的日志本**（像 git log）。生产者往里**追加**事件，消费者**从任意位置读**。事件一旦写入就不改（除非过期删除），多个消费者可以各自独立地读同一份日志。

```
topic: orders （一个"日志本"）
─────────────────────────────────
[订单1][订单2][订单3][订单4][订单5...]   → 不断追加
  ↑
  offset（每个消费者记住自己读到第几条）
```

### 1.2 Topic（主题）——消息的分类

**Topic 是 Kafka 里消息的逻辑分类**。比如 `orders`（订单）、`payments`（支付）、`shipments`（发货）各是一个 topic。生产者指定发到哪个 topic，消费者指定从哪个 topic 读。

> **在 Spring Cloud Stream 里**：topic 对应 `destination`。你配 `destination: orders`，底层 Kafka binder 就用名为 `orders` 的 topic。**`destination` 是 Stream 的抽象，`topic` 是 Kafka 的实体。**

### 1.3 Partition（分区）——并行与保序的关键 ⭐

**这是 Kafka 最重要、也最容易不懂的概念。** 一个 topic 被切成多个 **partition**（分区），就像一本日志被拆成几本子日志：

```
topic: orders （假设 3 个分区）
─────────────────────────────────────
partition 0: [订单A][订单D][订单G]
partition 1: [订单B][订单E][订单H]
partition 2: [订单C][订单F][订单I]
```

**分区有两个核心意义**：

#### ① 并行
一个 partition 同一时间只能被**一个消费者**（同消费组内）消费。所以：
- topic 有 3 个分区 → 一个消费组最多 3 个消费者能并行干活（多了的消费者闲置）。
- **想提高消费并行度？先加分区**。

#### ② 保序 ⭐
**同一个 partition 内，消息严格有序**（按写入顺序）。但**不同 partition 之间不保证顺序**。

所以如果你要"同一笔订单的 创建→支付→发货 事件按顺序处理"，必须让它们进**同一个 partition**——用 **key** 实现：**相同 key 的消息一定进同一个 partition**（Kafka 按 key 哈希分区）。

```java
// 生产时指定 key（如 orderId）
producer.send(new ProducerRecord<>("orders", orderId, event));
// 同一 orderId 的所有事件 → 同一 partition → 有序
```

> **在 Spring Cloud Stream 里**：第 6 章讲过 `partition-key-expression`。**底层就是 Kafka 的 key 分区机制**。Stream 把它抽象出来，让你不写 Kafka 代码也能用。

### 1.4 Offset（位移）——消费进度

每个分区里的每条消息有个**单调递增的编号**叫 offset（0, 1, 2, ...）。消费者读完一条，把"我读到哪了"（offset）记下来（提交，commit）。重启后从上次提交的 offset 续读。

- **自动提交**：消费者定期自动提交（默认每 5 秒）。简单，但可能"处理了但没提交就崩了"→ 重启重复消费；或"没处理完就提交了"→ 崩了丢消息。
- **手动提交**：处理完一条再提交。精确，但要自己管。Spring Cloud Stream 默认是"处理成功后提交"（at-least-once）。

> **核心结论**：Kafka 默认是 **at-least-once（至少一次）**——同一条消息**可能被消费多次**（比如处理成功、提交 offset 前崩溃）。所以**消费者必须幂等**。这是铁律，入门篇 9.1 讲过，这里再强调：**不懂这个，上线必出数据错误**。

### 1.5 Consumer Group（消费组）——负载均衡 vs 发布订阅

一个消费组是**一组共同消费某些 topic 的消费者**。机制（入门篇 5.2 讲过，这里补 Kafka 视角）：

- **同组**：一个 partition 只被组内**一个**消费者消费 → 负载均衡。
- **不同组**：每组**各自独立**收到全量消息 → 发布订阅。

```
topic orders 有 3 个分区 P0/P1/P2：

消费组 A（3 个消费者）：   消费组 B（1 个消费者）：
  c1 ← P0                   c4 ← P0,P1,P2（全收）
  c2 ← P1
  c3 ← P2
```

组 A 三个消费者分担（负载均衡）；组 B 一个消费者全收（它可能是做全量备份/分析的）。

### 1.6 Replica（副本）与 ISR——高可用

生产环境 Kafka 每个 partition 有**多个副本**（replica），分布在不同的 broker（Kafka 服务器）上。其中一个是 **leader**（读写都走它），其他是 **follower**（同步 leader 的数据）。leader 挂了，从 follower 里选一个新的当 leader。

**ISR（In-Sync Replicas，同步副本集合）**：跟得上 leader 的副本们。生产者可以配 `acks=all`（所有 ISR 副本确认才算成功）——这是**最强不丢消息**的配置（入门篇 Kafka 配置里讲过）。

> **这层你一般不用管**——是 Kafka 运维的事。但架构师要知道：**Kafka 的可靠性来自副本 + acks=all**。

### 1.7 一张图总结 Kafka 结构

```
Producer ──写──> [ Broker1: P0(leader) P1(follower) ]
                  [ Broker2: P1(leader) P2(follower) ]  ← 一个 Kafka 集群
                  [ Broker3: P2(leader) P0(follower) ]
                        │ topic = 多个 partition，partition 有副本
                        ▼
Consumer Group X:  c1(P0)  c2(P1)  c3(P2)   ← 同组分担
Consumer Group Y:  c4(P0,P1,P2)             ← 另一组全收
```

**分清边界**（这张表请你背下来）：

| 概念 | 是 Kafka 的 | 是 Spring Cloud Stream 的 |
|------|:-----------:|:------------------------:|
| topic / partition / offset / 消费组 / 副本 | ✅ | |
| destination / binding / Supplier/Function/Consumer / Binder | | ✅ |

Kafka 的概念**换了中间件（如 RabbitMQ）就不一样**（RabbitMQ 没有 partition，用 exchange/queue/binding 路由）；Stream 的抽象**换了中间件不变**（这就是 Stream 的价值）。**架构师必须能在脑子里分清这两层。**

---

## 进阶 第 2 章：Spring Cloud Stream 生产调优实战

入门篇讲了"能跑"。这一章讲"**生产环境怎么调好**"。这些都是真实项目里会踩的坑。

### 2.1 生产者：消息发出去到底成功了没？

默认 `KafkaTemplate.send()` 是**异步**的——方法返回时消息可能还没真正到 broker。如果发完就不管，可能"消息丢了你还不知道"。

**生产级配置**（入门篇第 10 章提过，这里细化）：

```yaml
spring:
  cloud:
    stream:
      kafka:
        bindings:
          orders-out-0:
            producer:
              sync: true              # ▼ 同步发送：等 broker 确认才返回（牺牲吞吐换可靠）
      # 或在 binder 级全局配
      # binder:
      #   configuration:
      #     acks: all                  # 所有 ISR 副本确认才算成功（最强不丢）
```

- `sync: true`：发送阻塞，等 broker ack。**可靠但慢**，适合关键消息（如订单）。
- `acks: all`（在 producer `configuration` 下）：所有同步副本确认。**不丢消息的基石**。
- 默认异步：吞吐高，但你要自己处理失败（监听 send 的回调）。

> **StreamBridge 的同步语义**：入门篇 4.2.2 讲的 `setAsync(true)` 反过来——默认 `StreamBridge.send` 是阻塞的（和 `KafkaTemplate` 默认异步相反！）。要异步显式 `setAsync(true)`。

### 2.2 消费者并发：怎么吃满多分区

topic 有 6 个分区，但你的消费者实例只用了 1 个线程？白白浪费 5 个分区的并行能力。

```yaml
spring:
  cloud:
    stream:
      bindings:
        process-in-0:
          destination: orders
          group: billing
          consumer:
            concurrency: 3     # ▼ 每个实例开 3 个消费线程
```

`concurrency` = 单实例内的消费线程数。**总并行度 = min(实例数 × concurrency, 分区数)**。规则：
- 分区数 6，3 个实例各 `concurrency: 2` → 6 个消费者刚好吃满。
- `concurrency` 超过分区数 → 多余线程闲置（见入门篇坑 6）。

### 2.3 offset 重置策略：新消费组从哪开始读

一个**新的消费组**第一次读某 topic，从哪开始？由 `auto-offset-reset` 决定：

```yaml
spring:
  cloud:
    stream:
      bindings:
        process-in-0:
          consumer:
            # ▼ 注意：Kafka binder 下这个配置在 kafka.bindings 下
      kafka:
        bindings:
          process-in-0:
            consumer:
              auto-offset-reset: earliest   # ▼ earliest=从头读历史；latest=只读新消息（默认）
              reset-offsets: true            # ▼ 某些场景强制重置（谨慎）
```

- `earliest`（Kafka 里叫 `earliest`，旧名 `smallest`）：从头读。**新服务上线想处理历史消息时用**。
- `latest`：只读启动后的新消息。**默认，多数场景用这个**。

> **坑**：很多人重启服务发现"历史消息又消费了一遍"——因为换了消费组名 + `earliest`。**生产环境固定消费组名**。

### 2.4 消息头（Headers）与内容协商

消息不只是 payload，还能带**头信息**（header）。Spring 用 `Message<T>` 封装：

```java
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

// ▼ 发送时带头信息
Message<Order> msg = MessageBuilder.withPayload(order)
        .setHeader("eventType", "OrderCreated")
        .setHeader("version", "v2")
        .setHeader("partitionKey", order.getUserId())
        .build();
streamBridge.send("orders-out-0", msg);

// ▼ 接收时读头信息（函数入参用 Message<Order> 而非 Order）
@Bean
public Consumer<Message<Order>> process() {
    return message -> {
        String eventType = (String) message.getHeaders().get("eventType");
        Order order = message.getPayload();
        // 按 eventType 分发处理...
    };
}
```

**内容协商（Content-Type Negotiation）**：`content-type` 决定序列化。`application/json` 自动 POJO↔JSON。复杂场景（如带泛型的 `List<Order>`）要用 `Message` 携带正确的 `contentType` 头，否则反序列化失败。

### 2.5 自定义序列化（Serde）

默认 JSON 序列化够用。但有时你要自定义（如用 Avro/Protobuf 省带宽、对接已有格式）：

```java
import org.springframework.context.annotation.Bean;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
// 通过 Kafka binder 的 configuration 传入自定义 Serializer/Deserializer 类
```

```yaml
spring:
  cloud:
    stream:
      kafka:
        binder:
          configuration:
            # ▼ 全局自定义 key/value 的序列化器（键值对）
            key.serializer: org.apache.kafka.common.serialization.StringSerializer
            value.serializer: com.example.AvroOrderSerializer
```

> **新手建议**：先用 JSON（`application/json`），真有性能/兼容需求再上 Avro/Protobuf。别一上来就过度设计。

---

## 进阶 第 3 章：Kafka Streams Binder——流式计算

这是进阶的**高价值章节**。前面所有章节都是"**搬**消息"（收发），这一章是"**算**消息"（实时计算）。

### 3.1 流式计算 vs 普通消息：什么时候用

普通消息（前面学的）：来一条处理一条，**无状态**（这条和上一条无关）。

**流式计算**：在消息流上做**有状态的连续计算**——比如：
- "每分钟统计各商品的订单量"（窗口聚合）
- "订单流 JOIN 用户流，算每个订单的实时用户画像"（流 JOIN）
- "同一用户 1 秒内下了 10 单 → 告警欺诈"（模式匹配）

这些用普通消息 + 自己查 DB 也能做，但**数据量大时扛不住**（每条都查 DB 太慢）。Kafka Streams 把"状态"放在本地（用 RocksDB），快得多。

**Kafka Streams 是 Apache Kafka 自带的流处理库**；Spring Cloud Stream 提供了它的 **Binder**，让你用熟悉的函数式模型写。

### 3.2 两个核心抽象：KStream 和 KTable

- **KStream**：**事件流**。每条消息是一个独立事件（如"用户点击了一下"）。可以理解为一串记录。
- **KTable**：**状态表**。每条消息是"某个 key 的**最新状态**"（如"用户当前余额"）。同 key 新消息**覆盖**旧消息——像数据库表。

**举例**：
- `KStream<userId, ClickEvent>`：用户每次点击都是一条。点了 10 次有 10 条。
- `KTable<userId, Balance>`：用户余额。改了 10 次只有最新那 1 条（状态）。

### 3.3 第一个 Kafka Streams 程序：词频统计

经典例子：读入一段段文字，统计每个词出现了多少次。

#### 3.3.1 依赖

```xml
<!-- ▼ Kafka Streams Binder（和普通 Kafka binder 是不同的 starter，已校验） -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-stream-kafka-streams</artifactId>
</dependency>
```

> **注意**：是 `spring-cloud-starter-stream-kafka-streams`（带 `-streams`），**不是**普通的 `spring-cloud-starter-stream-kafka`。两者不能同时混用核心 binding，但可以在一个应用里用"多 binder"共存（进阶附录讲）。

#### 3.3.2 函数：一步步搭出来（别急着看完整版）

Kafka Streams 的代码是一串**链式调用**。新手直接看完整版会懵，所以我们**分三步**，每步只加一个操作，每步都能独立跑通理解。

---

**第 1 步：最简单的——读入流，原样输出（KStream → KStream）**

先不管统计，就做最简单的事：读一条消息，处理一下，输出。让你先熟悉"KStream 长什么样"。

```java
import org.apache.kafka.streams.kstream.KStream;
import java.util.function.Function;

@Bean
public Function<KStream<String, String>, KStream<String, String>> echoUpper() {
    // 输入 KStream<String,String>（key=String, value=String 的流）
    // 输出 KStream<String,String>（还是一个流）
    return stream -> stream
            // ▼ mapValues：只变换每条消息的 value（不碰 key），把文字转大写
            .mapValues(value -> value.toUpperCase());
}
```

**理解**：`KStream<String, String>` 就是"一条条 `(key, value)` 消息组成的流"。`.mapValues(...)` 对每条消息的 value 做变换，返回新流——和 Java Stream 的 `.map()` 一模一样的思维。**这一步你拿到了一个"会变换的流处理器"，但还不涉及状态。**

> 这一阶段配置 `definition: echoUpper`、`echoUpper-in-0.destination=text-input`、`echoUpper-out-0.destination=upper-output`，跑一下：输入 `hello` → 输出 `HELLO`。先确认这个最简版能跑，再进下一步。

---

**第 2 步：加"拆分"——一行文字拆成多个单词（还是 KStream）**

现在加一个操作：把一行文字（如 `"hello world"`）拆成多个单词（`"hello"`、`"world"`），每条消息变多条。

```java
import org.apache.kafka.streams.kstream.KStream;
import java.util.Arrays;
import java.util.function.Function;

@Bean
public Function<KStream<String, String>, KStream<String, String>> splitWords() {
    return stream -> stream
            // ▼ flatMapValues：一条消息 → 多条消息（和 Stream.flatMap 同理）
            //   把 "hello world" 拆成 ["hello", "world"]，每个变成一条新消息
            .flatMapValues(line -> Arrays.asList(line.toLowerCase().split("\\W+")));
}
```

**理解**：`.mapValues` 是"一对一"（一条变一条），`.flatMapValues` 是"一对多"（一条变多条）。输入 `"hello world"` 一条 → 输出 `"hello"`、`"world"` 两条。**这一步拿到了"把文字拆成单词流"的能力，但还没计数。**

> 你可能问：为啥不直接统计？因为**统计（count）需要先"分组"**——而分组需要每条消息有一个明确的 key。现在每条消息是"一个单词"，但还没有"以单词为 key"。所以要先分组。

---

**第 3 步：分组 + 计数——这才变成 KTable（词频统计完整版）**

加上"按单词分组"和"计数"，输出就从 KStream 变成了 KTable（状态表）：

```java
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.springframework.context.annotation.Bean;
import java.util.Arrays;
import java.util.function.Function;

@Bean
public Function<KStream<String, String>, KTable<String, Long>> wordCount() {
    return textStream -> textStream
            // ① 拆单词（第 2 步学的）
            .flatMapValues(line -> Arrays.asList(line.toLowerCase().split("\\W+")))
            // ② 按"单词"分组：groupBy 把每条消息的 key 设为单词本身
            //    Grouped.with(组名, key的Serde, value的Serde) 告诉它怎么序列化
            .groupBy((key, word) -> word,
                     Grouped.with("word-count-group", Serdes.String(), Serdes.String()))
            // ③ 计数：每组数一下有几条 → KTable<单词, 次数>
            //    Materialized.as("word-counts") 把结果存到本地状态存储（起名 word-counts）
            .count(Materialized.as("word-counts"));
}
```

**三步合起来的逻辑**（这就是 Kafka Streams 的核心思维）：

```
输入流 "hello world hello"
    │
    │ ① flatMapValues（拆词）
    ▼
流：hello / world / hello   （3 条消息）
    │
    │ ② groupBy（按单词分组，单词当 key）
    ▼
分组：{hello: [hello, hello], world: [world]}
    │
    │ ③ count（每组计数）
    ▼
KTable（状态表）：{hello: 2, world: 1}   ← 输出，存在本地 state store
```

**两个新手必须懂的新东西**：
- **`groupBy`**：把流重新分组。**为什么分组才能计数？** 因为"计数"是"对同一组的东西数个数"——不分组就没有"组"的概念。`groupBy((key, word) -> word)` 的意思是"把每条消息重新归到'以单词为 key'的组里"。
- **`KTable`**：计数的结果不是"流"（一条条事件），而是"状态表"（每个单词的最新次数）。**流是过程，表是结果**。`Materialized.as("word-counts")` 给这个结果表起名并存到本地（RocksDB），崩溃后能恢复。

> **为什么强调三步走**：Kafka Streams 的所有程序都是这个套路——**变换（map/flatMap）→ 分组（groupBy）→ 聚合（count/aggregate）**。你只要理解这三步，90% 的 Streams 程序都能看懂。别被链式调用吓到，它就是这三步的组合。

#### 3.3.3 配置

```yaml
spring:
  cloud:
    function:
      definition: wordCount
    stream:
      kafka:
        streams:
          binder:                          # ▼ Kafka Streams binder 专属配置（已校验前缀）
            applicationId: wordcount-app   # ▼ Kafka Streams 的 application.id（必配！不同于普通 group）
            brokers: localhost:9092
            configuration:                 # ▼ 默认 Serdes（key/value 怎么序列化）
              default.key.serde: org.apache.kafka.common.serialization.Serdes$StringSerde
              default.value.serde: org.apache.kafka.common.serialization.Serdes$StringSerde
      bindings:
        wordCount-in-0:                    # ▼ KStream 输入
          destination: text-input          # 从这个 topic 读文字
        wordCount-out-0:                   # ▼ KTable 输出（binder 用 .to() 把 KTable 写回 topic）
          destination: word-counts-output  # 结果写到这个 topic（changelog）
```

**几个必须懂的点**：
- `applicationId`：Kafka Streams 的应用标识。**它兼做消费组名**（Streams 内部用 application.id 当 group.id）。不同 Streams 应用要不同 applicationId。
- `default.key.serde` / `default.value.serde`：Serde = Serializer + Deserializer。告诉 Streams key 和 value 各怎么序列化。
- KTable 输出会写到一个 topic（changelog topic），可以再被别的消费者读。

#### 3.3.4 跑起来验证

```bash
# 往 text-input 写文字
docker exec -it kafka kafka-console-producer --bootstrap-server localhost:9092 --topic text-input
> hello world hello kafka
> kafka streams hello

# 看结果（word-counts-output）
docker exec -it kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic word-counts-output --property print.key=true --property key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
# 输出（每次新词/词频变化才输出）：
# hello 1
# world 1
# hello 2
# kafka 1
# kafka 2
# streams 1
# hello 3
```

> **KTable 输出的特性**：KTable 只在**状态变化**时往下游发（这叫"changelog"）。所以同一个词第 3 次出现，你看到的是"hello 3"（最新状态），不是又发一遍 "hello 1"。

### 3.4 状态存储（State Store）与交互式查询

`Materialized.as("word-counts")` 创建的本地状态表叫 **state store**。它不只是中间产物——你可以**直接查它**（Interactive Queries）：

```java
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;

@RestController
public class QueryController {

    private final InteractiveQueryService iqService;
    public QueryController(InteractiveQueryService iqService) { this.iqService = iqService; }

    @GetMapping("/count/{word}")
    public Long getCount(@PathVariable String word) {
        // ▼ 直接查本地 state store（不用查外部 DB！）
        ReadOnlyKeyValueStore<String, Long> store = iqService.getQueryableStore(
                "word-counts", QueryableStoreTypes.keyValueStore());
        Long count = store.get(word);
        return count == null ? 0L : count;
    }
}
```

**这就是流式计算的威力**：状态在本地内存/RocksDB，查询是毫秒级，不用查远程 DB。

### 3.5 聚合（aggregate）与窗口（Windowing）

`count` 是最简单的聚合。更通用的 `aggregate`：

```java
@Bean
public Function<KStream<String, Order>, KTable<String, Double>> totalAmount() {
    return orders -> orders
            // 按用户分组
            .groupBy((key, order) -> order.getUserId(),
                     Grouped.with("user-group", Serdes.String(), new JsonSerde<>(Order.class)))
            // 聚合：累加金额
            .aggregate(
                () -> 0.0,                                          // 初始值
                (userId, order, total) -> total + order.getAmount(), // 累加器
                Materialized.with(Serdes.String(), Serdes.Double())  // 状态 Serde
            );
}
```

**时间窗口**（如"每 5 分钟的订单量"）：

```java
import org.apache.kafka.streams.kstream.TimeWindows;
import java.time.Duration;

// 按用户 + 5分钟窗口分组聚合
.groupBy((key, order) -> order.getUserId(), ...)
.windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))
.count();
```

> **窗口是流式计算的高级特性**：处理"无界数据流"时，必须用窗口把无限流切成有限块来聚合。Kafka Streams 支持时间窗口（固定/滑动/会话）。新手先理解 `count`/`aggregate`，窗口遇到再学。

### 3.6 Kafka Streams vs 普通 Kafka Binder

| 维度 | 普通 Kafka Binder（前面学的） | Kafka Streams Binder（本章） |
|------|------------------------------|----------------------------|
| 编程模型 | `Function<T,R>`（普通对象） | `Function<KStream,KTable>`（流类型） |
| 有状态？ | 无（来一条处理一条） | **有**（本地 state store） |
| 典型场景 | 收发消息、事件通知 | 实时聚合、JOIN、窗口统计 |
| 依赖 | `spring-cloud-starter-stream-kafka` | `spring-cloud-starter-stream-kafka-streams` |

> **架构师判断**：**80% 的场景用普通 Kafka Binder 就够**（收发消息）。只有需要**实时聚合/JOIN/状态计算**时才上 Kafka Streams。别为了"高级"而用 Streams。

---

## 进阶 第 4 章：响应式、背压，以及 reactive binder 为什么废弃了

> ⚠️ **这一章很重要，因为它包含一个 2025 年的重大变化。** 很多旧教程还在教你"响应式 Kafka Binder"，但**那个方案已经被官方废弃了**。学这章帮你避开这个坑。

### 4.1 入门篇埋的问题：响应式函数 + 普通 Binder 拿不到真背压

入门篇 8.1 说过：你可以写 `Function<Flux<String>, Flux<String>>` 响应式函数。但有个真相——**用普通的（非响应式）Kafka Binder，响应式只是 API 好看，拿不到真正的背压**。因为底层 Kafka 客户端是阻塞拉取的，不是响应式数据源。

### 4.2 什么是背压（Backpressure），为什么重要

**背压 = 下游告诉上游"我处理不过来，你慢点发"的机制**。

想象水管：上游（生产者）猛灌水，下游（消费者）处理慢，水管（缓冲区）会爆。**背压**让下游能反控上游速率。Reactor 的 `Flux` 天生支持背压（`request(n)` 机制）。

在消息系统里：消费者处理慢，背压让 Kafka 别一股脑把消息塞过来——而是按消费者能消化的速度来。

### 4.3 ⚠️ 2025 年 5 月：reactor-kafka 被废弃（官方公告）

**事实（已校验）**：Spring 官方 [2025-05-20 公告](https://spring.io/blog/2025/05/20/reactor-kafka-discontinued) 宣布 **Reactor Kafka 项目停止维护**。影响：

- `reactor-kafka`（`ReactiveKafkaConsumerTemplate` 等）→ **停止维护**。
- **Spring Cloud Stream 的响应式 Kafka Binder**（`spring-cloud-stream-binder-kafka-reactive`）→ **4.3.0 起废弃，未来版本移除**。

**官方原话**（已校验）：*"From the Spring Cloud Stream binder perspective, we recommend you use the **regular Kafka binder** instead of the deprecated/removed reactive binder."*

**为什么废弃**：Kafka 的消费者本质是**阻塞拉取**的（pull-based），用 Reactor 包一层增加了复杂度却没带来真正的收益。官方判断"为响应式而响应式"不划算。

### 4.4 那需要响应式 + Kafka 怎么办（官方推荐）

**官方推荐**（来自 [spring-kafka discussion #4192](https://github.com/spring-projects/spring-kafka/discussions/4192)）：用**普通 Kafka**（阻塞消费者）+ 在处理时桥接到响应式：

```java
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.function.Consumer;

// ▼ 官方推荐模式：阻塞消费 + 内部桥接到响应式处理
@Bean
public Consumer<Message<Order>> process() {
    return message -> {
        Order order = message.getPayload();
        // 把阻塞/异步的响应式处理隔离到弹性线程池
        Mono.fromRunnable(() -> reactiveService.handle(order))
            .subscribeOn(Schedulers.boundedElastic())  // ▼ 关键：隔离出阻塞线程
            .subscribe();
    };
}
```

**核心思想**：Kafka 消费者用阻塞 listener（`spring-kafka` 原生方式，被 Spring Cloud Stream 封装），**处理逻辑**里如果要用响应式（如调多个响应式服务、背压控制），用 `Schedulers.boundedElastic()` 把它隔离出去。

> **给你的结论**：
> 1. **别再用响应式 Kafka Binder**（`spring-cloud-stream-binder-kafka-reactive`）——废弃了。
> 2. 用**普通 Kafka Binder**（前面学的那些）。
> 3. 需要响应式处理？消费者用普通 Binder，处理逻辑内部用 Reactor + `Schedulers` 桥接。
> 4. 看到旧教程教 `ReactiveKafkaConsumerTemplate`，**跳过**——那是过去式。

### 4.5 那 RabbitMQ 呢

RabbitMQ Binder 目前**没有**这个废弃问题（它本来就基于阻塞的 `spring-amqp`）。如果你想用响应式 + RabbitMQ，同理：用普通 RabbitMQ Binder，处理逻辑内部桥接。

---

## 进阶 第 5 章：事件驱动架构（EDA）——架构师设计思维

前三章是"技术深挖"。这一章是"**设计高度**"——架构师怎么用事件**设计整个系统**。这一章代码少，思维多，但最值钱。

### 5.1 从"命令"到"事件"：思维的根本转变

**传统（命令式 RPC）**：服务 A 直接调服务 B 的方法，意思是"**你去做 X**"。
```java
orderService.create(order);
inventoryService.deduct(order);   // 命令：库存，你去扣减！
```

**事件驱动**：服务 A 只发一个"**发生了 X**"的事实，不关心谁来处理。
```java
events.publish(new OrderCreated(order));   // 事实：订单创建了（谁关心谁订阅）
```

**区别本质**：
- 命令 = "你去做"（强耦合，A 知道 B 存在）。
- 事件 = "发生了"（松耦合，A 不知道谁在听）。

### 5.2 事件命名（Event Naming）——新手最容易写错的地方

**铁律：事件名用"过去式/事实"，不用"命令/祈使"。**

| ❌ 错（命令式命名） | ✅ 对（事件式命名） |
|------|------|
| `CreateOrder` | `OrderCreated`（订单已创建） |
| `DeductInventory` | `InventoryDeducted`（库存已扣减） |
| `SendEmail` | `EmailRequested`（邮件已请求） |

为什么？事件是**已经发生的事实**，不是"要求别人做某事"。`OrderCreated` 表达的是"订单创建这件事已成事实"，库存/计费/通知各自决定怎么响应。如果叫 `DeductInventory`，那其实是"命令库存扣减"，又耦合回去了。

### 5.3 事件版本演进（Schema Evolution）

业务变化，事件结构要变（如 `OrderCreated` 加个 `couponCode` 字段）。怎么不破坏老消费者？

**原则**：
- **只加字段，不删/不改字段**（向后兼容）。老消费者忽略新字段，新消费者用新字段。
- 用 **Schema Registry**（如 Confluent Schema Registry + Avro）管理事件结构版本，自动校验兼容性。
- 实在要破坏性改动 → 发新版事件（`OrderCreatedV2`），老版逐步下线。

> **架构师要点**：事件是**契约**，一旦有消费者就不能随便改。版本演进要像 API 版本一样谨慎管理。

### 5.4 事件溯源（Event Sourcing）

传统：DB 存"**当前状态**"（用户余额=100）。事件溯源：DB 存"**所有事件**"（存了"充值100""消费30""消费20"...），当前状态由事件**重算**得出。

```
传统 DB：  accounts 表 → balance = 100（只存结果）
事件溯源：  events 流 → [+100, -30, -20]（存所有变化，balance = 重算 = 50）
```

**好处**：
- **完整审计**：所有变化都有记录（金融、合规刚需）。
- **时间旅行**：能重建任意时刻的状态。
- **天然适配事件驱动**。

**代价**：复杂（要处理事件重放、快照优化查询）。**只有对审计/历史有强需求才用**（金融、医疗），普通业务别上。

### 5.5 CQRS（命令查询职责分离）

传统：同一个模型既写又读。**CQRS**：写模型（命令侧）和读模型（查询侧）**分开**。

```
写侧（命令）：订单事件 → 更新写库（优化写入）
                ↓ 发事件
读侧（查询）：订阅事件 → 更新读库（优化查询，如 Elasticsearch）
```

**为什么**：写和读的优化方向不同（写要事务一致，读要快/灵活查询）。分开各优化各的。常和事件溯源搭配。

### 5.6 Saga 模式——分布式事务的解法

跨服务的业务操作（如"下单要扣库存+扣余额+加积分"，分别在不同服务）怎么保证一致性？**不能用 DB 事务**（跨服务跨库）。

**Saga**：把分布式操作拆成一串**本地事务**，每步发事件，失败时发**补偿事件**回滚。

```
正向：CreateOrder → InventoryReserved → PaymentCharged → PointsAdded
         ↑           ↓ 失败                ↓ 失败          ↓ 失败
补偿：OrderCancelled ← InventoryReleased ← PaymentRefunded ← (无)
```

每步：
1. 服务做本地事务，成功 → 发事件。
2. 下个服务订阅事件，做自己的本地事务。
3. 任一步失败 → 发补偿事件，前面的服务订阅后回滚。

**核心**：**最终一致性**（不是 ACID），靠事件 + 补偿达成。这是微服务架构的标准分布式事务方案。

> **架构师要点**：分布式系统里，**强一致（ACID）跨服务做不到**。接受**最终一致性**，用 Saga 编排。Spring Cloud Stream 正是 Saga 各步通信的理想载体（发事件、订阅事件）。

### 5.7 一句话总结架构师视角

学到这里，你应该能回答这些问题：
- **该用消息吗？** 要解耦/多消费者/异步 → 用。要同步拿结果 → 别用。
- **该用 Spring Cloud Stream 吗？** 可能换/多中间件 → 用。铁定只用一种+要极致控制 → 手写 spring-kafka。
- **该用 Kafka Streams 吗？** 要实时聚合/JOIN/状态计算 → 用。只收发 → 别用。
- **该用事件溯源/Saga 吗？** 强审计/跨服务一致性需求 → 用。普通 CRUD → 过度设计。
- **该用响应式 Kafka 吗？** **别用废弃的 reactive binder**，用普通 Binder + 内部 Reactor 桥接。

**架构师的本质**：不是会用所有技术，而是**知道每个技术的代价，在合适的场景选合适的工具**。

---

## 进阶 附录：进阶 API 校验表与踩坑

### A.1 Kafka Streams Binder 关键配置（已校验，前缀 `spring.cloud.stream.kafka.streams.binder`）

| 属性 | 说明 |
|------|------|
| `applicationId` | Kafka Streams 的 application.id（兼做消费组名），**必配** |
| `brokers` | Kafka 地址 |
| `configuration.default.key.serde` | 默认 key 序列化器 |
| `configuration.default.value.serde` | 默认 value 序列化器 |

### A.2 Kafka Streams 函数签名

```java
Function<KStream<K,V>, KStream<K,V>>      // 流→流（变换/过滤）
Function<KStream<K,V>, KTable<K,V>>        // 流→表（聚合，如 count/aggregate）
Function<KTable<K,V>, KStream<K,V>>        // 表→流（如 toStream）
Function<KStream<K,V>, GlobalKTable<K,V>>  // 流→全局表
```

### A.3 进阶踩坑

#### 坑 1：用了响应式 Kafka Binder（已废弃）
**现象**：照旧教程引了 `spring-cloud-stream-binder-kafka-reactive`。
**解决**：2025年5月起废弃。改用普通 Kafka Binder，响应式处理用 `Schedulers.boundedElastic()` 桥接（第 4 章）。

#### 坑 2：Kafka Streams 的 applicationId 没配 / 重复
**现象**：启动报错，或多个 Streams 应用互相抢消息。
**解决**：`applicationId` 必配，且每个 Streams 应用**唯一**（它兼做消费组）。

#### 坑 3：消费并发度 > 分区数
**现象**：`concurrency` 设很高，但有的消费者闲着。
**解决**：总并行度 ≤ 分区数。先加 topic 分区，再加并发。

#### 坑 4：事件命名用命令式
**现象**：事件叫 `CreateOrder`，结果越做越像 RPC 调用，耦合回来了。
**解决**：事件用过去式事实命名（`OrderCreated`）。

#### 坑 5：消费者不幂等，重复消费出数据错
**现象**：重启后部分消息重处理，扣了两次款。
**解决**：消费者**必须幂等**（幂等键去重）。at-least-once 是底线。

#### 坑 6：Kafka Streams 与普通 Kafka Binder 在一个应用里冲突
**现象**：同时引两个 starter，binding 行为异常。
**解决**：用"多 binder"配置明确区分（Streams 函数归 kafka-streams binder，普通函数归 kafka binder）。

---

## 配套学习资料

- [Spring Cloud Stream 入门到架构师](./01-Spring-Cloud-Stream从入门到架构师.md)（前置，本文的入门篇）
- [Kafka 核心概念与 Spring Boot 实战](../Kafka专题/01-Kafka核心概念与SpringBoot实战.md)（第 1 章 Kafka 基础的展开）
- [Kafka Streams Binder 官方文档](https://docs.spring.io/spring-cloud-stream/reference/kafka/kafka-streams-binder/programming-model.html)（第 3 章权威参考）
- [reactor-kafka 停止维护公告](https://spring.io/blog/2025/05/20/reactor-kafka-discontinued)（第 4 章必读）
- [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md)（手写 Kafka 实战，对比理解 Stream）

---

## 毕业下一步：装一辆能跑的车

理论学到这里，你已经"懂原理、懂设计"了。但还差最后一跃——**把这些零散知识组装成一个真正能跑的完整系统**。这是从"懂"到"会做"的跨越：

➡️ **[事件驱动微服务端到端实战](./03-事件驱动微服务端到端实战.md)**

那篇带你从零搭一个电商下单系统（订单/库存/支付三服务），落地本篇讲过的 **Saga 补偿事务、幂等表、消费组隔离**，补上"装一辆车"的实战经验。读完你才真正具备设计事件驱动系统的能力。

---

> **写在最后**：这份进阶文档从 Kafka 地基（第 1 章）讲到流式计算（第 3 章）、响应式真相（第 4 章）、架构设计（第 5 章）。**关键不是记住所有 API，而是建立"在什么场景选什么技术"的判断力**。尤其记住两个 2025 年的新事实：响应式 Kafka Binder 废弃了、事件驱动设计的本质是"发布事实而非发命令"。掌握了这些，你已经从"会用 Spring Cloud Stream"进入到"能设计事件驱动系统"的架构师领域。祝你继续精进。
