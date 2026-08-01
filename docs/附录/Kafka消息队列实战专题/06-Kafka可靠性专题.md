# Kafka 消息可靠性：从 at-least-once 到恰好一次（编码落地）

> **这份文档是什么**：[Kafka 消息队列实战专题](./README.md) 的第 06 篇，**可靠性专题**，而且**只讲怎么把代码写对**。前面五篇你已经会收发、懂原理、能搭系统、能做生产级 Outbox，这篇把**可靠性**这几个字拆成五段可抄的代码：幂等消费、恰好一次（EOS）全链路、延迟消息、请求-响应、可观测性埋点。**概念只做铺垫，代码占大头**。
>
> **和 01-05 的关系**（先对号入座，别重复读）：
> - [01 概念篇](./01-Kafka消息队列从入门到架构师.md) 第 8.4/9.1/9.2 章把 at-least-once、幂等生产者、消费幂等的**概念**讲透了，本篇把第 9 章挖的坑**一行一行填上**。
> - [02 调优篇](./02-Kafka进阶实战.md) 第 2.6 章给了 `@RetryableTopic` 重试/死信的**注解用法**，本篇第 1.6、3.3 章把"重试 + 幂等怎么配合"和"用重试 topic 做阶梯延迟"展开。
> - [03 实战篇](./03-事件驱动微服务端到端实战.md) 第 4.3 章的幂等表是**简化版**（`existsById` 先查），本篇第 1.3 章补上**多实例并发的唯一约束兜底**和事务边界。
> - [04 生产级篇](./04-生产级进阶-Outbox与Schema与分区调优.md) 方向 A 讲的是"写库 + 发消息"原子化的 Outbox（可靠性的一半：**发送端不丢**），本篇第 2 章补可靠性的另一半：**事务消息 + 消费幂等 = 恰好一次**，并明确"Kafka 事务为什么替代不了 Outbox"。
> - [05 实践篇](./05-全知识点实践项目.md) 阶段 9 是事务 + 幂等的**最小演示**，本篇把它升级成完整 EOS 链路。
>
> **版本前提（已校验）**：Spring Boot 4.1.0 + `spring-boot-starter-kafka`（BOM 托管版本）+ Kafka 3.x。所有 API 已对照 spring-kafka 3.x 校验，照抄能编译。本专题一律用 **`KafkaTemplate` 发、`@KafkaListener` 收**（见 [README](./README.md) 的说明）。

---

## 目录

- [第 1 章：at-least-once 的深坑与幂等消费](#第-1-章at-least-once-的深坑与幂等消费)
- [第 2 章：Exactly-Once（恰好一次）全链路](#第-2-章exactly-once恰好一次全链路)
- [第 3 章：延迟消息 / 定时消息——Kafka 没有原生延迟队列](#第-3-章延迟消息--定时消息kafka-没有原生延迟队列)
- [第 4 章：请求-响应模式（同步转异步）](#第-4-章请求-响应模式同步转异步)
- [第 5 章：可观测性代码埋点](#第-5-章可观测性代码埋点)
- [附录：依赖、测试与配置速查](#附录依赖测试与配置速查)

---

## 第 1 章：at-least-once 的深坑与幂等消费

### 1.1 先看 at-least-once 是怎么来的（1 分钟复习）

Kafka 消费者的循环是 `poll → 处理 → 提交 offset`。**"处理成功但还没提交 offset 就崩溃"** 这个窗口，决定了 Kafka 是 **at-least-once（至少一次）**：

```
poll 拉到消息 ──► 处理业务（扣库存/入账）──► 提交 offset
                          │
                          └─ 这个时刻崩溃 → 重启后 offset 没变 → 同一条消息再投递一次
```

> **结论一句话**：**Kafka 保证消息"至少被处理一次"，但不保证"只被处理一次"。重复消费是常态，不是事故。** 所以消费者侧**必须幂等**——同一条消息处理 N 次，效果必须和 1 次一样。（概念详见 [01 第 6.3 章 ack-mode](./01-Kafka消息队列从入门到架构师.md) 和 [01 第 9.2 章](./01-Kafka消息队列从入门到架构师.md)。）

### 1.2 深坑演示：不幂等的消费者会重复扣款

先写一个**错误示范**——注意它"测试的时候一切正常"：

```java
package com.example.order;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WalletConsumer {

    private final WalletRepository walletRepo;

    public WalletConsumer(WalletRepository walletRepo) {
        this.walletRepo = walletRepo;
    }

    // ❌ 反例：没有幂等，处理成功但提交 offset 前崩溃，重启会再扣一次
    @KafkaListener(topics = "orders", groupId = "wallet-group")
    public void charge(OrderEvent evt) {
        Wallet wallet = walletRepo.findById(evt.userId()).orElseThrow();
        wallet.setBalance(wallet.getBalance() - evt.amount());
        walletRepo.save(wallet);   // 用户被扣了两倍的钱
    }
}
```

**为什么会重复扣**：第 1 次扣款成功、offset 未提交时应用崩溃（或消费组 rebalance），重启后 Kafka 把同一条 `orders` 消息再投一次，`charge` 又跑一遍 → 用户被扣两次。

> **`OrderEvent` 长这样**（下面所有代码共用）：
> ```java
> package com.example.order;
>
> public record OrderEvent(String eventId, String userId, int amount) { }
> ```
> 注意有个 **`eventId`**——它是每条消息的**唯一身份证**，三种幂等方案全部靠它。

### 1.3 幂等方案 A：DB 唯一键去重（`@Id eventId`，最推荐）

**思路**：建一张"已处理事件"表，`eventId` 做主键。处理前**在同一 DB 事务里**做"业务 + 标记已处理"。`@Id` 本身是数据库唯一约束——这就是**多实例并发下的最终防线**。

```java
package com.example.order;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// ▼ 幂等表：eventId 做主键 → 数据库唯一索引，天然去重
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

    @Id
    private String eventId;   // 同一条消息的 eventId 永远相同

    protected ProcessedMessage() { }

    public ProcessedMessage(String eventId) {
        this.eventId = eventId;
    }
}
```

```java
package com.example.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {
}
```

**关键在第三个类 `WalletService`**——**业务 + 标记已处理必须放进同一个 `@Transactional` 方法**，而且这个方法要放在**另一个 bean** 里（不能是消费者自己调自己：Spring AOP 事务代理对"同类内部自调用"不生效）：

```java
package com.example.order;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WalletConsumer {

    private final WalletService walletService;

    public WalletConsumer(WalletService walletService) {
        this.walletService = walletService;
    }

    @KafkaListener(topics = "orders", groupId = "wallet-group")
    public void charge(OrderEvent evt) {
        // ▼ 通过另一个 bean 调用，事务代理才生效
        walletService.chargeWithIdempotency(evt);
    }
}
```

```java
package com.example.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private final WalletRepository walletRepo;
    private final ProcessedMessageRepository processedRepo;

    public WalletService(WalletRepository walletRepo,
                         ProcessedMessageRepository processedRepo) {
        this.walletRepo = walletRepo;
        this.processedRepo = processedRepo;
    }

    // ▼ 关键：业务 + 标记已处理 在同一个 DB 事务里（外部 bean 调用 → 代理生效）
    @Transactional
    public void chargeWithIdempotency(OrderEvent evt) {
        // ① 幂等检查（单实例下省一次 DB 写）
        if (processedRepo.existsById(evt.eventId())) {
            return;   // 处理过，直接跳过
        }

        // ② 业务
        Wallet wallet = walletRepo.findById(evt.userId()).orElseThrow();
        wallet.setBalance(wallet.getBalance() - evt.amount());
        walletRepo.save(wallet);

        // ③ 标记已处理（和业务同一个事务）
        processedRepo.save(new ProcessedMessage(evt.eventId()));
    }
}
```

**为什么多实例并发也不会重复扣**——这是本方案的精髓，画出来：

```
实例 A 和实例 B 同时 poll 到同一条 eventId=evt-1：
  A: existsById(evt-1) → false        B: existsById(evt-1) → false（还没提交）
  A: 扣款 + 插 ProcessedMessage(evt-1)
  B: 扣款 + 插 ProcessedMessage(evt-1) → ❗主键冲突 DataIntegrityViolationException
       → B 的整个 @Transactional 回滚 → B 的扣款也一起回滚 ✅
```

> **要点**：`existsById` 先查只是**优化**（少写一次），真正的保证是**唯一约束 + 同一事务**。因为 `@Id` 建了唯一索引，两个实例都插同一主键时第二个必然冲突、事务整体回滚，**重复的业务也被回滚**。这就是 [03 第 4.3 章](./03-事件驱动微服务端到端实战.md) 说的"唯一约束是双保险"。

### 1.4 幂等方案 B：Redis setnx 去重（无 DB 或想用缓存）

**思路**：用 Redis 的 `SETNX`（`setIfAbsent`）原子地去占位。**只有第一次能占上**，天然防多实例并发。

```java
package com.example.order;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class WalletConsumer {

    private final WalletRepository walletRepo;
    private final StringRedisTemplate redisTemplate;

    private static final Duration DEDUP_TTL = Duration.ofHours(24);  // 去重 key 保留时间

    public WalletConsumer(WalletRepository walletRepo, StringRedisTemplate redisTemplate) {
        this.walletRepo = walletRepo;
        this.redisTemplate = redisTemplate;
    }

    @KafkaListener(topics = "orders", groupId = "wallet-group")
    public void charge(OrderEvent evt) {
        // ▼ setnx：成功返回 true（只有第一次），失败返回 false（已处理过）
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent("msg:" + evt.eventId(), "1", DEDUP_TTL);

        if (!Boolean.TRUE.equals(first)) {
            return;   // 别人已经处理过了
        }

        try {
            // 业务：扣款
            Wallet wallet = walletRepo.findById(evt.userId()).orElseThrow();
            wallet.setBalance(wallet.getBalance() - evt.amount());
            walletRepo.save(wallet);
        } catch (Exception e) {
            // ▼ 重要：业务失败要删掉 key，否则这条消息永远不会再被处理（丢了）
            redisTemplate.delete("msg:" + evt.eventId());
            throw e;
        }
    }
}
```

> **坑要讲清楚**：
> 1. **业务失败必须删 key**。setnx 的 key 在"拿到占位"那一刻就写入了，如果业务抛异常，不删 key，这条消息重试时会被当成"已处理"跳过 → **消息丢失**。上面的 `catch` 就是干这个的。
> 2. **TTL 要大于"这条消息可能重投的最长时间"**（一般是消费组的重试/死信周期），否则 TTL 过期后同一条消息可能再被处理一次。
> 3. Redis 去重**不是数据库事务**，扣款和 setnx 是两件事。极端情况下（扣款成功但删 key 前的某步），仍要靠业务层的对账。**追求强一致还是回到方案 A 的 DB 唯一约束**。
> 4. 依赖 Redis 高可用，若 Redis 挂了要降级方案（先查 DB 或直接拒收）。

### 1.5 幂等方案 C：状态机去重（比 status，靠 CAS 抢占）

**思路**：不额外建幂等表，直接用业务实体自身的**状态字段**做"比较并交换（CAS）"。适合"订单状态推进""任务只执行一次"这类**有明确状态机**的场景。

假设订单实体有状态 `NEW → PROCESSED`：

```java
package com.example.order;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

@Entity
public class Order {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.NEW;   // 初始 NEW

    private int amount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }

    public enum OrderStatus { NEW, PROCESSED }
}
```

```java
package com.example.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, String> {

    // ▼ 原子 CAS：只有当前状态是 NEW 才会把状态翻成 PROCESSED，返回影响的记录数
    @Modifying
    @Query("update Order o set o.status = 'PROCESSED' where o.id = :id and o.status = 'NEW'")
    int tryMarkProcessed(@Param("id") String id);
}
```

**CAS 的 `UPDATE` 是单条 SQL（本身原子），但"抢占 + 改字段"必须放进同一个 `@Transactional` 方法**——同样放到独立 bean（`OrderService`）里，别让消费者自己调自己：

```java
package com.example.order;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderConsumer {

    private final OrderService orderService;

    public OrderConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = "orders", groupId = "order-group")
    public void onOrder(OrderEvent evt) {
        orderService.processOnce(evt);
    }
}
```

```java
package com.example.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepo;

    public OrderService(OrderRepository orderRepo) {
        this.orderRepo = orderRepo;
    }

    // ▼ 抢占 + 改字段 同一个事务：要么一起提交，要么一起回滚
    @Transactional
    public void processOnce(OrderEvent evt) {
        // ▼ CAS 抢占：返回 1 = 本实例拿到处理权；返回 0 = 已被别人处理过，直接跳过
        int claimed = orderRepo.tryMarkProcessed(evt.eventId());
        if (claimed == 0) {
            return;   // 幂等：跳过
        }

        Order order = orderRepo.findById(evt.eventId()).orElseThrow();
        // 到这里，状态已经是 PROCESSED 且只有这一个事务持有 → 放心做业务
        order.setAmount(evt.amount());
        // 事务提交后，PROCESSED 状态 + 业务字段一起落库
    }
}
```

**为什么 `UPDATE ... WHERE status='NEW'` 就是幂等**：数据库的行锁保证同一时刻只有**一个**事务能把 `NEW` 翻成 `PROCESSED`，第二个事务的 UPDATE 匹配不到行、影响行数为 0。这和 1.3 的唯一约束是**同一个物理原理**（约束/条件保证只有一次能成功），只是把"标记已处理"做进了业务实体自己。

> **三种方案怎么选**（先给结论，详细对比在 3.5）：
> | 方案 | 依赖 | 适用 | 代价 |
> |------|------|------|------|
> | A. DB 唯一键 `@Id` | 业务 DB | **通用，最推荐** | 多一张表/多一次写 |
> | B. Redis setnx | Redis | 已有 Redis、要低延迟 | 业务失败要删 key、非事务 |
> | C. 状态机 CAS | 业务 DB | 实体本身有状态机 | 需要能写 UPDATE 条件 |

### 1.6 重试时如何不重复处理（幂等 × 重试的配合）

有了 `@RetryableTopic`（[02 第 2.6 章](./02-Kafka进阶实战.md)、[05 阶段 3](./05-全知识点实践项目.md)），消息失败会被**重投**——但重投的还是**同一条消息、同一个 `eventId`**。所以：**幂等检查必须放在业务最前面，且"已处理过"要正常 return，不要抛异常**（抛异常会被当成"处理失败"继续重试）。

```java
package com.example.order;

import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class WalletConsumer {

    private final WalletService walletService;

    public WalletConsumer(WalletService walletService) {
        this.walletService = walletService;
    }

    // ▼ 失败自动重试：1 次原始 + 3 次重试（间隔 1s、2s、4s）
    @RetryableTopic(
        topics = "orders",
        attempts = "4",
        backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = "orders", groupId = "wallet-group")
    public void charge(OrderEvent evt) {
        walletService.chargeWithIdempotency(evt);   // 复用 1.3 的幂等事务方法（外部 bean 调用）
    }

    // ▼ 重试 4 次全失败才进死信（orders-dlt）
    @DltHandler
    public void onDlt(String payload,
                      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                      @Header(KafkaHeaders.EXCEPTION_MESSAGE) String error) {
        // 落库 / 告警，人工补偿
        System.err.println("[DLT] 重试耗尽：" + topic + " error=" + error + " payload=" + payload);
    }
}
```

> **为什么重试不会重复处理**：重投的还是**同一条 `eventId`**，`WalletService.chargeWithIdempotency` 里的 `existsById` 检查在业务**最前面**，且"已处理过"分支**静默 return（不抛异常）** → 不触发重试、也不进死信。

**这里最容易犯的错**：幂等检查放在**业务之后**（先扣款、再写幂等表），或"已处理"分支里 `throw new RuntimeException`——前者崩溃窗口会重复扣款，后者会把"已处理"的消息反复重试到死信。**幂等检查必须第一条、必须静默返回。**

> **本章验证**：
> 1. 发一条正常消息 → 控制台处理 1 次，`processed_message` 表多一行。
> 2. **模拟崩溃窗口**：给 `WalletConsumer` 加 `@Scheduled(fixedDelay=...)` 在扣款后、提交 offset 前 `System.exit(1)`，重启应用，观察：有幂等表 → 只扣一次；去掉幂等表（1.2 的代码）→ 扣两次。
> 3. 多实例并发：开两个实例、同一个消费组、发同一条 `eventId` → 只有一实例处理成功，另一个 `DataIntegrityViolationException` 回滚。

---

## 第 2 章：Exactly-Once（恰好一次）全链路

### 2.1 一句话公式：事务消息 + 消费幂等 = EOS

"恰好一次（End-to-End Exactly-Once, EOS）"不是某一项配置，而是**四层机制拼起来的**：

| 层 | 机制 | 干掉的是什么重复 |
|----|------|----------------|
| ① 幂等生产者 | `enable.idempotence=true` | 生产者**网络重试**导致的 broker 端重复消息 |
| ② 生产者事务 | `transactional.id` + `@Transactional` | **一批消息要么全可见、要么全不可见**（不是重复问题，是原子性问题） |
| ③ 消费端隔离 | `isolation.level=read_committed` | 消费者**看不到**未提交/已回滚事务的消息 |
| ④ 消费幂等 | 第 1 章三种方案 | 消费者 at-least-once **重复投递** |

> **"事务消息 + 消费幂等 = EOS"**：Kafka 事务保证**发送端**"一批消息原子可见、回滚消息永不出现"，`read_committed` 让**消费端**只读到已提交的；但消费端依然可能**重复处理**（崩溃窗口），所以**最后一道闸门永远是第 1 章的消费幂等**。四层合起来才是端到端恰好一次。

### 2.2 幂等生产者：`enable.idempotence`

Kafka 3.0+ 默认开启（Kafka 协议层自动给每条消息打上 **producerId + 序列号**，broker 端去重）。概念见 [01 第 9.1 章](./01-Kafka消息队列从入门到架构师.md)，代码层**显式写出来**并配上 `acks=all`：

```yaml
spring:
  kafka:
    producer:
      enable-idempotence: true   # 幂等生产者（Broker 去重，防重试重复）
      acks: all                  # 开启幂等后 acks 会被强制提升为 all，显式写出更清楚
```

### 2.3 事务生产者：`transactional.id` 与 `transaction-id-prefix` 自动装配

要启用 Kafka 事务，给生产者配一个**事务 ID 前缀**，Boot 会自动把 `ProducerFactory` 变成事务性的，并且**自动装配一个 `KafkaTransactionManager`**：

```yaml
spring:
  kafka:
    producer:
      enable-idempotence: true
      transaction-id-prefix: eos-   # ▼ 事务 ID 前缀 → 开启 Kafka 事务
      acks: all
```

**Boot 的自动装配规则（务必搞清）**：

> - 只要配了 `transaction-id-prefix` 且项目里**没有别的 `TransactionManager`**，Boot 的 `KafkaAutoConfiguration` 会自动创建一个 `KafkaTransactionManager` bean（名字叫 `kafkaTransactionManager`），此时 `@Transactional` 直接用它。
> - **如果项目里有 DB（JPA/JDBC），就会存在 `DataSourceTransactionManager`** → Boot 的条件装配失效 → 你必须**手动声明 `KafkaTransactionManager`**。生产项目几乎都有 DB，所以**手动声明是常态**，代码给你备好：

```java
package com.example.order;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.KafkaTransactionManager;

@Configuration
public class KafkaTxConfig {

    // ▼ 显式声明 Kafka 事务管理器。配了 transaction-id-prefix 后 ProducerFactory 才是事务性的
    @Bean
    public KafkaTransactionManager<Object, Object> kafkaTransactionManager(
            ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTransactionManager<>(producerFactory);
    }
}
```

> **多实例注意**：`transaction-id-prefix` 每个实例必须唯一，否则 `transactional.id` 冲突（同一事务 ID 被两个实例抢用）。生产写成 `eos-${random.uuid}`。

### 2.4 `@Transactional` 里发消息

事务性 `KafkaTemplate` 的发送**不会立即对外可见**——消息被缓存在事务里，**事务提交时一起写入、对 `read_committed` 消费者可见；事务回滚则一条都不出现**：

```java
package com.example.order;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    // ▼ 关键：指定用 Kafka 事务管理器。三条消息要么全发、要么一条不发
    @Transactional("kafkaTransactionManager")
    public void publishOrderBundle(OrderEvent evt) {
        kafkaTemplate.send("orders", evt.eventId(), evt);
        kafkaTemplate.send("payments", evt.eventId(),
                new PaymentEvent(evt.eventId(), evt.userId(), evt.amount()));
        kafkaTemplate.send("audit", evt.eventId(), "created");
        // 事务提交：read_committed 消费者同时看到这 3 条；中途任何失败 → 3 条都不可见
    }
}
```

> **重要**：配了 `transaction-id-prefix` 之后，`KafkaTemplate.send()` **必须**在事务里调用，否则抛 `No transaction is in process`（[01 附录坑 8](./01-Kafka消息队列从入门到架构师.md)）。

### 2.5 消费端隔离级别：`isolation.level: read_committed`

消费者配 `read_committed` 后：**只看到已提交事务的消息，事务回滚/未提交的消息直接跳过**。这是 EOS 消费端必须的配套：

```yaml
spring:
  kafka:
    consumer:
      group-id: wallet-group
      enable-auto-commit: false        # 事务消费建议手动 ack（配合容器事务，见 2.6）
      auto-offset-reset: earliest
      isolation-level: read_committed  # ▼ EOS 关键：只见已提交事务的消息
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
        spring.json.value.default.type: com.example.order.OrderEvent
```

> **trade-off**：`read_committed` 会限制未提交消息的可见性，消费端读到的是"已提交事务"的快照，对**大量高频小事务**会略增延迟。纯消息通知场景（不在乎事务回滚消息）用默认 `read_uncommitted` 即可，别为了"显得高级"盲目上 EOS。

### 2.6 完整代码链：生产端 + 消费端一次跑通

**① 配置**（`application.yaml`，事务生产者 + read_committed 消费者）：

```yaml
spring:
  application:
    name: order-service
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      enable-idempotence: true
      transaction-id-prefix: eos-${random.uuid}   # 每实例唯一
      acks: all
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: wallet-group
      enable-auto-commit: false
      auto-offset-reset: earliest
      isolation-level: read_committed
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
        spring.json.value.default.type: com.example.order.OrderEvent
    listener:
      ack-mode: record   # ▼ 每条处理完、同步方法返回后才提交 offset（配合幂等表 → at-least-once + 去重）
```

**② 事务管理器 + 手动声明**（有 DB 时）：

```java
package com.example.order;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.KafkaTransactionManager;

@Configuration
public class KafkaTxConfig {

    @Bean
    public KafkaTransactionManager<Object, Object> kafkaTransactionManager(
            ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTransactionManager<>(producerFactory);
    }
}
```

**③ 生产端：`@Transactional` 发事务消息**：

```java
package com.example.order;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional("kafkaTransactionManager")
    public String createOrder(OrderRequest req) {
        String eventId = "evt-" + System.currentTimeMillis();

        // ▼ 事务消息：订单 + 支付 + 审计 三条，要么全可见要么全不可见
        kafkaTemplate.send("orders", eventId,
                new OrderEvent(eventId, req.userId(), req.amount()));
        kafkaTemplate.send("payments", eventId,
                new PaymentEvent(eventId, req.userId(), req.amount()));
        kafkaTemplate.send("audit", eventId, "order-created");

        return eventId;
    }
}
```

**④ 消费端：`read_committed` + 消费幂等（收尾闸门）**：

```java
package com.example.order;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WalletConsumer {

    private final WalletService walletService;

    public WalletConsumer(WalletService walletService) {
        this.walletService = walletService;
    }

    // ▼ read_committed 保证：只收到已提交事务的消息；
    //   消费幂等（WalletService 的事务方法）保证：就算 at-least-once 重复投递，也只处理一次
    @KafkaListener(topics = "payments", groupId = "wallet-group")
    public void onPayment(PaymentEvent evt) {
        walletService.creditWithIdempotency(evt);
    }
}
```

```java
package com.example.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private final WalletRepository walletRepo;
    private final ProcessedMessageRepository processedRepo;

    public WalletService(WalletRepository walletRepo,
                         ProcessedMessageRepository processedRepo) {
        this.walletRepo = walletRepo;
        this.processedRepo = processedRepo;
    }

    // ▼ 入账 + 标记已处理，同一个 DB 事务（原子）
    @Transactional
    public void creditWithIdempotency(PaymentEvent evt) {
        if (processedRepo.existsById(evt.eventId())) {
            return;
        }
        Wallet wallet = walletRepo.findById(evt.userId()).orElseThrow();
        wallet.setBalance(wallet.getBalance() + evt.amount());
        walletRepo.save(wallet);
        processedRepo.save(new ProcessedMessage(evt.eventId()));
    }
}
```

**这条链的完整语义**：生产者把"订单/支付/审计"三条放进**同一个 Kafka 事务** → 事务提交前消费者（`read_committed`）**看不到**任何一条 → 事务回滚时三条一起消失，消费者**永远不会**收到"半套"消息 → 就算消费者崩溃重复投递，**幂等表**兜底只处理一次。**这就是 EOS 的完整落地。**

### 2.7 局限与诚实结论（别把 Kafka 事务当万能药）

**必须说清楚 Kafka 事务解决不了什么**（呼应 [04 方向 A](./04-生产级进阶-Outbox与Schema与分区调优.md) 的 Outbox 讨论）：

| 需求 | Kafka 事务能吗 | 正解 |
|------|:---:|------|
| 一批 Kafka 消息原子可见 | ✅ | 本节代码 |
| **"写 DB + 发 Kafka"原子** | ❌ 要 ChainedTransactionManager 跨资源两阶段提交，性能差、易出问题 | **Outbox 模式**（[04 方向 A](./04-生产级进阶-Outbox与Schema与分区调优.md)），把"发消息"降级成"事务里写 outbox 表" |
| 消费端重复投递 | ❌ 这是 at-least-once 语义决定的 | **消费幂等**（第 1 章） |

> **进阶（可选）**：如果连"消费 + 发消息 + 提交 offset"都想原子，可以上**事务性消费者**——在 `@KafkaListener` 方法上加 `@Transactional("kafkaTransactionManager")`，**前提是给 listener 容器工厂配好同一个 `KafkaTransactionManager`**（`factory.setTransactionManager(kafkaTransactionManager)`），这样 Spring Kafka 会把 **offset 提交也并入 Kafka 事务**：处理失败 → 事务回滚 → offset 不提交 → 消息被重投。但**它依然是 Kafka 内部原子，不跨 DB**，跨 DB 仍要靠 Outbox。

> **本章验证**：
> 1. 起一个普通消费者（`read_uncommitted`）和一个 `read_committed` 消费者订阅 `payments`。
> 2. 调 `createOrder` 正常发 3 条 → 两个消费者都能看到。
> 3. 在 `@Transactional` 方法里**人为抛异常**让事务回滚 → `read_committed` 消费者**一条都看不到**，`read_uncommitted` 能看到未提交的那批（随后变孤儿）。
> 4. `kafka-console-consumer --bootstrap-server localhost:9092 --topic payments --isolation-level read_committed` 观察。

---

## 第 3 章：延迟消息 / 定时消息——Kafka 没有原生延迟队列

### 3.1 为什么 Kafka 没有原生延迟队列

Kafka 是**日志**（append-only log），消息一进 topic 就**尽可能快地**被消费者拉走，它**没有"每条消息什么时候才允许被消费"的定时器**。想"这条消息 10 分钟后再处理"，Kafka 本身做不到——这是 RabbitMQ 延迟插件、Pulsar 延迟消息追踪器干的事。**在 Kafka 世界，延迟队列要靠业务层自己实现**。下面是三种编程实现。

### 3.2 实现一：`@Scheduled` 定时扫描 DB 到期记录再发（推荐）

**思路**：不直接发 Kafka，而是先把"将来要发的消息"存进一张表（带着 `fireAt` 时间），一个**定时任务**每秒扫描到期记录 → 发到 Kafka → 标记已发。数据库是"延迟队列"，Kafka 是"投递通道"。

**① 建表 + 实体**：

```sql
CREATE TABLE scheduled_message (
    id       BIGSERIAL PRIMARY KEY,
    topic    VARCHAR(128) NOT NULL,
    msg_key  VARCHAR(128),
    payload  TEXT         NOT NULL,
    fire_at  TIMESTAMPTZ  NOT NULL,
    status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',   -- PENDING / SENT / FAILED
    attempts INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_scheduled_fire ON scheduled_message (status, fire_at);
```

```java
package com.example.delay;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "scheduled_message")
public class ScheduledMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String topic;
    private String msgKey;

    @Column(columnDefinition = "TEXT")
    private String payload;

    private Instant fireAt;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private int attempts;

    // ▼ getter/setter 省略（IDE 生成）

    public enum Status { PENDING, SENT, FAILED }
}
```

**② Repository**：

```java
package com.example.delay;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessage, Long> {

    // ▼ 查"已到期还没发"的记录，按时间先后
    @Query("select m from ScheduledMessage m " +
           "where m.status = com.example.delay.ScheduledMessage.Status.PENDING " +
           "and m.fireAt <= :now order by m.fireAt")
    List<ScheduledMessage> findDue(@Param("now") Instant now, Pageable pageable);
}
```

**③ 对外 API：把"延迟发送"收进来**：

```java
package com.example.delay;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class DelayedMessageService {

    private final ScheduledMessageRepository repo;

    public DelayedMessageService(ScheduledMessageRepository repo) {
        this.repo = repo;
    }

    // ▼ 业务调用方：想延迟 10 分钟发，就调 delay(topic, key, payload, Duration.ofMinutes(10))
    @Transactional
    public void delay(String topic, String key, String payload, Duration delay) {
        ScheduledMessage m = new ScheduledMessage();
        m.setTopic(topic);
        m.setMsgKey(key);
        m.setPayload(payload);
        m.setFireAt(Instant.now().plus(delay));
        m.setStatus(ScheduledMessage.Status.PENDING);
        repo.save(m);
    }
}
```

**④ 定时扫描器**（配合 `@EnableScheduling`）：

```java
package com.example.delay;

import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class DueMessagePoller {

    private final ScheduledMessageRepository repo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public DueMessagePoller(ScheduledMessageRepository repo,
                            KafkaTemplate<String, String> kafkaTemplate) {
        this.repo = repo;
        this.kafkaTemplate = kafkaTemplate;
    }

    // ▼ 每秒扫一次到期记录（fixedDelay：上一次跑完再等 1s，避免任务重叠）
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        for (ScheduledMessage m : repo.findDue(Instant.now(), PageRequest.of(0, 100))) {
            try {
                kafkaTemplate.send(m.getTopic(), m.getMsgKey(), m.getPayload());
                m.setStatus(ScheduledMessage.Status.SENT);   // 发成功才标记
            } catch (Exception e) {
                m.setAttempts(m.getAttempts() + 1);
                if (m.getAttempts() >= 3) {
                    m.setStatus(ScheduledMessage.Status.FAILED);  // 多次失败标记，人工补偿
                }
            }
        }
    }
}
```

**⑤ 开启定时任务**：

```java
package com.example.delay;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling   // ▼ 让 @Scheduled 生效（整个专题第一次出现，记住这个注解）
public class SchedulingConfig {
}
```

> **多实例部署的坑**：应用扩到多实例后，**每个实例都会跑这个扫描器** → 同一条到期记录可能被多个实例发出去。解法（按复杂度选）：
> 1. 把扫描 SQL 改成 `SELECT ... FOR UPDATE SKIP LOCKED`（悲观锁，DB 自带，推荐）；
> 2. 用分布式锁包住扫描任务（[Redis 分布式锁实战](../Redis专题/02-Redis分布式锁实战.md)）；
> 3. 接受重复发送，靠**消费端幂等**兜底（把第 1 章的 `eventId` 去重用上）。
> 真实生产一般 **1 + 3 组合**：发送端少重复，消费端不怕重复。

### 3.3 实现二：重试 topic 阶梯延迟（`@RetryableTopic` + `@Backoff` 变体）

**思路**：`@RetryableTopic` 的每一次"重试"本身就是**投到下一个延迟递增的重试 topic**。把"处理必失败"的监听器当成一个**定时炸弹**——故意抛异常触发重试，每跳一档就是一次延迟。用 `SUFFIX_WITH_DELAY_VALUE` 让 topic 名直接显示延迟值，看得见"阶梯"：

```java
package com.example.delay;

import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class LadderDelayConsumer {

    // ▼ attempts=5 → 1 次原始 + 4 档重试，每档延迟翻倍：1s / 2s / 4s / 8s
    //   topicSuffixingStrategy=SUFFIX_WITH_DELAY_VALUE → 重试 topic 名带延迟值：
    //     orders-retry-1000 / orders-retry-2000 / orders-retry-4000 / orders-retry-8000
    @RetryableTopic(
        topics = "orders",
        attempts = "5",
        backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_DELAY_VALUE)
    @KafkaListener(topics = "orders", groupId = "delay-demo-group")
    public void onOrder(String payload) {
        // ▼ 故意失败触发重试 = 把消息推入延迟阶梯
        //    实战里就是"还不到执行时间就抛异常"：if (now < fireAt) throw new RuntimeException("未到期");
        throw new RuntimeException("模拟未到期，交给重试阶梯延迟");
    }

    // ▼ 4 档延迟走完后进死信，这里就是"真正到期"要做的事（或人工检查）
    @DltHandler
    public void onDue(String payload) {
        System.out.println("[到点执行] " + payload);
    }
}
```

**这个方案的优缺点要诚实**：
- **优点**：零新增组件、纯注解、不用建表；适合"**固定档位**的延迟"（1s/2s/4s/8s）。
- **缺点**：
  1. 重试 topic 会**积压消费 lag**（延迟期间消息就躺在重试 topic 里），lag 监控会误报；
  2. 延迟档位**硬编码**，改成"10 分钟后"要调注解；
  3. 每档都要建 topic，topic 数量膨胀。
  4. 如果你想要"每档不同、非指数"的延迟，用注解做不到（注解只有 `delay + multiplier`），得用 `RetryTopicConfigurationBuilder.exponentialBackoff(long initial, double multiplier, long maxInterval)` 编程式配置。

### 3.4 实现三：消费端判定的坏方案 vs 好方案（时间窗 / Thread.sleep）

**❌ 坏方案 1：`Thread.sleep` 硬等**——这是新手最常写的，必须知道它错在哪：

```java
package com.example.delay;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class BadDelayConsumer {

    // ❌ 反例：用 Thread.sleep 让消费者线程睡到"到点"
    @KafkaListener(topics = "scheduled-tasks", groupId = "bad-demo-group")
    public void bad(OrderEvent evt) throws InterruptedException {
        Duration wait = Duration.between(Instant.now(), evt.fireAt());
        if (!wait.isNegative() && !wait.isZero()) {
            Thread.sleep(wait.toMillis());   // ❌ 容器线程被占死
        }
        doWork(evt);
    }

    private void doWork(OrderEvent evt) { /* 业务 */ }
}
```

**为什么坏**：
1. **占住容器线程**：`@KafkaListener` 容器线程被 `sleep` 占住，不再 `poll` → 心跳超时被踢出消费组、触发 rebalance（[02 第 1.5 章](./02-Kafka进阶实战.md) 讲过的消费组机制）。
2. **吞吐崩塌**：睡 10 分钟 = 这个分区 10 分钟内一条都处理不了，lag 直线飙升。
3. **崩溃即重来**：睡到一半实例挂了，重启后消息重投，`sleep` 从头开始——延迟被"重置"。
4. **无法横向扩容**：sleep 不释放线程，加实例只是增加"集体睡觉"的线程。

**❌ 坏方案 2：时间窗轮询判定**——比 sleep 稍好，但本质一样堵：

```java
// ❌ 反例：循环等到期。虽然不一次性睡满，但同样占线程 + 忙等
@KafkaListener(topics = "scheduled-tasks", groupId = "bad-demo-group")
public void bad(OrderEvent evt) throws InterruptedException {
    while (Instant.now().isBefore(evt.fireAt())) {
        Thread.sleep(1000);   // 每秒查一次，还是占着容器线程
    }
    doWork(evt);
}
```

**✅ 好方案的本质**：**别让消费者线程"等待"，而是把"到点的判定"交给独立机制**。三条路，按代价从小到大：
1. **到期再发**（3.2）：消息**根本不进**业务 topic，存 DB 等扫描器到点再发。消费者永远只收到"到点"的消息——**消费者不需要等**。
2. **延迟阶梯 topic**（3.3）：用重试 topic 天然延迟，消费者收到的是"该处理"的下一跳。
3. **到期时间放消息头 + 消费者把未到期消息"放回"**：消费者发现未到期，就把消息投到一个"等待区 topic"（`not-yet-due`），下次轮询再来——**不阻塞当前线程**。这是 RabbitMQ 延迟队列思路的 Kafka 移植。

**放回式**的关键代码（体现"不阻塞、不丢"）：

```java
package com.example.delay;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TimeWindowConsumer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TimeWindowConsumer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "scheduled-tasks", groupId = "tw-demo-group")
    public void onTask(OrderEvent evt) {
        if (Instant.now().isBefore(evt.fireAt())) {
            // ▼ 未到期：放回等待区，不 sleep、不占线程；下一次扫描再来
            kafkaTemplate.send("not-yet-due", evt.eventId(), evt);
            return;
        }
        doWork(evt);
    }

    // ▼ 等待区每隔一段时间被触发一次（用 @Scheduled 往 waiting 发个"唤醒"消息，
    //   或直接把等待区消费线程的 poll 间隔当时间窗）
    @KafkaListener(topics = "not-yet-due", groupId = "tw-demo-group")
    public void onRecheck(OrderEvent evt) {
        // 再次判定，未到期就继续放回
        if (Instant.now().isBefore(evt.fireAt())) {
            kafkaTemplate.send("not-yet-due", evt.eventId(), evt);
            return;
        }
        doWork(evt);
    }

    private void doWork(OrderEvent evt) { /* 业务 */ }
}
```

> **放回式的代价要说清**：未到期消息会在"业务 topic → 等待区 → 业务 topic"之间**反复横跳**，消息**可能多次重投**（网络/重启），所以同样要**消费幂等**兜底；而且无谓的流转消耗带宽。**相比起来 3.2 的 DB 扫描才是生产首选。**

### 3.5 三种实现对比（选型表）

| 实现 | 延迟精度 | 需要额外组件 | 多实例安全 | 适用 |
|------|:---:|:---:|:---:|------|
| **3.2 DB 扫描** | 秒级（由扫描间隔决定） | DB 表 + `@Scheduled` | 要 `FOR UPDATE SKIP LOCKED` 或分布式锁 | **生产首选**，任意延迟时长、可查询 |
| **3.3 重试阶梯** | 固定档位（1s/2s/4s…） | 无 | 天然安全（topic 本身就是队列） | 快速接入、档位固定的延迟 |
| **3.4 放回式** | 秒级（poll 间隔） | 无 | 天然安全（但重投多） | 不想建表、延迟较短 |
| 3.4 sleep（❌） | — | — | — | **别用** |

> **本章验证**：
> 1. 3.2：调 `delayedMessageService.delay("orders", "k1", "hello", Duration.ofSeconds(5))` → 5 秒后 `orders` 消费端才收到；`scheduled_message` 表状态从 PENDING → SENT。
> 2. 3.3：发一条消息 → `kafka-topics --list` 看到 `orders-retry-1000/2000/4000/8000` 四个重试 topic，消费端约 1/2/4/8 秒各触发一次，最后进 `orders-dlt`。
> 3. 3.4：对比 `Thread.sleep` 版和放回式的消费 lag（`kafka-consumer-groups --describe`）：sleep 版 lag 一路涨，放回式 lag 平稳。

---

## 第 4 章：请求-响应模式（同步转异步）

### 4.1 场景：调用方要"等结果"

前面全是**单向消息**（发了不管回不回）。但有些业务**必须等对方的结果**，比如"下单接口要等到库存扣减成功才返回"。两个选择：

- **同步 HTTP**：简单，但调用方被阻塞、下游慢会拖垮上游、不易削峰。
- **RPC over Kafka（请求-响应）**：把"发请求"和"收响应"拆开，调用方用 `CompletableFuture` **异步地等**，超时自己控制。这就是"同步转异步"——**代码看起来还是"等结果"，底层是异步消息**。

### 4.2 `CorrelationId` + `reply topic` 约定

Kafka 是单向的，要把"一对多/多对一"变成"一问一答"，靠**两个约定**：

1. **`CorrelationId`**：请求方生成一个唯一 ID 放进消息头；响应方原样带回来。响应方靠它知道"回给谁"，请求方靠它知道"这条回复是我哪次请求的"。
2. **`reply topic`**：请求方在消息头里告诉响应方"回信投到哪个 topic"。响应方把结果投到那个 topic，请求方在 reply topic 上等。

```
请求方                               响应方
  │  ① ProducerRecord                    │
  │  headers:                            │
  │    CorrelationId = uuid              │
  │    ReplyTopic    = "rpc-replies"     │
  ├──────────────────────────────────────►  @KafkaListener("stock-check")
  │                                      │  ② 处理请求
  │                                      │  ③ 回信到 ReplyTopic，带同一个 CorrelationId
  │  ④ @KafkaListener("rpc-replies")     │
  │  用 CorrelationId 匹配到 pending      │
  │  future → complete(结果)             │
  │                                      │
  │  ⑤ 超时（5s 没回）→ future 超时失败   │
```

Spring Kafka 已经把这两个约定封装成了 `KafkaHeaders.CORRELATION_ID` 和 `KafkaHeaders.REPLY_TOPIC`。下面先手写一遍（把机制讲透），再用官方 `ReplyingKafkaTemplate`。

### 4.3 手写版：`CompletableFuture` + correlationId 匹配 + 超时

**请求方客户端**：

```java
package com.example.rpc;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Component
public class RpcClient {

    // ▼ correlationId → 还没完成(未收到回复)的 future
    private final ConcurrentMap<String, CompletableFuture<String>> pending =
            new ConcurrentHashMap<>();

    private final KafkaTemplate<String, String> kafkaTemplate;

    public RpcClient(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** 发请求并"异步地等"结果。调用方拿到 future 后可以 .get() 阻塞等，也可以回调 */
    public CompletableFuture<String> request(String requestTopic, String payload) {
        String correlationId = UUID.randomUUID().toString();   // ① 本次请求的唯一 ID
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(correlationId, future);

        // ② 组装 ProducerRecord，把 CorrelationId 和 ReplyTopic 写进消息头
        ProducerRecord<String, String> record = new ProducerRecord<>(requestTopic, payload);
        record.headers().add(KafkaHeaders.CORRELATION_ID,
                correlationId.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.REPLY_TOPIC,
                "rpc-replies".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);

        // ③ 超时：5 秒没回就失败；无论成败都从 pending 里清掉，防止内存泄漏
        return future.orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((result, ex) -> pending.remove(correlationId));
    }

    // ▼ ④ 消费响应：按 correlationId 找到对应的 future 并 complete
    @KafkaListener(topics = "rpc-replies", groupId = "rpc-client-group")
    public void onReply(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.CORRELATION_ID);
        if (header == null) {
            return;   // 没有 correlationId，无法匹配，丢弃
        }
        String correlationId = new String(header.value(), StandardCharsets.UTF_8);
        CompletableFuture<String> future = pending.get(correlationId);
        if (future == null) {
            // 可能已经超时被清掉了 → 迟到的回复，静默忽略
            System.out.println("[RpcClient] 迟到的回复（已超时？）：" + correlationId);
            return;
        }
        future.complete(record.value());
    }
}
```

**响应方**（手写 reply：不用 `@SendTo`，自己把结果发回 reply topic）：

```java
package com.example.rpc;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class StockServer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StockService stockService;

    public StockServer(KafkaTemplate<String, String> kafkaTemplate,
                       StockService stockService) {
        this.kafkaTemplate = kafkaTemplate;
        this.stockService = stockService;
    }

    // ▼ 请求方约定好的请求 topic
    @KafkaListener(topics = "stock-check", groupId = "inventory-group")
    public void onRequest(String requestJson,
                          @Header(KafkaHeaders.CORRELATION_ID) String correlationId,
                          @Header(KafkaHeaders.REPLY_TOPIC) String replyTopic) {
        String result = stockService.check(requestJson);   // 业务：查库存

        // ▼ 手写回信：投到 replyTopic，必须原样带回 correlationId（匹配的关键）
        ProducerRecord<String, String> reply = new ProducerRecord<>(replyTopic, result);
        reply.headers().add(KafkaHeaders.CORRELATION_ID,
                correlationId.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(reply);
    }
}
```

**调用方"等结果"的两种姿势**：

```java
// 姿势一：阻塞等（内部是消息，但调用方代码像同步一样）
String result = rpcClient.request("stock-check", requestJson)
        .get(5, TimeUnit.SECONDS);          // 超时会抛 TimeoutException

// 姿势二：异步回调（不阻塞当前线程，回复到了再处理）
rpcClient.request("stock-check", requestJson)
        .thenAccept(result -> System.out.println("库存结果：" + result))
        .exceptionally(ex -> {
            System.out.println("RPC 失败或超时：" + ex.getMessage());
            return null;
        });
```

### 4.4 Spring 原生版：`ReplyingKafkaTemplate.sendAndReceive` + `@SendTo`

手写版讲透了机制，但 Spring Kafka 官方已经把"发请求 + 匹配响应 + 组装回复"封装好了。**`ReplyingKafkaTemplate` 是 `KafkaTemplate` 的子类，多了 `sendAndReceive()`**，自带一个回复容器帮你消费 reply topic。

**① 配置回复容器 + Bean**：

```java
package com.example.rpc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;

@Configuration
public class RpcConfig {

    // ▼ 官方请求-响应模板：给它一个"专门消费回复"的容器
    @Bean
    public ReplyingKafkaTemplate<String, String, String> replyingKafkaTemplate(
            ProducerFactory<String, String> producerFactory,
            ConcurrentKafkaListenerContainerFactory<String, String> containerFactory) {

        ConcurrentMessageListenerContainer<String, String> replyContainer =
                containerFactory.createContainer("rpc-replies");   // 回复 topic
        replyContainer.getContainerProperties().setGroupId("rpc-replies-group");
        return new ReplyingKafkaTemplate<>(producerFactory, replyContainer);
    }
}
```

**② 客户端：`sendAndReceive` 一步到位**（correlationId、reply topic、匹配全自动）：

```java
package com.example.rpc;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.requestreply.RequestReplyFuture;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class RpcClient {

    private final ReplyingKafkaTemplate<String, String, String> replyingKafkaTemplate;

    public RpcClient(ReplyingKafkaTemplate<String, String, String> replyingKafkaTemplate) {
        this.replyingKafkaTemplate = replyingKafkaTemplate;
    }

    public String request(String requestTopic, String payload) throws Exception {
        // ▼ 自动设置 REPLY_TOPIC 头（回复容器的 topic），自动生成 correlationId
        ProducerRecord<String, String> record = new ProducerRecord<>(requestTopic, payload);

        // sendAndReceive：发请求 + 等回复（超时 5s）
        RequestReplyFuture<String, String> future =
                replyingKafkaTemplate.sendAndReceive(record, Duration.ofSeconds(5));

        // ▼ 想确认"发出去没"：getSendFuture()
        // future.getSendFuture().get(5, TimeUnit.SECONDS);

        ConsumerRecord<String, String> reply = future.get(5, TimeUnit.SECONDS);   // 阻塞等回复
        return reply.value();
    }
}
```

**③ 响应方：`@SendTo` 注解，方法返回值自动回信**：

```java
package com.example.rpc;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;

@Component
public class StockServer {

    private final StockService stockService;

    public StockServer(StockService stockService) {
        this.stockService = stockService;
    }

    // ▼ @SendTo（无参数）：方法返回值自动发到请求头 REPLY_TOPIC 指定的 topic，
    //   Spring Kafka 会自动把 correlationId 头复制到回复里——不用你手动拼
    @KafkaListener(topics = "stock-check", groupId = "inventory-group")
    @SendTo
    public String onRequest(String requestJson,
                            @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
        return stockService.check(requestJson);   // 返回值 = 回信内容
    }
}
```

> **对比**：4.3 手写版和 4.4 官方版**底层机制完全一样**（correlationId + reply topic），官方版把"生成 correlationId、写 REPLY_TOPIC 头、消费回复并匹配、复制头回信"都替你做了。**推荐官方版**；手写版的价值是**让你出问题时能看穿它**。

### 4.5 超时、失败、乱序回复的处理

| 问题 | 现象 | 处理 |
|------|------|------|
| **超时** | 响应方挂了 / 处理超过 5s | `future.get(5, TimeUnit.SECONDS)` 抛 `TimeoutException`；4.3 用 `orTimeout` 兜底 + 从 `pending` 清除。调用方 catch 后走重试/降级 |
| **迟到回复** | 超时后才回来 | 4.3 的 `pending` 里已没有该 correlationId → 静默忽略（打印日志）。官方版 `ReplyingKafkaTemplate` 对迟到回复同样丢弃 |
| **发送失败** | `sendAndReceive` 的消息根本没发出去 | 官方版 `future.getSendFuture().get(...)` 能拿到发送结果，失败抛 `KafkaException`；不发出去就永远等不到回复，要靠超时兜底 |
| **乱序回复** | 多个请求并发，回复顺序和请求顺序不同 | **correlationId 匹配天然解决**——每个回复都回到自己的 future，顺序无所谓（这是消息版 RPC 优于"按顺序等"的地方） |
| **响应方异常** | 处理请求抛异常 | 响应方 catch 后把"错误信息"作为 reply 发回（约定好错误 JSON），或投到死信；别让异常把回信吞掉导致请求方干等超时 |

**一个真实权衡**：请求-响应模式把**同步依赖从"调用栈"搬到了"消息流"**，好处是解耦、削峰、上游不阻塞；坏处是**调试变难**（链路拉长）——所以本章的代码**必须配第 5 章的 traceparent 链路追踪**，否则排障靠猜。

> **本章验证**：
> 1. 起 `RpcClient` + `StockServer`，调 `rpcClient.request("stock-check", "{\"productId\":1}")` → 打印库存结果。
> 2. **超时**：让 `StockServer` 处理前 `Thread.sleep(6000)`（故意超过 5s）→ 客户端 `TimeoutException`，控制台打印"迟到的回复"。
> 3. **并发乱序**：一次发 10 个不同 productId 的请求 → 每个请求拿到自己对应的结果（correlationId 匹配正确）。

---

## 第 5 章：可观测性代码埋点

### 5.1 开箱即用的 `kafka_*` 指标（零代码）

`spring-boot-starter-kafka` + Actuator 就自动暴露 Kafka 客户端指标（[02 第 2.7 章](./02-Kafka进阶实战.md)、[01 第 9.5 章](./01-Kafka消息队列从入门到架构师.md)）。加依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
```

**最该盯的指标**（`/actuator/prometheus` 或 `GET /actuator/metrics/{name}`）：
- `kafka.producer.record.send.total` / `kafka.producer.record.send.rate`：发送量/发送速率。
- `kafka.producer.request.latency.avg`：生产端请求延迟。
- `kafka.consumer.fetch.manager.records.lag`：**消费 lag（落后量）——最重要，持续上涨 = 消费者扛不住了**。
- `kafka.consumer.fetch.manager.records.consumed.rate`：消费速率。
- `spring.kafka.listener.time` / `spring.kafka.listener.failure`：监听器处理耗时 / 失败数（Spring Kafka 的 Observation 指标）。
- `spring.kafka.template.time` / `spring.kafka.template.failure`：发送耗时 / 失败数。

### 5.2 手动埋 Micrometer 计数器/计时器

自动指标看"机器层"，**业务层指标（成功处理多少、失败多少、耗时分布）要自己埋**。注入 `MeterRegistry`：

```java
package com.example.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MessageMetrics {

    private final Counter processed;
    private final Counter failed;
    private final Timer processTimer;

    public MessageMetrics(MeterRegistry meterRegistry) {
        // ▼ 计数器：成功/失败各一个，tag 区分服务
        this.processed = Counter.builder("app.message.processed")
                .description("成功处理的消息数")
                .tag("service", "order-service")
                .register(meterRegistry);
        this.failed = Counter.builder("app.message.failed")
                .description("处理失败的消息数")
                .tag("service", "order-service")
                .register(meterRegistry);
        // ▼ 计时器：耗时分布（publishPercentileHistogram 让 Prometheus 能算 p99）
        this.processTimer = Timer.builder("app.message.process.time")
                .description("单条消息处理耗时")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void recordSuccess() { processed.increment(); }

    public void recordFailure() { failed.increment(); }

    public void recordTime(Duration duration) { processTimer.record(duration); }
}
```

在消费者里埋点（注意 `finally` 里记耗时，失败重抛给重试/死信处理）：

```java
package com.example.observability;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class OrderConsumer {

    private final MessageMetrics metrics;

    public OrderConsumer(MessageMetrics metrics) {
        this.metrics = metrics;
    }

    @KafkaListener(topics = "orders", groupId = "obs-group")
    public void onOrder(OrderEvent evt) {
        Instant start = Instant.now();
        try {
            handle(evt);
            metrics.recordSuccess();
        } catch (Exception e) {
            metrics.recordFailure();
            throw e;   // 抛出去交给 @RetryableTopic 重试 / DLT（见 1.6）
        } finally {
            metrics.recordTime(Duration.between(start, Instant.now()));
        }
    }

    private void handle(OrderEvent evt) { /* 业务 */ }
}
```

> **告警建议**（不写代码但必须配）：`app.message.failed` 增长、`kafka.consumer.*.lag` 持续上涨、`spring.kafka.listener.failure` 出现，都要拉告警。消息系统不可观测 = 线上黑盒。

### 5.3 分布式追踪：`traceparent` 头跨服务串链路

**目标**：订单服务发的消息，到库存服务消费、再发出新消息，到支付服务……整条链共享**同一个 traceId**，在 Zipkin/Tempo 里能串成一条链路。**原理就是 W3C 的 `traceparent` 头**（`00-<traceId>-<spanId>-01`）：生产端把当前 trace 上下文写进消息头，消费端读出来续上。

**加依赖**（[01 第 9.5 章](./01-Kafka消息队列从入门到架构师.md)、[03 第 8.2 章](./03-事件驱动微服务端到端实战.md) 已经铺垫）：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

```yaml
management:
  tracing:
    sampling:
      probability: 1.0      # 生产 0.1~1.0；demo 直接全采样
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans   # Zipkin 地址
```

**先看自动版（0 行埋点）**：classpath 有 Micrometer Tracing 时，Spring Kafka 的 Observation **自动**给 `KafkaTemplate` 发送注入 `traceparent`、给 `@KafkaListener` 消费时提取并续 span（[03 第 8.2 章](./03-事件驱动微服务端到端实战.md)）。**默认就有，别重复埋。**

**再看手动版（把机制讲透，或你需要完全掌控时）**——用 `io.micrometer.tracing.Tracer`：

```java
package com.example.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.SpanContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class TracingProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Tracer tracer;

    public TracingProducer(KafkaTemplate<String, String> kafkaTemplate, Tracer tracer) {
        this.kafkaTemplate = kafkaTemplate;
        this.tracer = tracer;
    }

    public void send(String topic, String key, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);

        // ▼ 把"当前链路上下文"注入消息头（默认就是 W3C traceparent）
        Span current = tracer.currentSpan();          // 可能是外部 HTTP 请求带进来的 span
        if (current != null) {
            tracer.inject(current.context(), record.headers(),
                    (Headers headers, String name, String value) ->
                            headers.add(name, value.getBytes(StandardCharsets.UTF_8)));
        }
        kafkaTemplate.send(record);
    }
}
```

```java
package com.example.observability;

import io.micrometer.tracing.Scope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.SpanContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class TracingConsumer {

    private final Tracer tracer;

    public TracingConsumer(Tracer tracer) {
        this.tracer = tracer;
    }

    @KafkaListener(topics = "orders", groupId = "order-group")
    public void onOrder(ConsumerRecord<String, String> record) {
        // ▼ 从消息头还原父 trace 上下文（生产端注入的 traceparent）
        SpanContext parent = tracer.extract(record.headers(),
                (Headers headers, String name) -> {
                    Header h = headers.lastHeader(name);
                    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
                });

        // ▼ 在父上下文下开一个"消费"span，业务代码里的日志/子 span 自动归属同一条 trace
        Span span = tracer.nextSpan(parent).name("kafka-consume orders");
        span.start();
        try (Scope scope = tracer.makeSpanCurrent(span)) {
            handle(record.value());
        } finally {
            span.end();
        }
    }

    private void handle(String payload) {
        // 业务处理；如果这里再用 TracingProducer.send 发新消息，
        // 会读到当前 span → 继续往新消息头注入同一 traceId → 链路串起来
    }
}
```

> **串链路的完整闭环**：HTTP 请求带 `traceparent` 进来 → `TracingProducer.send` 把它写进 Kafka 消息头 → 库存服务 `TracingConsumer` 读出来续上 span → 库存服务再 `send` 时继续往下传 → 支付服务再续。**三个服务、N 条消息，Zipkin 里是一条 trace。**

### 5.4 手写简化版链路（零依赖，MDC + traceparent）

如果你不想上 Micrometer Tracing / Zipkin，只想**让日志带上 traceId、跨服务能对得上**，可以自己维护一份最简上下文：

```java
package com.example.observability;

import org.slf4j.MDC;

import java.util.UUID;

/** 最简 trace 上下文：存当前线程的 traceId，并把它拼成 traceparent 头 */
public final class TraceContext {

    private TraceContext() { }

    public static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : generateTraceId();
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");   // 32 位 hex
    }

    public static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);  // 16 位 hex
    }

    /** 拼 W3C traceparent：00-<traceId>-<spanId>-01 */
    public static String formatTraceParent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    /** 从 traceparent 里取出 traceId（简化解析，够用即可） */
    public static String traceIdFromTraceParent(String traceParent) {
        if (traceParent == null || traceParent.isBlank()) return null;
        String[] parts = traceParent.split("-");
        return parts.length >= 2 ? parts[1] : null;
    }
}
```

**生产端**（写 traceparent 头 + 日志带 traceId）：

```java
package com.example.observability;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SimpleTracingProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public SimpleTracingProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(String topic, String key, String payload) {
        // ▼ 复用当前线程的 traceId（如果本线程本来就是某条链路的一部分）
        String traceId = TraceContext.currentTraceId();
        String spanId = TraceContext.generateSpanId();

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add("traceparent",
                TraceContext.formatTraceParent(traceId, spanId).getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);

        // 让发送日志也带上 traceId，方便 grep
        MDC.put("traceId", traceId);
    }
}
```

**消费端**（读 traceparent → 还原到 MDC → 业务日志自动带 traceId → 再发送时复用）：

```java
package com.example.observability;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SimpleTracingConsumer {

    private final SimpleTracingProducer producer;   // 复用来继续往下传

    public SimpleTracingConsumer(SimpleTracingProducer producer) {
        this.producer = producer;
    }

    @KafkaListener(topics = "orders", groupId = "order-group")
    public void onOrder(ConsumerRecord<String, String> record) {
        // ▼ 从消息头还原父 traceId
        Header tp = record.headers().lastHeader("traceparent");
        String traceId = tp != null
                ? TraceContext.traceIdFromTraceParent(new String(tp.value(), StandardCharsets.UTF_8))
                : TraceContext.generateTraceId();

        MDC.put("traceId", traceId);      // ▼ 之后所有日志自动带 [traceId=...]
        try {
            handle(record.value());
            // ▼ 业务里再发消息 → producer 复用同一个 traceId → 链路串起来
            producer.send("audit", record.key(), "processed:" + record.value());
        } finally {
            MDC.remove("traceId");        // 必须清理，否则线程池复用会串 traceId
        }
    }

    private void handle(String payload) {
        // 日志格式里配 %X{traceId}，就能看到同一条链路的日志
        System.out.println("[traceId=" + MDC.get("traceId") + "] 处理 " + payload);
    }
}
```

> **对比 5.3 与 5.4**：5.3 是"完整方案"（span + Zipkin + 自动 instrumentation），5.4 是"日志够用"的简化版（只传 traceId）。**生产至少要有一种**——没有链路信息，跨服务的消息排障就像在一堆日志里大海捞针。

> **本章验证**：
> 1. 起应用 + Actuator，`curl localhost:8080/actuator/metrics/kafka.consumer.fetch.manager.records.lag` 能看到 lag 指标。
> 2. 发一批消息，`curl localhost:8080/actuator/metrics/app.message.processed` 计数增长。
> 3. 5.3：起 Zipkin（`docker run -p 9411:9411 openzipkin/zipkin`），连发一条跨服务消息，Zipkin UI 里能看到一条完整 trace。
> 4. 5.4：发消息，两个服务的控制台日志 `[traceId=...]` **相同** → 跨服务日志能对上。

---

## 附录：依赖、测试与配置速查

### A.1 依赖清单（`pom.xml`）

```xml
<!-- 本专题主角：Kafka 客户端（BOM 托管版本，Boot 4.1.0） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-kafka</artifactId>
</dependency>

<!-- 可观测：指标 + 追踪（第 5 章） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>

<!-- 幂等表 / 延迟表（第 1、3 章）按需 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Redis setnx 去重（第 1.4 章）按需 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- 测试（第 1 章验证用内嵌 Kafka） -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

### A.2 一个可跑的测试：内嵌 Kafka 验证幂等

用 `@EmbeddedKafka`（spring-kafka-test 提供，测试时起一个内存 broker，自动把 `spring.kafka.bootstrap-servers` 指过去）验证幂等消费：

```java
package com.example.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.util.concurrent.TimeUnit;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = { "orders" })   // ▼ 内嵌 Kafka
class IdempotentConsumerTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private WalletRepository walletRepo;

    @Autowired
    private ProcessedMessageRepository processedRepo;

    @Test
    void sameEventId_shouldProcessOnlyOnce() throws Exception {
        // 同一条 eventId 连发 3 次（模拟 at-least-once 重复投递）
        OrderEvent evt = new OrderEvent("evt-1", "user-1", 100);
        kafkaTemplate.send("orders", evt.eventId(), evt).get(5, TimeUnit.SECONDS);
        kafkaTemplate.send("orders", evt.eventId(), evt).get(5, TimeUnit.SECONDS);
        kafkaTemplate.send("orders", evt.eventId(), evt).get(5, TimeUnit.SECONDS);

        // 等消费完成
        Thread.sleep(2000);

        // 只扣了一次
        Wallet wallet = walletRepo.findById("user-1").orElseThrow();
        assert wallet.getBalance() == 900;          // 1000 - 100（不是 700）
        assert processedRepo.existsById("evt-1");   // 幂等表有记录
    }
}
```

### A.3 配置速查（本章用到的 `spring.kafka.*`）

| 配置 | 值 | 作用 | 章节 |
|------|----|------|------|
| `producer.enable-idempotence` | `true` | 幂等生产者（防重试重复） | 2.2 |
| `producer.transaction-id-prefix` | `eos-${random.uuid}` | 开启 Kafka 事务（配 `transaction-id-prefix` 才会自动装配 `KafkaTransactionManager`） | 2.3 |
| `producer.acks` | `all` | 幂等时强制 all | 2.2 |
| `consumer.isolation-level` | `read_committed` | 只读已提交事务消息（EOS 消费端） | 2.5 |
| `consumer.enable-auto-commit` | `false` | 配合事务容器/手动 ack | 2.6 |
| `listener.ack-mode` | `record` | 每条处理完 ack | 2.6 |
| `management.tracing.sampling.probability` | `1.0` | 全量采样（追踪） | 5.3 |

---

## 下一步

可靠性是"能上生产"的地基，本篇把五段代码落地了：**幂等消费（第 1 章）、EOS 全链路（第 2 章）、延迟消息（第 3 章）、请求-响应（第 4 章）、可观测性（第 5 章）**。继续深入的方向：

- 把第 1 章幂等和第 2 章事务应用到你的订单/支付场景，配合 [04 的 Outbox](./04-生产级进阶-Outbox与Schema与分区调优.md) 做"写库 + 发消息"原子化。
- 延迟消息 3.2 的 DB 扫描 + `FOR UPDATE SKIP LOCKED` 多实例改造，参考 [Redis 分布式锁实战](../Redis专题/02-Redis分布式锁实战.md)。
- 第 4 章请求-响应 + 第 5 章追踪，拼出完整的同步转异步 RPC 服务。
- 想在 35 号文档的管数分离架构里落地 Kafka 可靠性，看 [35 管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 6 章的幂等键与第 10 章的 Kafka 持久总线——把本篇的幂等/事务/EOS 接进去。

## 配套学习资料

- [Kafka 消息队列实战专题 README](./README.md)（本专题学习顺序）
- [01 概念篇：事务、幂等生产者、消费幂等](./01-Kafka消息队列从入门到架构师.md)（第 8.4 / 9.1 / 9.2 章）
- [02 进阶篇：重试与死信、可观测性](./02-Kafka进阶实战.md)（第 2.6 / 2.7 章）
- [03 实战篇：幂等表、链路追踪](./03-事件驱动微服务端到端实战.md)（第 4.3 / 8.2 章）
- [04 生产级篇：Outbox、Schema、分区调优](./04-生产级进阶-Outbox与Schema与分区调优.md)（方向 A）
- [05 全知识点实践项目](./05-全知识点实践项目.md)（阶段 9 事务 + 幂等）
- [35 管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md)（幂等键、Kafka 持久总线）
- [Spring 官方：Spring for Apache Kafka 参考文档](https://docs.spring.io/spring-kafka/reference/)（事务、请求-回复、重试 topic 权威）
- [Apache Kafka 官方：Transactions](https://kafka.apache.org/documentation/#semantics)（EOS 语义权威）
