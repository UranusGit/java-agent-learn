# Redis Streams 与 Pub/Sub 实战（Spring Boot 响应式）

> **配套文档**：[35-管数分离实战](../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 4、7 章大量使用了 Redis Streams（持久）+ Pub/Sub（实时），但主线聚焦"管数分离"，没展开讲这两个数据结构本身。本篇把它们单独拎出来，从零讲透——是什么、怎么用、和 Kafka 比怎么选。
>
> **难度假设**：你会基本的 Spring Boot，听过 Redis，但不熟 Streams/Pub/Sub。所有代码基于 Spring Boot 4.0.6 + `spring-boot-starter-data-redis-reactive`（默认 Lettuce 客户端），照抄能跑。Redis Streams/Pub/Sub 的 API 从 Spring Data Redis 2.x 到 4.x 一直稳定。

---

## 第 1 章：先搞清楚——Redis 里到底有几种"消息"

很多人把 Redis 当缓存用（`SET/GET`），但 Redis 其实有**四五种"消息/流"相关的数据结构**，容易混。先把它们分清楚：

| 结构 | 一句话 | 持久？ | 适合场景 |
|------|--------|--------|---------|
| **List** | 普通列表，`LPUSH/RPOP` | ✅ | 简单任务队列（pop 走就没了） |
| **Pub/Sub** | 广播，发了就忘 | ❌ | 实时通知（在线才能收） |
| **Stream** | 持久追加日志，可回放 | ✅ | 事件流、消息队列（本文重点） |
| **Keyspace Notification** | key 变化时通知 | ❌ | 监听 key 过期/删除 |

**最容易混的两个：Pub/Sub 和 Stream。** 一句话区分：

> **Pub/Sub 像电台广播**——播音员喊一嗓子，当时开着的收音机能听到，**没开的（晚加入的）听不到，也不补播**。断电回来啥也没有。
>
> **Stream 像录播节目库**——每期节目都**存着**，你什么时候来都能从第一期开始补看，也能从第 5 期接着看。

这就是为什么管数分离文档里**两个都用**：Stream 管持久回放（晚加入/断线重连能补看前文），Pub/Sub 管实时通知（新 chunk 立刻推给在线订阅者）。**各司其职，不是二选一。**

---

## 第 2 章：Pub/Sub——最简单的实时广播

### 2.1 原生命令

```bash
# 终端1：订阅频道 chat
redis-cli SUBSCRIBE chat

# 终端2：往频道发消息
redis-cli PUBLISH chat "你好"
# 终端1 立刻收到："你好"
```

就两个命令：`SUBSCRIBE` 订阅，`PUBLISH` 发布。**没有任何持久化**——发的时候没人订阅，这条消息就永久丢失。

### 2.2 Spring Boot 响应式实现

**配置**（和主文档第 4 章一致）：

```java
@Configuration
public class RedisConfig {
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        RedisSerializationContext<String, String> ctx = RedisSerializationContext
                .<String, String>newSerializationContext(new StringRedisSerializer())
                .key(new StringRedisSerializer()).value(new StringRedisSerializer())
                .hashKey(new StringRedisSerializer()).hashValue(new StringRedisSerializer())
                .build();
        return new ReactiveRedisTemplate<>(factory, ctx);
    }

    @Bean
    public ReactiveRedisMessageListenerContainer listenerContainer(
            ReactiveRedisConnectionFactory factory) {
        return new ReactiveRedisMessageListenerContainer(factory);
    }
}
```

**发布**（一行）：

```java
// convertAndSend = PUBLISH。返回值是"收到这条消息的订阅者数量"
redis.convertAndSend("chat", "你好").subscribe(count ->
        System.out.println("有 " + count + " 个订阅者收到"));
```

**订阅**（返回一个永不结束的 Flux）：

```java
listener.receive(ChannelTopic.of("chat"))
        .map(Message::getMessage)          // 取消息体
        .subscribe(msg -> System.out.println("收到: " + msg));
```

> **关键认知**：`listener.receive(...)` 返回的 Flux **永不结束**（complete）——只要你不取消订阅，它会一直等新消息。在 WebFlux 里把它返回给 Controller，就形成一个"一直推送的 SSE 流"。

### 2.3 Pub/Sub 的硬伤（必须知道）

1. **不持久**：发布时无人订阅 = 丢失。
2. **无回放**：新订阅者收不到订阅前的消息。
3. **无消费确认**：发出去就不管了，不知道谁收到了、谁处理失败了。
4. **一对多强制广播**：没法"只给某一个消费者"。所有订阅者都收到全量。

**所以 Pub/Sub 几乎不能单独当消息队列用**——它只适合"实时通知"这种"丢了也没关系、在线才需要"的场景。需要可靠投递、回放、消费确认时，用 Stream。

---

## 第 3 章：Stream——持久的事件日志

### 3.1 它到底长什么样

Stream 是一个**只追加（append-only）的日志**。每条消息（entry）长这样：

```
1689000000000-0     ← 这是自动生成的 ID（毫秒时间戳-序号）
  chunk: "你"        ← 消息内容（一个或多个 field:value）
  seq:   1
```

你可以把它想象成一张**带自动 ID 的、只往末尾加行的表**。任何时候都能按 ID 范围读出来。

### 3.2 核心命令速查

| 命令 | 作用 | 类比 |
|------|------|------|
| `XADD key * field value` | 追加一条消息（`*` 让 Redis 自动生成 ID） | 往日志末尾写一行 |
| `XLEN key` | 消息条数 | 日志有多少行 |
| `XRANGE key - +` | 从头读到尾 | 翻看完整日志 |
| `XREAD COUNT n STREAMS key id` | 从指定 id 之后读 | 翻到某一页继续看 |
| `XGROUP CREATE key group $` | 创建消费组 | 建一个"读书小组"，各自记进度 |
| `XREADGROUP GROUP g c COUNT n STREAMS key >` | 消费组读（`>` = 新消息） | 小组领新书 |
| `XACK key group id` | 确认处理完 | 标记"这本看完了" |
| `XPENDING key group` | 查未确认的消息 | 查"谁借了没还" |
| `XTRIM key MAXLEN n` | 裁剪到最多 n 条 | 日志只保留最近 n 行 |
| `XDEL key id` | 删除某条 | 删一行 |

### 3.3 Spring Boot 写一条（XADD）

```java
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import java.util.Map;

public Mono<RecordId> addChunk(String streamKey, String chunk, long seq) {
    StringRecord record = StreamRecords.string(
            Map.of("chunk", chunk, "seq", String.valueOf(seq)))   // field:value
            .withStreamKey(streamKey);
    return redis.opsForStream().add(record);   // 返回生成的 RecordId
}
```

> **`opsForStream()`** 是 `ReactiveRedisTemplate` 的 Stream 操作入口，方法名和 Redis 命令一一对应：`add`=XADD、`read`=XREAD、`ack`=XACK、`trim`=XTRIM、`range`=XRANGE、`size`=XLEN。

### 3.4 读全量历史（XRANGE）——晚加入者的"补看"

```java
import org.springframework.data.domain.Range;

public Flux<MapRecord<String, String, String>> readAll(String streamKey) {
    return redis.opsForStream().range(streamKey, Range.unbounded());   // XRANGE - +
}
```

每条 `MapRecord` 里，`r.getValue()` 是个 `Map<String,String>`（就是 field→value）。**这就是管数分离文档第 4 章"回放历史"的原理**——晚加入的设备调这个，能看到所有已生成的 chunk。

### 3.5 按游标续读（XREAD）——断线重连的"接着看"

```java
// 从某个 ID 之后读（比如客户端最后看到 ID=1689000000000-5）
redis.opsForStream().read(StreamOffset.create(streamKey,
        ReadOffset.from("1689000000000-5")));
```

> **管数分离文档第 5 章用 `seq` 单调号 + INCR**，而不是直接用 Stream 自动 ID，是因为自动 ID 是个长时间戳串、不直观，做 SSE `Last-Event-ID` 不好处理。但原理一样：**单调递增的 ID + 从 ID 之后读 = 断线续传**。

---

## 第 4 章：消费组——多个消费者分担工作

这是 Stream 区别于 Pub/Sub 的**杀手锏**。

### 4.1 为什么要消费组

假设有 10000 条订单消息要处理，一个消费者处理不过来。你想让 **5 个消费者分担**——每条消息只被**其中一个**处理（不是 5 个都处理）。

Pub/Sub 做不到（它是强制广播，5 个都收到）。Stream 的**消费组**就是干这个的：

```
Stream (10000 条)
   │
   ├── 消费者 A (group=workers) → 处理 1,4,7,10...
   ├── 消费者 B (group=workers) → 处理 2,5,8,11...
   └── 消费者 C (group=workers) → 处理 3,6,9,12...
```

**同一个 group 内**，每条消息只被一个消费者拿到。**不同 group** 各自独立消费全量（一个 group 像一个独立的"读书小组"，各自从头读到尾）。

### 4.2 三步走

**① 创建消费组**（只做一次）：

```java
// XGROUP CREATE orders workers $  （$ 表示只消费创建后的新消息；0 表示从头）
redis.opsForStream().createGroup("orders", ReadOffset.latest(), "workers").subscribe();
```

**② 消费者读消息**：

```java
// XREADGROUP GROUP workers consumer-1 COUNT 1 BLOCK 0 STREAMS orders >
// > 表示"我没读过的新消息"
redis.opsForStream().read(Consumer.from("workers", "consumer-1"),
        StreamOffset.create("orders", ReadOffset.lastConsumed()))
        .subscribe(record -> {
            System.out.println("消费者1 处理: " + record.getValue());
            // 处理完后必须 ACK
            redis.opsForStream().ack("orders", "workers", record.getId()).subscribe();
        });
```

**③ 确认（XACK）**：

```java
redis.opsForStream().ack("orders", "workers", record.getId());
```

### 4.3 为什么必须 ACK——"至少一次"与"未确认队列"

这是 Stream 消费组最精妙的设计，也是和 Pub/Sub 的本质区别：

> 消费者用 `XREADGROUP` 拿到一条消息后，这条消息**不会从 Stream 删除**，而是进入一个**"待确认列表"（PEL，Pending Entries List）**。只有消费者调 `XACK` 后，它才从 PEL 移除。

**意义**：如果消费者拿到消息后**崩溃了**（没来得及 ACK），这条消息**还在 PEL 里**。它可以通过 `XAUTOCLAIM` 或 `XCLAIM` 被**其他消费者重新领取**继续处理——**消息不会丢**。这叫 **at-least-once（至少一次）投递**。

对比 Pub/Sub：消费者崩了，消息直接没了。

> **代价**：at-least-once 意味着**同一条消息可能被处理多次**（处理完了、ACK 之前崩了，重启又处理一遍）。所以消费者代码必须是**幂等**的（重复处理不出错）。这是分布式系统的通用要求。

### 4.4 阻塞式监听（生产推荐用 StreamMessageListenerContainer）

上面是响应式写法。如果是传统（阻塞）Spring Boot，更常用 `StreamMessageListenerContainer`，它会自动循环拉取、自动重试：

```java
@Configuration
public class StreamConfig {
    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> container(
            RedisConnectionFactory factory) {
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder().pollTimeout(Duration.ofMillis(100)).build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(factory, options);
        // 订阅：消费组 workers，消费者 consumer-1，从最后消费位置续读
        container.receive(Consumer.from("workers", "consumer-1"),
                StreamOffset.create("orders", ReadOffset.lastConsumed()),
                record -> {
                    System.out.println("处理: " + record.getValue());
                    // 这里 ACK
                });
        container.start();
        return container;
    }
}
```

> **响应式 vs 阻塞**：WebFlux 项目用 `ReactiveRedisTemplate`；传统 MVC 项目用 `StreamMessageListenerContainer`。本文主项目是 WebFlux，所以用响应式。两者 API 不同但概念一致。

---

## 第 5 章：裁剪与保留——别让 Stream 撑爆内存

Stream 是持久追加，**不加限制会一直涨**，最终吃光内存。两个控制手段：

### 5.1 XTRIM MAXLEN——保留最近 N 条

```java
// 只保留最近 10000 条
redis.opsForStream().trim("orders", 10000, true);   // true = 近似裁剪（~，更快）
```

> **`approximate=true`（即 XTRIM ... MAXLEN ~ N）**：近似裁剪，性能更好。Redis 不保证精确裁到 N，可能裁到 N~N+几千，但开销小很多。生产推荐。

### 5.2 MAXLEN 在 XADD 时直接指定

```java
// 每次写时就封顶，省一次 TRIM
// 对应 XADD orders MAXLEN ~ 10000 * chunk hello
```

### 5.3 设置 TTL（整个 key 过期）

```java
// 整个 Stream 24 小时后自动删除
redis.expire("orders", Duration.ofHours(24)).subscribe();
```

> **管数分离文档第 4-5 章的 chunk 流**用了 MAXLEN + TTL 双保险：MAXLEN 防止单个 run 的流无限涨，TTL 让老 run 的数据自动清理。

---

## 第 6 章：Stream vs Kafka——什么时候用哪个

这是面试高频题，也是真实选型难题。

| 维度 | Redis Stream | Kafka |
|------|-------------|-------|
| **定位** | 轻量消息队列/事件流 | 重量级分布式流平台 |
| **持久** | 内存（可 RDB/AOF 落盘） | 磁盘原生 |
| **吞吐** | 万级/秒（够多数场景） | 百万级/秒 |
| **保留时长** | 靠 MAXLEN/TTL，内存成本高 | 配置保留 N 天/GB，磁盘便宜 |
| **消费组** | 有（XGROUP） | 有（更成熟） |
| **分区扩展** | 单实例，靠 Cluster 分片 | 原生多 broker 多分区 |
| **运维成本** | 低（一个 Redis） | 高（ZooKeeper/KRaft + 多 broker） |
| **延迟** | 极低（亚毫秒） | 低（毫秒级） |

**选型经验**：

- **吞吐不高（万级以内）、要低延迟、不想多引中间件、数据保留不需要很久** → **Redis Stream**。比如管数分离的 chunk 流（每次 run 几百条、跑完就清）。
- **高吞吐、跨多个服务消费、数据要长期保留（审计/回溯）、需要横向扩展** → **Kafka**。比如全站用户行为日志、订单事件总线。
- **管数分离文档的演进正好体现这个**：第 4-8 章用 Redis Stream（轻量、够用），第 9 章因为"要跨服务消费 + 保留 30 天"才升级 Kafka。

> **一句话**：**Redis Stream 是"够用就好"的轻量队列，Kafka 是"为海量而生"的重型平台。** 别一上来就 Kafka——很多场景 Redis Stream 足够，且省一个中间件。

---

## 第 7 章：常见坑

### 坑 1：Pub/Sub 用了，发现新订阅者收不到历史消息

**原因**：Pub/Sub 天然不持久。
**解决**：需要历史回放就用 Stream。管数分离文档是 Stream + Pub/Sub 配合（Stream 补历史，Pub/Sub 推实时）。

### 坑 2：消费组消费者崩了，消息卡在 PEL 里

**现象**：消息越来越少但没处理完，`XPENDING` 一堆未确认。
**解决**：用 `XAUTOCLAIM` 把超时未 ACK 的消息重新分配给存活消费者。生产必须配这套，否则消费者崩溃会导致消息"卡住"。

### 坑 3：忘了 XACK，消息被重复处理

**现象**：同一条消息被处理多次。
**原因**：没 ACK，重启后被重新领取。
**解决**：处理完一定要 ACK；消费者逻辑要幂等（重复处理不出错）。

### 坑 4：Stream 无限增长撑爆内存

**原因**：没配 MAXLEN/TTL。
**解决**：XADD 时带 `MAXLEN ~`，或定期 XTRIM，或给 key 设 TTL。

### 坑 5：响应式项目用了阻塞 RedisTemplate

**现象**：高并发卡死。
**解决**：WebFlux 必须用 `data-redis-reactive` + `ReactiveRedisTemplate`。

### 坑 6：消费组创建时报 BUSYGROUP

**原因**：消费组已存在，重复 CREATE 会报错。
**解决**：启动时先判断是否存在（`XINFO GROUPS`），不存在才创建。

---

## 总结

- **Pub/Sub**：实时广播，不持久，适合"在线通知"。
- **Stream**：持久日志，可回放，有消费组，适合"事件流/可靠队列"。
- **消费组**：同组内消息只被一个消费者处理（分担工作），不同组各自消费全量。
- **ACK + PEL**：保证 at-least-once，消费者崩了消息不丢，但要写幂等代码。
- **MAXLEN/TTL**：防 Stream 撑爆。
- **选型**：轻量用 Redis Stream，海量用 Kafka——不是越重越好，是够用就好。

学完本篇，再回头看 [管数分离文档第 4-7 章](../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md)，你会发现那些"为什么这么写"都豁然开朗。
