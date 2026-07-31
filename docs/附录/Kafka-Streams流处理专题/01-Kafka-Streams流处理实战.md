# Kafka Streams 流处理实战（从批处理到实时计算）

> **这份文档是什么**：一篇**独立专题**，讲 **Kafka Streams**——在消息流上做**有状态的实时计算**。它是 Kafka 自带的流处理库，Spring Cloud Stream 提供了 Kafka Streams Binder 让你用熟悉的函数式模型写。
>
> **写给谁**：读完了 [Spring Cloud Stream 专题](../Spring-Cloud-Stream专题/README.md) 02 进阶篇第 3 章（Kafka Streams 词频统计）的人。那章是入门，这篇是**深度展开**——窗口、JOIN、状态存储、交互式查询。
>
> **和 Stream 的关系**：Spring Cloud Stream 02 进阶篇只讲了 Kafka Streams 的"hello world"（词频）。真正用 Kafka Streams 做实时计算（每分钟订单量、流 JOIN、会话统计），需要这篇。本篇用 Spring Cloud Stream 的 Kafka Streams Binder，是 02 第 3 章的深化。
>
> **版本前提（已校验）**：Spring Cloud Stream 4.2.x（含 Kafka Streams Binder）+ Spring Boot 3.4.x + Kafka。窗口/JOIN/交互式查询 API 对照 [Kafka 官方 Streams DSL](https://kafka.apache.org/41/streams/developer-guide/dsl-api.html) 校验。

---

## 目录

- [第 1 章：为什么需要流处理——批处理 vs 流处理](#第-1-章为什么需要流处理批处理-vs-流处理)
- [第 2 章：窗口（Windowing）——把无限流切成有限块](#第-2-章窗口windowing把无限流切成有限块)
- [第 3 章：JOIN——流与流的关联](#第-3-章join流与流的关联)
- [第 4 章：状态存储与交互式查询](#第-4-章状态存储与交互式查询)
- [第 5 章：实战——实时电商指标](#第-5-章实战实时电商指标)
- [第 6 章：架构师取舍](#第-6-章架构师取舍)

---

## 第 1 章：为什么需要流处理——批处理 vs 流处理

### 1.1 批处理的局限

传统数据分析：白天攒数据，**夜里跑一批**统计昨天的订单量、GMV、用户数。问题：

- **延迟一天**：白天出了异常（如订单暴跌），明天才知道。
- **数据量大扛不住**：每天重算全量，数据一多就慢。

### 1.2 流处理：来一条算一条

**流处理（Stream Processing）**：数据**一来就处理**，持续维护计算结果。订单一来就累加到"今日订单量"——任意时刻查都是最新的。

```
批处理：[攒一天数据] → [夜里全量算] → 结果（延迟1天）
流处理：[来一条] → [增量更新结果]（持续，毫秒~秒级延迟）
```

**例子**：
- 每分钟各商品订单量
- 实时用户画像（订单流 JOIN 用户流）
- 异常检测（同用户 1 秒内 10 单 → 欺诈告警）

### 1.3 为什么用 Kafka Streams 而不是 Spark/Flink

| 方案 | 特点 |
|------|------|
| **Kafka Streams** | 轻量库（不是独立集群），嵌在 Spring Boot 应用里。和 Kafka 原生集成。适合"输入输出都是 Kafka topic"的场景。 |
| **Spark Streaming / Flink** | 重（独立集群），功能更强（复杂窗口、exactly-once、批流统一）。适合大规模、复杂 ETL。 |

> **本篇选 Kafka Streams**：因为我们的数据源就是 Kafka（配合前面的 Spring Cloud Stream 系统），且它轻量、和 Spring Boot 天然集成。**输入输出是 Kafka topic 的流处理，Kafka Streams 是最直接的选择。**

---

## 第 2 章：窗口（Windowing）——把无限流切成有限块

### 2.1 为什么要窗口

流是**无限的**（订单永远在产生）。无限数据没法直接聚合（"全部订单量"会无限增长）。**窗口**把无限流切成有限的时间块——"每 5 分钟一块"，每块单独聚合，结果有意义（"每 5 分钟的订单量"）。

### 2.2 四种窗口（对照 Kafka 官方校验）

| 窗口类型 | 特点 | 例子 |
|---------|------|------|
| **滚动（Tumbling）** | 固定大小、不重叠 | 每 5 分钟一块，5:00-5:05、5:05-5:10 |
| **跳跃（Hopping）** | 固定大小、按步长前进、可重叠 | 5 分钟窗口、每 1 分钟前进：5:00-5:05、5:01-5:06（重叠） |
| **滑动（Sliding）** | 以事件为中心、连续 | 每来一条事件，往前看 N 时间 |
| **会话（Session）** | 按活动间隙聚合，间隙大则切分会话 | 用户操作间隔 <30 分钟算同一会话 |

### 2.3 滚动窗口实战：每 5 分钟订单量

```java
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import java.time.Duration;
import java.util.function.Function;

@Bean
public Function<KStream<String, Order>, KTable<Windowed<String>, Long>> ordersPer5Min() {
    return orders -> orders
            // 按 productId 分组（每个商品分别统计）
            .groupBy((key, order) -> order.getProductId())
            // ▼ 滚动窗口：5分钟一块，无宽限期（grace=0，超窗口就关闭）
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))
            // 计数
            .count();
}
```

**`Windowed<String>`**：窗口化后的 key 带了窗口信息（窗口起始时间），所以查询结果能知道"哪个商品、哪个 5 分钟段"。

### 2.4 会话窗口实战：用户会话统计

```java
import org.apache.kafka.streams.kstream.SessionWindows;
import java.time.Duration;

@Bean
public Function<KStream<String, UserAction>, KTable<Windowed<String>, Long>> sessionStats() {
    return actions -> actions
            .groupBy((key, action) -> action.getUserId())
            // ▼ 会话窗口：同用户操作间隔 <30分钟算同一会话，超过则切分会话
            .windowedBy(SessionWindows.ofInactivityGapWithNoGrace(Duration.ofMinutes(30)))
            .count();   // 每个会话的操作数
}
```

**会话窗口的特殊性**：窗口大小不固定——用户活跃则窗口变长，停了 30 分钟则窗口关闭、开新会话。适合"分析用户行为模式"。

### 2.5 Grace（宽限期）——重要的窗口概念

窗口到点后，可能有**迟到的数据**（网络延迟，事件 5:04 生成但 5:07 才到）。默认窗口到点就关，迟到数据丢弃。**Grace** 给一段宽限期：

```java
// 窗口5分钟 + 1分钟宽限期：5:00-5:05的窗口，容忍迟到数据到5:06
TimeWindows.ofSizeAndGrace(Duration.ofMinutes(1), Duration.ofMinutes(5));
```

> **Grace 是窗口的"迟到容忍"**。延迟敏感用小 grace，正确性敏感用大 grace。`ofSizeWithNoGrace` = 不容忍迟到（吞吐优先）。

---

## 第 3 章：JOIN——流与流的关联

### 3.1 三种 JOIN（对照官方校验）

| JOIN | 用途 |
|------|------|
| **KStream + KTable** | 流 + 表（如订单流 JOIN 用户表，给订单补用户信息） |
| **KStream + GlobalKTable** | 流 + 全局表（每个实例有完整副本，无需 co-partition） |
| **KStream + KStream（windowed）** | 流 + 流（两个流在时间窗口内的关联，如订单流 JOIN 支付流） |

### 3.2 KStream + KTable：给订单补用户信息

场景：订单流（只有 userId）要 JOIN 用户表（userId → 用户信息），输出带完整用户信息的订单。

```java
import org.apache.kafka.streams.kstream.KTable;

@Bean
public Function<KStream<String, Order>, KStream<String, EnrichedOrder>> enrichOrder(
        KTable<String, User> userTable) {   // ▼ 用户表（KTable，状态）
    return orders -> orders
            // ▼ 流 JOIN 表：用 userId 关联
            .join(userTable,
                (order, user) -> new EnrichedOrder(order, user.getName(), user.getLevel()));
}
```

**前提**：KStream 和 KTable 要 **co-partitioned**（按相同 key、相同分区数分区）。这里订单和用户都以 userId 为 key、分区数相同。

### 3.3 GlobalKTable：无需 co-partition 的查找式 JOIN

KStream+KTable 要求 co-partition（同 key 同分区），有时做不到（如用户表分区和订单表不同）。**GlobalKTable** 每个 Streams 实例都有**完整副本**，JOIN 时直接本地查找，不要求 co-partition：

```java
@Bean
public Function<KStream<String, Order>, KStream<String, EnrichedOrder>> enrichGlobal(
        GlobalKTable<String, User> userGlobal) {
    return orders -> orders.join(userGlobal,
        (orderKey, order) -> order.getUserId(),   // ▼ 从订单里提取要 JOIN 的 key
        (order, user) -> new EnrichedOrder(order, user.getName()));
}
```

**代价**：GlobalKTable 每实例全量数据，**内存/存储消耗大**。只用于小表（如配置表、字典表）的查找。

### 3.4 KStream + KStream（窗口 JOIN）：订单 JOIN 支付

场景：订单流和支付流，30 分钟内配对（订单 → 支付），找出"下了单但 30 分钟没支付"的订单。

```java
@Bean
public BiFunction<KStream<String, Order>, KStream<String, Payment>, KStream<String, OrderPayment>>
joinOrderPayment() {
    return (orders, payments) -> orders
            // ▼ 流 JOIN 流，必须带窗口（流无界，要界定关联时间）
            .join(payments,
                (order, payment) -> new OrderPayment(order, payment),
                JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofMinutes(30)));  // 30分钟窗口内配对
}
```

> **流 JOIN 流必须带窗口**——因为流无界，不限定时间窗口关联结果会无限累积。`JoinWindows` 定义"两流事件时间差多少内算配对"。

---

## 第 4 章：状态存储与交互式查询

### 4.1 State Store——流处理的状态住哪

前面 `count`/`aggregate`/`JOIN` 都产生**状态**（计数、表）。这些状态存在哪？**不是外部数据库，是 Streams 自带的本地状态存储（state store）**——默认用 RocksDB（磁盘）+ 内存缓存。

```
事件流 → Kafka Streams → [ 本地 state store (RocksDB) ]  ← 状态在这，毫秒级读写
```

好处：状态在本地，读写极快，不依赖外部 DB。崩溃后能从 Kafka changelog 重建（容错）。

### 4.2 交互式查询（Interactive Queries）——直接查状态

State store 不只是中间产物——你可以**直接查它**（02 进阶篇第 3 章提过）。比如词频统计后，直接查"某词出现几次"，不用另存数据库。

```java
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;

@RestController
public class QueryController {

    private final InteractiveQueryService iqService;
    public QueryController(InteractiveQueryService iqService) { this.iqService = iqService; }

    @GetMapping("/count/{productId}")
    public Long getCount(@PathVariable String productId) {
        // ▼ 直接查 state store（名字来自 Materialized.as 或默认）
        ReadOnlyKeyValueStore<String, Long> store = iqService.getQueryableStore(
                "orders-per-product",   // ▼ state store 名（见 5.1 Materialized.as）
                QueryableStoreTypes.keyValueStore());
        Long count = store.get(productId);
        return count == null ? 0L : count;
    }
}
```

**这就是流处理的杀手锏**：实时计算的结果存在本地，查询毫秒级——天然适合"实时仪表盘"。

### 4.3 多实例查询的坑

State store 是**分片**的——每个 Streams 实例只持有部分分区的状态。如果某 productId 的状态在实例 A，但请求打到实例 B，B 查不到。

**解法**：`InteractiveQueryService` 会告诉你"这个 key 在哪个实例"，需要转发请求（或用 REST 互查）。这是多实例 Streams 查询的标准处理。

---

## 第 5 章：实战——实时电商指标

把前面串起来：实时计算"每分钟各商品订单量"+"订单补用户信息"+"查询接口"。

### 5.1 完整拓扑

```java
import org.apache.kafka.streams.kstream.*;

@Configuration
public class RealtimeMetrics {

    // ① 订单流 JOIN 用户 GlobalKTable（补用户信息）
    @Bean
    public BiFunction<KStream<String, Order>, GlobalKTable<String, User>, KStream<String, EnrichedOrder>>
    enrichOrders() {
        return (orders, users) -> orders.join(users,
            (k, order) -> order.getUserId(),
            (order, user) -> new EnrichedOrder(order, user));
    }

    // ② 每分钟各商品订单量（滚动窗口）
    @Bean
    public Function<KStream<String, EnrichedOrder>, KTable<Windowed<String>, Long>> ordersPerMin() {
        return enriched -> enriched
                .groupBy((k, e) -> e.getProductId())
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
                .count(Materialized.as("orders-per-product"));   // ▼ state store 名，供交互查询
    }
}
```

### 5.2 配置

```yaml
spring:
  cloud:
    function:
      definition: enrichOrders;ordersPerMin
    stream:
      kafka:
        streams:
          binder:
            applicationId: realtime-metrics-app   # ▼ 必配
            brokers: localhost:9092
            configuration:
              default.key.serde: org.apache.kafka.common.serialization.Serdes$StringSerde
              default.value.serde: org.springframework.kafka.support.serializer.JsonSerde
      bindings:
        enrichOrders-in-0:               # 订单流
          destination: orders
        enrichOrders-in-1:               # 用户 GlobalKTable
          destination: users
        enrichOrders-out-0:
          destination: enriched-orders
        ordersPerMin-in-0:
          destination: enriched-orders
        ordersPerMin-out-0:
          destination: orders-per-min
```

### 5.3 查询接口

用 4.2 的 `QueryController` 查 `orders-per-product` state store——前端实时仪表盘直接调它，看到的是**秒级更新的实时订单量**。

---

## 第 6 章：架构师取舍

### 6.1 Kafka Streams 的价值

- **实时**：来一条算一条，毫秒~秒级延迟。
- **状态本地化**：RocksDB 本地状态，快且可查询。
- **轻量**：嵌在 Spring Boot 里，不用独立集群。
- **容错**：状态从 Kafka changelog 重建。

### 6.2 代价

1. **复杂度**：窗口/JOIN/state store 概念多，学习陡。
2. **调试难**：流处理出错定位比批处理难（数据在流动）。
3. **co-partition 约束**：KStream JOIN KTable 要同 key 同分区，有时难满足。
4. **不擅长大规模 ETL**：那种用 Spark/Flink。

### 6.3 决策表

| 场景 | 用不用 Kafka Streams |
|------|---------------------|
| 输入输出是 Kafka topic 的实时计算 | ✅ 首选 |
| 实时仪表盘（配合交互式查询） | ✅ |
| 复杂 ETL / 大规模批流统一 | ❌ 用 Spark/Flink |
| 简单消息收发（无状态） | ❌ 用普通 Spring Cloud Stream |
| 要 exactly-once 强一致 | ⚠️ Kafka Streams 支持但要正确配置 |

### 6.4 架构师的一句话

> **Kafka Streams 让 Kafka 从"消息管道"升级成"实时计算平台"**。但它有适用边界——输入输出都是 Kafka、需要有状态实时计算时用它；无状态收发用普通 Stream；复杂 ETL 用 Spark。**判断标志：你的计算结果需要"持续更新、实时查询"吗？** 是→Kafka Streams；否→别用。

---

## 配套学习资料

- [Spring Cloud Stream 专题 02 进阶篇第 3 章](../Spring-Cloud-Stream专题/02-Spring-Cloud-Stream进阶实战.md)（Kafka Streams 词频入门，本篇是其深化）
- [Kafka 官方 Streams DSL 文档](https://kafka.apache.org/41/streams/developer-guide/dsl-api.html)（窗口/JOIN 权威）
- [Kafka 交互式查询文档](https://kafka.apache.org/41/streams/developer-guide/interactive-queries.html)（state store 查询）
- [Kafka 核心概念与 Spring Boot 实战](../Kafka专题/01-Kafka核心概念与SpringBoot实战.md)（Kafka 基础，分区/消费组是 Streams 前置）
- [Reactor 响应式入门](../Reactor响应式编程/01-Reactor响应式入门.md)（Streams 用到响应式思维）

---

> **写在最后**：Kafka Streams 让你从"搬消息"进入"算消息"的领域——实时聚合、流 JOIN、状态查询。它是 Kafka 生态的高阶能力，掌握后你能做实时仪表盘、实时风控、实时推荐。但记住它的边界：有状态、实时、输入输出是 Kafka——满足这三条才是它的主场。学到这儿，你的流式系统能力又上了一个台阶。
