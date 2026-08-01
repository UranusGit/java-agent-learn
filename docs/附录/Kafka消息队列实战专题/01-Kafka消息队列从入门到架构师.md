# Kafka 消息队列从入门到架构师（直接用 spring-boot-starter-kafka）

> **这份文档是什么**：一份**从零开始、循序渐进、最终达到架构师水平**的 Kafka + Spring Kafka 专题手册。你不需要懂消息队列、不需要懂微服务，只要会 Java 和 Spring Boot 基础，跟着读、跟着抄代码，就能从"它到底是什么"一路学到"企业级为什么直接用 Kafka、它怎么扛住千万级消息、生产环境怎么不出事"。
>
> **它和 35 号文档的关系**：[35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 10 章用了**原生 `spring-boot-starter-kafka`**（手写 `KafkaTemplate` 发、`@KafkaListener` 收）做 chunk 持久总线。那份文档教的是"消息总线"这个**概念**。本文教的是**那套写法的系统化升级版**——把"会用 KafkaTemplate / @KafkaListener"展开成完整的知识体系：topic/分区/offset/消费组、生产者全解、消费者全解、重试死信、客户端调优、幂等事务、可观测。**35 号文档第 10 章的手写 Kafka 就是本文的方式**；学完本文，你会明白手写 Kafka 在生产环境怎么从"能用"变成"生产级"。
>
> **版本前提（重要）**：本文基于 **Spring Boot 4.1.0 + `spring-boot-starter-kafka`**。纯 Kafka **不需要引入 Spring Cloud 依赖**——`spring-kafka` 与 `kafka-clients` 的版本由 Boot 父工程 BOM 统一托管，你只管写业务。如果你还在用 Boot 3.x，用法**几乎完全一致**（见文末版本对照表），本文代码可直接照抄。

---

## 目录

- [第 0 章：先搞清楚它是什么](#第-0-章先搞清楚它是什么)
- [第 1 章：30 分钟跑起来——你的第一个 Kafka 程序](#第-1-章30-分钟跑起来你的第一个-kafka-程序)
- [第 2 章：核心三件套——topic / 分区 / offset（+ 消费组）](#第-2-章核心三件套topic--分区--offset-消费组)
- [第 3 章：编程模型——@KafkaListener + KafkaTemplate](#第-3-章编程模型kafkalistener-kafkatemplate)
- [第 4 章：生产者全解——KafkaTemplate](#第-4-章生产者全解kafkatemplate)
- [第 5 章：消费者全解——@KafkaListener、消费组、重试、死信](#第-5-章消费者全解kafkalistener消费组重试死信)
- [第 6 章：Kafka 客户端详解——producer/consumer 配置与顺序](#第-6-章kafka-客户端详解producerconsumer-配置与顺序)
- [第 7 章：Kafka vs RabbitMQ——消息中间件选型](#第-7-章kafka-vs-rabbitmq消息中间件选型)
- [第 8 章：进阶——批处理、多 topic、手动 ack、事务](#第-8-章进阶批处理多-topic手动-ack事务)
- [第 9 章：生产级——幂等、分区与 key、可观测、事务](#第-9-章生产级幂等分区与-key可观测事务)
- [第 10 章：架构师视角——该不该直接写 Kafka、什么时候上 Stream](#第-10-章架构师视角该不该直接写-kafka什么时候上-stream)
- [附录 A：API 签名校验表](#附录-aapi-签名校验表)
- [附录 B：完整可跑项目](#附录-b完整可跑项目)
- [附录 C：版本对照与踩坑手册](#附录-c版本对照与踩坑手册)

---

## 第 0 章：先搞清楚它是什么

### 0.1 一个最朴素的问题

假设你要做一个系统：**订单服务下单后，要通知库存服务扣减库存、通知通知服务发短信、通知计费服务记账**。最直觉的写法：

```java
// 订单服务里
orderService.create(order);
inventoryService.deduct(order);   // 同步调用
notifyService.sendSms(order);      // 同步调用
billingService.charge(order);      // 同步调用
```

这有三个致命问题：

1. **强耦合**：库存服务挂了，下单也跟着失败。一个下游拖垮整个下单链路。
2. **无法扩展消费者**：明天市场部说"我也要收到订单事件做数据分析"——你得改订单服务代码，加一行调用。
3. **同步阻塞**：三个下游串行调用，下单接口要等它们全部完成才返回，慢。

**消息驱动（Event-Driven）**是业界标准答案：订单服务下单后，**只做一件事——往消息系统扔一条"订单已创建"的消息**，然后立即返回。库存、通知、计费、市场部分别**订阅**这条消息，各自处理，互不影响。

```
订单服务 ──发消息──> [ 消息系统(Kafka) ] ──┬──> 库存服务
                                          ├──> 通知服务
                                          ├──> 计费服务
                                          └──> 市场分析服务
```

订单服务**根本不知道**下游有几个、是谁——它只管发消息。这就叫**发布-订阅（Pub/Sub）**。

### 0.2 为什么用 Kafka 做消息队列，而不是别的

消息中间件很多：RabbitMQ、RocketMQ、Pulsar、ActiveMQ……为什么企业级（尤其是互联网大流量场景）**首选 Kafka**？

| 维度 | Kafka | RabbitMQ | RocketMQ / Pulsar |
|------|-------|----------|-------------------|
| **吞吐量** | **极高**（百万级 msg/s，顺序写磁盘 + 零拷贝） | 中（万级） | 高 |
| **持久化/堆积** | 强（消息落盘，可长期堆积几十 TB 不丢） | 中（积压会拖垮） | 强 |
| **分区并行** | 一等公民（topic 分 partition，天然并行） | 弱（queue 并行有限） | 强 |
| **消费语义** | 消费组 + offset，at-least-once / exactly-once | 消费确认 | 消费组 |
| **生态** | 最广（Kafka Streams / Connect / Flink / Spark 全支持） | 通用 AMQP | 各有绑定 |
| **典型场景** | 日志、埋点、削峰填谷、事件流、大数据管道 | 业务消息、RPC 解耦、任务队列 | 金融/阿里系、云原生流 |

> **一句话**：RabbitMQ 是"业务消息的瑞士军刀"，Kafka 是"海量数据的搬运河"。当你要处理**千万级消息**、要**削峰填谷**（秒杀瞬间 10 倍流量全打到 MQ，下游慢慢消费）、要**长期留存重放**（流式计算、数据管道），Kafka 是默认答案。**这也正是 35 号文档选 Kafka 做 chunk 持久总线的原因**——chunk 会高频产出大量消息，需要一个扛得住的队列。

### 0.3 那为什么用 Spring Kafka？直接用 kafka-clients 不行吗？

`kafka-clients` 是 Apache 的官方 Java 客户端，**当然可以直接用**。但它只给了你最底层的 `Producer` / `Consumer`，剩下的全要你自己写：

```java
// 原生 kafka-clients 写一个消费者——全手写，别在生产这么干
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("group.id", "log-group");
props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
    consumer.subscribe(List.of("hello-topic"));
    while (true) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
        for (ConsumerRecord<String, String> record : records) {
            System.out.println("收到消息：" + record.value());
        }
        consumer.commitSync();   // offset 提交也要自己管
    }
}
```

问题一目了然：**连接管理、poll 循环、offset 提交、重试、线程模型、序列化、优雅关闭，全都要你手工写**。而 `spring-boot-starter-kafka`（Spring Kafka）把这些**用 Spring 的方式全部接管**：

- **`KafkaTemplate`**：帮你管理 `Producer` 的创建、懒连接、序列化、回调、事务、可观测——你只需 `template.send(topic, value)`。
- **`@KafkaListener`**：帮你管理 `Consumer` 的订阅、消费组、并发、offset 提交、重试、死信——你只需写一个普通方法。
- **配置收敛**：连接地址、序列化器、各类调优参数，全部放进 `application.yaml` 的 `spring.kafka.*`，不用手写一行 `Properties`。

**对比"再加一层抽象"**：Spring Cloud Stream 是在 Kafka 之上再抽象一层"业务函数"（换中间件不改代码）；Spring Kafka **不是业务抽象**——它只是**用 Spring 的方式管理 Kafka 客户端**，你操作的概念依然是 Kafka 自己的（topic、分区、offset、消费组）。**所以用 Spring Kafka 几乎没有"抽象损耗"，性能、可控性、调试直观度都和直接用客户端一样。**这就是企业级 Kafka-only 项目直接选 `spring-boot-starter-kafka` 的原因。

### 0.4 它在整个 Spring 生态的位置

```
你的业务代码（@KafkaListener 方法 / KafkaTemplate 调用）
        ↓
Spring Kafka（spring-boot-starter-kafka）← 本文主角：用 Spring 管理 Kafka 客户端
        ↓
kafka-clients（Apache 官方客户端：Producer / Consumer / Admin）← 真正的网络协议层
        ↓
Kafka Broker（真正的分布式消息集群）
```

**记住这张图**：你写业务，Spring Kafka 管客户端生命周期，kafka-clients 干实事，broker 存数据。架构师必须能在脑子里画出这张分层——出问题时才知道去哪层排查。

### 0.5 适合谁、不适合谁

| 适合 | 不适合 |
|------|--------|
| 微服务之间的事件通信 / 削峰填谷 | 单体内部的方法调用（杀鸡用牛刀） |
| 需要解耦多个消费者（发布订阅） | 强同步、要立即拿结果的请求-响应（用 Feign/HTTP/gRPC） |
| 海量消息、日志埋点、数据管道 | 消息量极小（一天几十条）的简单通知（定时任务即可） |
| 需要消息留存重放（流式计算） | 超低延迟（亚毫秒）场景 |
| 单一消息中间件就是 Kafka（**绝大多数企业**） | 必须多中间件桥接且要统一编程模型（才考虑 Stream） |

---

## 第 1 章：30 分钟跑起来——你的第一个 Kafka 程序

> **目标**：用最快的方式跑起来一个"发一条消息、收到并打印"的程序，建立直觉。细节后面慢慢讲。

### 1.1 建项目

用 [Spring Initializr](https://start.spring.io/) 建一个项目，选：
- **Spring Boot 4.1.x**（本文统一用 `spring-boot-starter-parent` **4.1.0**）
- 依赖：**Spring for Apache Kafka**（就是 `spring-boot-starter-kafka`）+ **Spring Web**（第 4 章发消息要用）

手写 `pom.xml` 的关键部分（父工程 Boot 4.1.0，**不需要 Spring Cloud BOM**）：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
</parent>

<dependencies>
    <!-- ▼ 核心依赖：Spring Kafka（kafka-clients 由 Boot BOM 托管版本，别手写版本号） -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>
    <!-- ▼ 发送消息要用 HTTP 触发，加 Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

> **注意**：`spring-boot-starter-kafka` 这个 starter 内部就依赖 `spring-kafka` + `spring-kafka-test`（测试用），Boot 4.1.0 的 starter 坐标是 `org.springframework.boot:spring-boot-starter-kafka`。两个写法都行：**starter 更省事**（顺带带来测试依赖），直接引 `spring-kafka` 更轻。本文示例用 `spring-boot-starter-kafka`。`kafka-clients` 版本由 Boot BOM 统一托管，**不要**自己写版本号（写错了反而会冲突）。

### 1.2 写你的第一个 @KafkaListener（收消息）

新建主类 + 一个监听方法：

```java
package com.example.kafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;

@SpringBootApplication
public class KafkaDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaDemoApplication.class, args);
    }

    // ▼ 这就是一个"消费者"：订阅 hello-topic，收到消息就打印
    @KafkaListener(topics = "hello-topic", groupId = "log-group")
    public void logMessage(String message) {
        System.out.println("收到消息：" + message);
    }
}
```

### 1.3 配置：告诉它连谁、怎么反序列化

`application.yaml`：

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092   # ▼ Kafka 地址
    consumer:
      group-id: log-group                # ▼ 消费组（方法上也写了，二选一；此处是全局默认）
      auto-offset-reset: earliest         # ▼ 新消费组从最早开始读（见第 2 章 offset）
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

**先别急着跑——记住 Kafka 世界的命名规则**（第 2 章细讲）：
1. **topic 名**（`hello-topic`）：消息发到哪个主题，就是哪条"消息管道"。一个 Kafka 集群有无数 topic，靠名字区分。
2. **消费组名**（`log-group`）：`@KafkaListener` 上的 `groupId` 或配置里的 `spring.kafka.consumer.group-id`。**同一消费组内的实例分摊消息，不同消费组各自收到全量**（第 2 章细讲）。

### 1.4 起 Kafka + 跑起来

```bash
# 起 Kafka（KRaft 模式，不需要 Zookeeper）
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9092 -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
  apache/kafka:3.9.0

# 跑你的应用
./mvnw spring-boot:run
```

> **topic 从哪来？** Kafka 默认 `auto.create.topics.enable=true`，客户端首次往一个不存在的 topic 发消息时 broker 会自动创建它（生产环境建议预建，见第 2 章 `NewTopic`）。

### 1.5 发一条消息试试

用 Kafka 自带的命令行工具往 `hello-topic` 发一条：

```bash
docker exec -it kafka kafka-console-producer --bootstrap-server localhost:9092 --topic hello-topic
# 然后输入：你好 Kafka
# 按 Ctrl+D 退出
```

回到你的应用控制台，会看到：

```
收到消息：你好 Kafka
```

**恭喜——你的第一个 Spring Kafka 程序跑起来了。** 你写了一个普通的 Java 方法，加了一个注解，框架就把它接到了 Kafka 上。

### 1.6 本章小结

记住这三件事，后面的所有内容都是它们的展开：

1. **收消息 = 一个 `@KafkaListener(topics = "...", groupId = "...")` 方法**。
2. **连 Kafka = 配置 `spring.kafka.bootstrap-servers`**，序列化/反序列化用 `spring.kafka.consumer.*` / `spring.kafka.producer.*`。
3. **命名规则 = topic 名 + 消费组**（不是 Stream 的 `<函数名>-in-0`）。

> **本章验证**：发一条消息，应用控制台能打印 `收到消息：...`。把第 1.5 节命令换成 `kafka-console-consumer --topic hello-topic --from-beginning`，能看到消息被持久化了（这就是 Kafka 的"留存"，RabbitMQ 消费即删、Kafka 默认保留 7 天）。

---

## 第 2 章：核心三件套——topic / 分区 / offset（+ 消费组）

这四件事贯穿全文，是 Kafka 的**根本骨架**。Spring Kafka 的一切 API 都是它们的皮，必须彻底搞懂。

### 2.1 topic（主题）——最具体的概念

**topic 就是一条逻辑上的"消息管道"**。你的业务给管道起个名字（如 `orders`），生产者往里写，消费者从里读。

```
Kafka Cluster
   ├── topic: orders     （订单事件）
   ├── topic: payments   （支付事件）
   └── topic: hello-topic（示例）
```

**关键**：topic 之间完全隔离，互不干扰。它只负责"分类"，不负责"顺序保证"（顺序是分区的职责，见 2.2）。

### 2.2 分区（partition）——并行与保序的单位

一个 topic 会被拆成**多个 partition**（分区）。每个 partition 是一个**有序的、可持久化的消息日志**。分区有两大意义：

1. **并行**：一个 topic 的数据可以分散到多个分区，由多个消费者**并行消费**。分区数 = 该 topic 的并行度上限。
2. **保序**：**同一个 key 的消息永远进同一个 partition**，因此 partition 内天然有序。比如"同一订单的 创建→支付→发货"事件，把 `orderId` 作为 key，它们就都在同一个分区里、按顺序被消费。

```
topic: orders（分 3 个分区）
   partition 0: [order-1 创建] → [order-1 支付] → ...   ← 同一 orderId 进同分区，保序
   partition 1: [order-2 创建] → [order-3 创建] → ...
   partition 2: [order-4 创建] → ...
```

**Key 决定进哪个分区**：`key.hashCode() % 分区数`（或用自定义分区器）。**不指定 key 则轮询/粘性分配**，不保序。

### 2.3 offset（偏移量）——消费进度

**offset 是消息在分区里的序号（从 0 开始）**。消费者读到哪了，靠 offset 记录：

```
partition 0: [msg0(offset=0)] [msg1(offset=1)] [msg2(offset=2)] [msg3(offset=3)]
消费进度：↑ 消费者当前 offset=2，下次从 msg2 开始读
```

- Kafka **不会因为你消费过就删消息**（默认保留 7 天），所以必须有 offset 标记"读到哪里"。
- **offset 提交**：消费者处理完一批消息，把 offset 提交给 broker（存到内部 topic `__consumer_offsets`）。重启后从上次提交的 offset 续读。
- **at-least-once**：默认是"处理完再提交"。如果"处理成功但提交前崩溃"，重启后会**重读这条消息**——这就是"至少一次"语义，意味着**消费者必须幂等**（同一条消息处理多次，效果和一次一样，第 9 章讲）。

### 2.4 消费组（consumer group）——最容易误解的概念

**消费组决定"谁收到这条消息"**。先看问题：

> 假设订单消息发给库存服务，但库存服务部署了 **3 个实例**（扩容了）。一条订单消息来了，3 个实例**都会**收到吗？那扣库存不就扣了 3 次？

**消费组解决这个问题**：把 3 个库存实例放进**同一个消费组**，那么**一条消息只会被组内的一个实例消费**（负载均衡）。不同组（如 inventory-group 和 analytics-group）则**各自都能收到**全量消息（发布订阅）。

```
                      orders topic（分 3 个分区）
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
   inventory-group   analytics-group   inventory-group
   (3 个实例分摊 3 个分区，   (收全量做分析)    (实例1/2/3 各分 1 个分区)
    每条只被一个实例处理)
```

**记住**：
- **同组 = 负载均衡**：一条消息组内只一个实例处理；**一个分区同一时刻只被组内一个消费者消费**。
- **不同组 = 各自全量**：每组都收到全量。

Spring Kafka 里用 `@KafkaListener` 的 `groupId`（或配置 `spring.kafka.consumer.group-id`）指定消费组。**生产环境一定要配 group**——不配的话，每次重启会用随机组名，会重复消费历史消息。

### 2.5 四者关系一张图 + 配置速查

```
  topic（消息管道：orders）
     │
     │ 分成 N 个 partition（并行度 / 保序的载体）
     ▼
  partition 0 / 1 / 2 ...（每条消息有唯一 offset）
     │
     │ 消费者按"消费组 + offset"记录读到哪
     ▼
  consumer group（决定负载均衡 vs 发布订阅）
```

**背下来**：消息存进 **topic 的某个分区**，**offset 记录消费进度**，**消费组决定谁消费**。

生产环境常用做法——**用 `NewTopic` bean 预建 topic**（不依赖 broker 自动创建，版本、分区数、副本数全可控）：

```java
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic ordersTopic() {
        return TopicBuilder.name("orders")
                .partitions(6)      // 6 个分区（≈ 并行度上限）
                .replicas(1)        // 1 个副本（生产一般 3）
                .build();
    }
}
```

> **本章验证**：启动应用，用 `kafka-topics --bootstrap-server localhost:9092 --describe --topic orders` 能看到 `orders` 有 6 个分区。把 `@KafkaListener` 的 `groupId` 开两个不同组各跑一个实例，往 `orders` 发消息，观察两组都收到（发布订阅）；两个实例用**同一组**，观察消息被分摊（负载均衡）。

---

## 第 3 章：编程模型——@KafkaListener + KafkaTemplate

这一章不长，但极其重要——它决定了你**用对还是用错** Spring Kafka。

### 3.1 收消息：声明式 `@KafkaListener`（对比手写 poll 循环）

第 1 章的 `@KafkaListener` 是**声明式监听**：你声明"我要收这个 topic 的消息"，框架在后台启动消费者线程、帮你管理一切。对比手写 kafka-clients 的 `while(true) { poll(); ...; commit(); }`，Spring Kafka 替你做了：

- 消费者线程池管理（`concurrency` 控制并发）；
- offset 自动提交（`ack-mode` 控制时机）；
- 异常处理与重试（第 5 章）；
- 自动注册监听（容器管理生命周期，优雅关闭时先停消费再退出）。

```java
import org.springframework.kafka.annotation.KafkaListener;

@KafkaListener(topics = "orders", groupId = "billing-group")
public void onOrder(Order order) {          // ← 收到 Order 对象（自动 JSON 反序列化）
    billingService.charge(order);
}
```

**能接什么参数**（按需选择，框架自动注入）：

```java
@KafkaListener(topics = "orders", groupId = "billing-group")
public void onOrder(
        Order order,                              // 消息体（反序列化后的 payload）
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,     // 消息头：来自哪个 topic
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition, // 来自哪个分区
        @Header(KafkaHeaders.OFFSET) long offset,               // offset 是多少
        Acknowledgment ack) {                                   // 手动 ack（第 8 章）
    // ...
}
```

### 3.2 发消息：程序化 `KafkaTemplate`

`KafkaTemplate` 是对 kafka-clients `Producer` 的封装，**在你想要的时候主动发消息**（HTTP 请求、业务事件、定时任务……）。

```java
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void placeOrder(Order order) {
        // 业务逻辑...
        // ▼ 在事件发生时主动发消息：发到 orders topic，以 orderId 为 key（保序）
        kafkaTemplate.send("orders", order.getId(), order);
    }
}
```

`KafkaTemplate` 由 Spring Boot **自动配置**，直接注入即可用。泛型 `<K, V>` 是"key 类型, value 类型"。第 4 章把它的所有重载讲透。

### 3.3 对比"函数式模型"（Spring Cloud Stream / Function）的局限

如果你之前接触过 Spring Cloud Stream，它把消费端写成 `@Bean Consumer<Order>` 这种**函数式模型**：

```java
// Stream 的写法：业务函数 + spring.cloud.function.definition + bindings 配置
@Bean
public Consumer<Order> onOrder() { return order -> billingService.charge(order); }
```

这种模型的**优点**是"换中间件不改代码"（抽象统一）；但它的**代价**正是直接写 Kafka 时你不需要的：

| 维度 | 函数式模型（Stream） | 直接 `@KafkaListener` + `KafkaTemplate` |
|------|---------------------|------------------------------------------|
| 消息来源 | 隐式绑定（`<函数名>-in-0` 命名约定） | **显式**：`topics` + `groupId`，一眼看懂 |
| Kafka 细节 | 被抽象屏蔽（topic/分区要绕配置） | **直接操作**：`ConsumerRecord`、分区、offset 全在手 |
| 换中间件 | 改配置即可（多中间件场景的卖点） | 要重写监听/发送代码（**但你本来就用 Kafka**） |
| 调试 | 多一层抽象，定位要多跳一层 | **看到的就是 Kafka**，直观 |
| 性能/可控 | 有抽象开销，深水区要靠 binder 专属配置补 | **无抽象损耗**，Kafka 所有特性直接可用 |

> **结论**：函数式模型的"抽象收益"只在"可能换中间件 / 多中间件并存 / 团队统一多套消息模型"时才有价值。**如果你确定消息中间件就是 Kafka（企业绝大多数情况），`@KafkaListener` + `KafkaTemplate` 更直接、更可控、调试更爽**。第 10 章会把这个取舍讲到架构师级别。

### 3.4 两个 API 的分工总结

| API | 方向 | 编程范式 | 用途 |
|-----|------|---------|------|
| `@KafkaListener` | **收**（消费） | 声明式（注解） | 订阅 topic，处理消息 |
| `KafkaTemplate` | **发**（生产） | 程序化（调用） | 主动往 topic 发消息 |
| `NewTopic` + `KafkaAdmin` | **建**（元数据） | 声明式（Bean） | 预建 topic |

> **本章验证**：把第 1 章的例子跑通后，加一个 `@PostMapping` 调 `kafkaTemplate.send(...)` 发消息，用 `@KafkaListener` 收到并打印——这就是"会发会收"的完整闭环（见附录 B 完整项目）。

---

## 第 4 章：生产者全解——KafkaTemplate

"怎么发消息"是消息系统的半边天。这一章把 `KafkaTemplate` 用到极致。

### 4.1 它从哪来、怎么配

Spring Boot 自动配置了一个 `KafkaTemplate<String, String>`，序列化器来自 `spring.kafka.producer.*`。**要发 POJO（JSON）就换 value 序列化器**：

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer   # ▼ 自动把 POJO 转 JSON
```

如果你不想用自动配置，也可以自己声明一个 `KafkaTemplate`：

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", JsonSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }
}
```

> **建议**：能用自动配置（`spring.kafka.producer.*`）就用自动配置，少写样板代码。手动配置只在需要多套 producer（不同序列化/不同集群）时才用。

### 4.2 `send()` 的全部重载（逐个记住）

`KafkaTemplate.send(...)` 返回 `CompletableFuture<SendResult<K, V>>`（异步结果，spring-kafka 3.x 起；老版本是 `ListenableFuture`）：

```java
// 1. 最常用：topic + value
CompletableFuture<SendResult<K, V>> send(String topic, V data);

// 2. 指定 key：同一 key 进同一分区，保序
CompletableFuture<SendResult<K, V>> send(String topic, K key, V data);

// 3. 指定分区：跳过 key 哈希，直接指定进哪个分区（慎用）
CompletableFuture<SendResult<K, V>> send(String topic, Integer partition, K key, V data);

// 4. 指定分区 + 时间戳
CompletableFuture<SendResult<K, V>> send(String topic, Integer partition, Long timestamp, K key, V data);

// 5. 直接传 ProducerRecord（最底层、最灵活，见 4.4）
CompletableFuture<SendResult<K, V>> send(ProducerRecord<K, V> record);

// 6. 传 Spring Message（自动用消息转换器转成 ProducerRecord）
CompletableFuture<SendResult<K, V>> send(Message<?> message);
```

> **`send` 返回的是 CompletableFuture，不是 void**——它可以告诉你这消息**到底发没发出去**（见 4.3）。**记住：`send()` 是异步的**，它把消息放进发送队列就立即返回，真正发到 broker 是在后台线程。

### 4.3 同步发送 vs 异步发送——必须搞懂

```java
// ▼ 方式一：异步（默认、生产推荐）——send 立即返回，用回调拿结果
CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send("orders", order);
future.whenComplete((result, ex) -> {
    if (ex == null) {
        System.out.println("发送成功：partition=" + result.getRecordMetadata().partition()
                + ", offset=" + result.getRecordMetadata().offset());
    } else {
        System.err.println("发送失败：" + ex.getMessage());
    }
});

// ▼ 方式二：同步——调用 get() 阻塞等结果（吞吐低，一般不用在请求链路）
try {
    SendResult<String, Object> result = kafkaTemplate.send("orders", order).get(5, TimeUnit.SECONDS);
    System.out.println("同步发送成功，offset=" + result.getRecordMetadata().offset());
} catch (Exception e) {
    System.err.println("同步发送失败：" + e.getMessage());
}
```

> **为什么"发送成功"≠"已落盘"**：`send` 成功只代表 broker 的 leader **收到了**消息。`acks` 配置（第 6 章）决定"收到"到什么程度算成功：
> - `acks=0`：fire-and-forget，发出即算成功（可能丢）；
> - `acks=1`：leader 写入就算成功（默认推荐）；
> - `acks=all`：所有同步副本写入才算成功（最安全，配幂等必须）。
>
> 回调里的 `SendResult` 能拿到 `RecordMetadata`（分区、offset、时间戳）——**这是做"发送是否真成功"判断的官方途径**。

### 4.4 `ProducerRecord`——最底层的发送载体

`send(ProducerRecord<K, V>)` 是最终的"真身"，其他重载都是它语法糖。`ProducerRecord` 包含：topic、分区、时间戳、key、value、headers。

```java
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

// 手动拼一个 ProducerRecord
ProducerRecord<String, Order> record = new ProducerRecord<>(
        "orders",             // topic
        2,                    // partition（指定分区；传 null 则由 key 决定）
        System.currentTimeMillis(), // timestamp
        order.getId(),        // key
        order                // value
);
record.headers().add(new RecordHeader("source", "order-service".getBytes()));  // 自定义 header

kafkaTemplate.send(record);
```

构造器重载和 `send` 一一对应：`ProducerRecord(topic, value)` / `ProducerRecord(topic, key, value)` / `ProducerRecord(topic, partition, key, value)` / `ProducerRecord(topic, partition, timestamp, key, value)`。

### 4.5 实战建议

1. **key 一定要传**：不传 key 就是随机分区，同一实体的消息可能被打散到不同分区、顺序错乱。传了 key 才能"同一订单的事件有序"（第 9 章细讲）。
2. **value 用 POJO 还是 JSON 字符串**：POJO + `JsonSerializer` 最省事；要跨语言消费（非 Java 消费者）就用 JSON 字符串。
3. **发送结果要看**：生产环境至少加 `whenComplete` 打日志/埋点，别把 `send()` 当 void 用。

> **本章验证**：写个 `@RestController` 用 `kafkaTemplate.send("orders", id, order)` 发消息，回调里打印 `partition` 和 `offset`。同一个 key 连发多条，观察它们进**同一个分区**（`--describe` 或消费端打印 `RECEIVED_PARTITION` 验证）。

---

## 第 5 章：消费者全解——@KafkaListener、消费组、重试、死信

消费者是消息系统的"另一半边天"。这一章把消费端的**全部生产级问题**讲透。

### 5.1 基础监听 + JSON 反序列化

第 1 章收的是字符串。要收 POJO，配 JSON 反序列化器即可：

```yaml
spring:
  kafka:
    consumer:
      group-id: billing-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        # ▼ JsonDeserializer 出于安全默认只信任 java.*，业务 POJO 必须显式放行包名
        spring.json.trusted.packages: com.example
        # ▼ 若消息没有类型头，用这个作为默认目标类型
        spring.json.value.default.type: com.example.kafka.model.Order
```

```java
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BillingConsumer {

    @KafkaListener(topics = "orders", groupId = "billing-group")
    public void chargeOrder(Order order) {          // ▼ 收到的是 Order 对象，不是 JSON 字符串
        billingService.charge(order);
    }
}
```

> **注意**：`JsonSerializer` 发消息时默认会在消息里带一个**类型头**（`spring.json.type`），`JsonDeserializer` 靠它还原类型——所以"POJO 发、POJO 收"通常不用配 `default.type`。跨语言/没类型头时才需要 `spring.json.value.default.type`。

### 5.2 消费组（Consumer Group）——回顾第 2 章

配置 `groupId` 的两种方式（等价）：

```yaml
# 方式一：全局默认（配置所有 @KafkaListener 的默认组）
spring:
  kafka:
    consumer:
      group-id: billing-group
```

```java
// 方式二：注解上指定（覆盖全局默认，更灵活）
@KafkaListener(topics = "orders", groupId = "billing-group")
public void chargeOrder(Order order) { ... }
```

**同组内多个实例**会自动分摊分区（每个分区同一时刻只被组内一个消费者消费），这就是"水平扩容"。

```yaml
# 扩并发：一个实例里开多个消费者线程（上限是分区数）
spring:
  kafka:
    listener:
      concurrency: 3      # ▼ 这个监听容器开 3 条消费线程（3 个分区并行）
```

> **消费组 + 分区数 的关系**：组内消费者数 **>** 分区数时，多余的消费者闲置（分不到分区）；分区数 **>** 消费者数时，才有并行度提升空间。所以扩容消费者实例的**上限是分区数**——topic 设计时就该想好分区数（第 9 章）。

### 5.3 重试（Retry）——消息处理失败怎么办

消费者处理消息时可能抛异常（比如 DB 暂时连不上）。Spring Kafka 用 `DefaultErrorHandler` 提供**自动重试**：

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        // FixedBackOff(interval, maxAttempts)：每 1000ms 重试一次，最多总共尝试 3 次（含首次）
        return new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
    }
}
```

**发生了什么**：消息处理失败 → 等 1s 重试 → 又失败 → 等 1s 再重试 → 3 次用完 → 抛给兜底逻辑（死信，见 5.4）。

> **关键**：只要声明了 `CommonErrorHandler` 这个 bean，Spring Boot 自动配置的监听容器工厂就会自动接上它，**不用再手动改容器工厂**。想换指数退避就用 `ExponentialBackOff`：

```java
import org.springframework.util.backoff.ExponentialBackOff;

ExponentialBackOff backOff = new ExponentialBackOff();
backOff.setInitialInterval(1000L);   // 首次等 1s
backOff.setMultiplier(2.0);          // 之后每次 ×2
backOff.setMaxInterval(10000L);      // 单次最长等 10s
return new DefaultErrorHandler(new ExponentialBackOff(1000L, 2.0, 10000L));
```

### 5.4 死信（DLT）——重试用尽后的兜底

重试 3 次还失败的消息怎么办？直接丢掉太危险（订单扣款失败就丢了，钱就没了）。**死信机制**把这种"处理不了的消息"发到一个**专门的死信 topic（DLT, Dead Letter Topic）**，留待人工介入或后续修复。

```java
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

@Bean
public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template) {
    // ▼ 重试 3 次（含首次）仍失败 → 把消息发到死信 topic
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition(record.topic() + "-dlq", record.partition()));

    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
}
```

**发生了什么**：重试 3 次仍失败 → 消息（连同原始异常信息）被发到 `orders-dlq` 这个 topic。默认 DLT 名是 **`<原topic>.DLT`**；上面自定义成了 `<原topic>-dlq`。你可以单独起一个消费者处理它（发告警、记库、人工修复）。

再补一个消费者看死信长什么样：

```java
@KafkaListener(topics = "orders-dlq", groupId = "dlq-monitor")
public void onDlq(ConsumerRecord<String, String> record) {
    // ▼ 死信消息的 header 里带着原始异常信息，可提取做告警
    System.err.println("死信：topic=" + record.topic()
            + ", value=" + record.value());
}
```

> **为什么要把异常信息一起发过去？** `DeadLetterPublishingRecoverer` 会把原始异常写到消息头（`KafkaHeaders.DLT_EXCEPTION_*`），监控端可以提取异常类型和堆栈做根因分析。

### 5.5 `ErrorHandlingDeserializer`——反序列化失败也要兜底

第 5.4 的死信兜底的是**业务处理异常**。还有一种更隐蔽的失败：**反序列化异常**（比如消息是坏 JSON、类型不对）。如果 value 反序列化失败，消息连 `Order` 都还原不出来，默认会让消费者线程卡死/反复报错。

Spring Kafka 的 `ErrorHandlingDeserializer` 专门兜底这个：**反序列化失败时，把原始字节和异常装进消息头，而不是直接抛死**。配置：

```yaml
spring:
  kafka:
    consumer:
      key-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        # ▼ 指定真正的反序列化器（由 ErrorHandlingDeserializer 包一层）
        spring.deserializer.key.delegate.class: org.apache.kafka.common.serialization.StringDeserializer
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: com.example
        spring.json.value.default.type: com.example.kafka.model.Order
```

配了它之后，反序列化失败的消息会带着异常信息继续走到 `DefaultErrorHandler`——于是"重试 + 死信"对反序列化失败也生效，坏消息进 DLT，不卡线程。

> **生产铁律**：`JsonDeserializer` **必须**配 `ErrorHandlingDeserializer` 包一层，否则一条坏消息就可能让消费者线程反复报错、甚至影响同一分区的后续消息。

### 5.6 本章小结

1. **收消息**：`@KafkaListener(topics, groupId)` 方法，value 反序列化靠 `spring.kafka.consumer.value-deserializer`。
2. **消费组**：同组分摊、异组全量；并发上限 = 分区数。
3. **重试**：声明 `DefaultErrorHandler` bean（配 `FixedBackOff` / `ExponentialBackOff`）。
4. **死信**：`DeadLetterPublishingRecoverer` 把重试耗尽的消息发到 DLT。
5. **反序列化兜底**：`ErrorHandlingDeserializer` 包一层真反序列化器。

> **本章验证**：往 `orders` 发一条 value 是 `{"id":"bad-json"`（故意坏 JSON），观察它不卡线程、3 次重试后进 `orders-dlq`；用 `kafka-console-consumer --topic orders-dlq --from-beginning` 能看到这条死信。

---

## 第 6 章：Kafka 客户端详解——producer/consumer 配置与顺序

前面讲的是"怎么用"。这一章落到 `kafka-clients` 的**调优参数**，让你从"会用"到"会调"。

### 6.1 producer 关键配置（`spring.kafka.producer.*`）

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      # ▼ acks：落盘确认级别
      acks: all                 # 0=发出即成功；1=leader 写入（默认）；all=全部同步副本写入（最安全）
      # ▼ 重试：发送失败的自动重试次数
      retries: 3                # 网络抖动/leader 切换时自动重发
      # ▼ 幂等生产者：防止重试导致消息重复
      enable-idempotence: true  # Kafka 3.0+ 默认 true；开启后 acks 自动被提升为 all，retries 默认无限
      # ▼ 批处理与延迟（吞吐调优）
      batch-size: 16384         # 攒满 16KB 才发一批（单位字节）
      linger-ms: 5              # 最多等 5ms 凑批（调大→吞吐↑延迟↑）
      buffer-memory: 33554432   # 发送缓冲 32MB，满了 send() 阻塞
      compression-type: lz4     # 压缩：lz4/zstd（吞吐↑ CPU↑）
```

> **acks + 幂等是"不丢不重"的底线组合**：
> - `acks=all`：一条消息只有所有同步副本写入才返回成功 → 不丢。
> - `enable.idempotence=true`：给每条消息加序列号，broker 去重 → 重试不重。
> - 二者配合，生产者在正常故障下能保证 **exactly-once 写入**（对单分区而言）。**生产必开**。

### 6.2 consumer 关键配置（`spring.kafka.consumer.*`）

```yaml
spring:
  kafka:
    consumer:
      group-id: billing-group
      # ▼ 新消费组（没有已提交 offset）从哪开始读
      auto-offset-reset: earliest   # earliest=从最早读（重放）；latest=从最新读（只收新消息）；none=无则报错
      # ▼ 是否自动提交 offset（配合 listener.ack-mode 使用）
      enable-auto-commit: false     # 生产建议 false：由监听容器的 ack-mode 精确控制提交时机
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      # ▼ 单次 poll 最多拉多少条（控制每批处理量）
      max-poll-records: 500
      # ▼ poll 间隔（与 max.poll.interval.ms 配合：处理太慢会被判定失联、触发 rebalance）
      properties:
        max.poll.interval.ms: 300000   # 两次 poll 之间最长间隔，超了算"死亡"，踢出组
        spring.json.trusted.packages: com.example
```

> **最大坑：`max.poll.interval.ms` 与处理耗时**。consumer 是"拉取"模型：poll 一批 → 处理 → 再 poll。如果**一批消息处理时间超过 `max.poll.interval.ms`（默认 5 分钟）**，broker 认为消费者死了，触发 **rebalance**（分区转移），严重时消息被重复消费。**对策**：调大 `max.poll.interval.ms`，或调小 `max-poll-records`，或用批处理（第 8 章）把"拉取"和"处理"解耦。

### 6.3 `ack-mode`——offset 到底什么时候提交

offset 提交时机由 `spring.kafka.listener.ack-mode` 决定（前提是 `enable-auto-commit=false`）：

| ack-mode | 提交时机 | 适用 |
|----------|---------|------|
| `BATCH`（默认） | 一批 poll 的全部消息处理完才提交 | 追求吞吐、能接受"批中一条失败整批重读" |
| `RECORD` | 每条消息处理完立即提交 | 每条消息独立、想减少重复 |
| `TIME` / `COUNT` | 每过一段时间 / 每 N 条提交一次 | 折中 |
| `MANUAL` | 代码里调 `ack.acknowledge()` | 精确控制（第 8 章） |
| `MANUAL_IMMEDIATE` | 代码里调一次立即提交一次 | 手动 ack 推荐 |

```yaml
spring:
  kafka:
    listener:
      ack-mode: record     # 每条处理完就提交 offset（重复消费窗口最小）
```

> **at-least-once 的语义窗口就在这**：`BATCH` 下，批中最后一条处理完才提交，前面任何一条失败重读的就是**整批**；`RECORD` 只重读失败那一条。**窗口越小，重复越少，但提交越频繁、性能略降**——这是要权衡的。

### 6.4 分区与顺序——并行和保序的权衡

Kafka 只保证**分区内**有序，不保证 topic 全局有序。想"有序"就要让相关消息进同一分区：

**同一 key → 同一分区 → 顺序保证。**

```java
// ▼ 订单的事件流用 orderId 做 key，保证同一订单的事件顺序
kafkaTemplate.send("orders", order.getId(), order);

// ▼ 不同 key（不同订单）打到不同分区 → 可并行处理
```

**顺序 vs 并行的矛盾**：一个分区同一时刻只能被组内一个消费者消费。所以：
- 分区数 = 并行度上限（分区多 → 并行多）；
- 但"同一 key 的消息"永远在一个分区 → 它们**串行**处理。

**架构决策**：
- 业务要求**强顺序**（同一账户扣款必须按序）→ 用账户 ID 做 key，接受该账户消息串行；
- 业务**不要求顺序**（如埋点、日志）→ 不传 key（粘性分区），全量并行，吞吐最大化；
- 想要"热 key 也有并发" → 只能接受**近似有序**（按时间窗口/桶拆分 key），牺牲严格顺序换并行。

> **本章验证**：同一个 `orderId` 连发 5 条事件，消费端打印 `RECEIVED_PARTITION`，确认 5 条都在同一分区、且消费顺序和发送顺序一致。换不同的 key，确认它们落在不同分区。

---

## 第 7 章：Kafka vs RabbitMQ——消息中间件选型

这一章不是教程，是一段**选型短文**。直接写 Kafka 的你不用 RabbitMQ 代码，但要能回答"为什么选 Kafka 而不是 RabbitMQ"。

### 7.1 一张表看本质区别

| 维度 | Kafka | RabbitMQ |
|------|-------|----------|
| **模型** | topic + 分区 + 消费组 + offset | exchange + queue + binding |
| **吞吐** | 百万级 msg/s（顺序写盘 + 零拷贝） | 万级（单队列性能有限） |
| **消息留存** | 默认保留 7 天，可重放（消费不删） | 消费即删，无重放 |
| **顺序** | 分区内严格有序 | 单队列有序 |
| **消息大小** | 大消息效率低（建议 ≤1MB） | 大消息更灵活 |
| **路由** | 按 key 分区（没有复杂路由） | 强大路由（topic/direct/header/fanout） |
| **运维** | 稍复杂（分区、副本、ISR） | 简单 |
| **典型场景** | 日志、埋点、事件流、削峰填谷、数据管道 | 业务消息、任务队列、需要复杂路由的 RPC 解耦 |

### 7.2 怎么选

- **选 Kafka**：消息量大、要留存重放、要流式计算、要削峰填谷、要强顺序——**互联网大流量后端，绝大多数事件流场景**。
- **选 RabbitMQ**：消息量不大（万级够用）、需要复杂路由（按 header/主题匹配）、团队更熟悉 AMQP、要"消费即删"的普通业务队列。

> **35 号文档为什么选 Kafka**：chunk 持久总线高频产出大量消息，需要**扛得住、可留存、可重放**——这是 Kafka 的主场。如果是低频业务通知，RabbitMQ 也完全够用。

---

## 第 8 章：进阶——批处理、多 topic、手动 ack、事务

基础够用了。这一章是"高级玩家"的内容，也是架构师必须知道的。

### 8.1 批处理监听（Batch Consumer）

一次 poll 拉一批，一次性处理（批量入库、批量聚合）。

```yaml
spring:
  kafka:
    listener:
      type: batch            # ▼ 把自动配置的容器工厂切成批处理模式
```

```java
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

@KafkaListener(topics = "orders", groupId = "batch-group")
public void onBatch(List<ConsumerRecord<String, Order>> records) {
    System.out.println("收到一批：" + records.size() + " 条");
    for (ConsumerRecord<String, Order> r : records) {
        billingService.charge(r.value());
    }
}
```

也可以直接收 `List<Order>`（只要 value）：

```java
@KafkaListener(topics = "orders", groupId = "batch-group")
public void onBatch(List<Order> orders) { ... }
```

> **用途与注意**：批量入库比逐条快一个数量级。但注意 `ack-mode=BATCH` 时，批中一条失败会**整批重读**——批量场景要配合幂等（第 9 章）。另外 `max.poll.interval.ms` 的坑在批量下更明显（一批处理太久），要把批次控制在能及时处理完的规模。

### 8.2 多 topic / 通配

```java
// ▼ 明确列出多个 topic
@KafkaListener(topics = {"orders", "payments"}, groupId = "finance-group")
public void onFinance(Order order) { ... }

// ▼ 用正则匹配 topic（新增 topic 自动纳入监听）
@KafkaListener(topicPattern = "order-.*", groupId = "order-group")
public void onOrder(ConsumerRecord<String, Order> record) { ... }
```

> **注意**：`topicPattern` 匹配到多个 topic 时，它们共享一个消费组；**同一个消息只被监听一次**。想区分来源，用 `@Header(KafkaHeaders.RECEIVED_TOPIC) String topic`。

### 8.3 手动 ack——精确控制提交时机

默认容器自动提交 offset。需要"**处理到一半不想提交 / 想自己控制**"时用手动 ack：

```yaml
spring:
  kafka:
    listener:
      ack-mode: manual_immediate   # 等代码里显式调 acknowledge()
```

```java
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

@KafkaListener(topics = "orders", groupId = "billing-group")
public void onOrder(ConsumerRecord<String, Order> record, Acknowledgment ack) {
    try {
        billingService.charge(record.value());
        ack.acknowledge();          // ▼ 业务成功，提交 offset
    } catch (Exception e) {
        // 业务失败：不提交 → 下次重新消费这条（at-least-once 的重投递）
        // 也可以主动发死信、打日志，决定权在业务手里
    }
}
```

> **手动 ack 的典型场景**：业务成功 + 提交 offset 需要"原子化"（比如 DB 写成功才算消费成功）；或你想自定义失败策略（部分成功、部分进死信）。

### 8.4 Kafka 事务——多条消息"要么全发、要么全不发"

Kafka 支持**事务性发送**：一批消息要么全部对消费者可见、要么一条都不可见。前提是开启幂等（`enable.idempotence=true`，事务要求幂等）并给事务一个 ID 前缀：

```yaml
spring:
  kafka:
    producer:
      enable-idempotence: true
      transaction-id-prefix: tx-     # ▼ 开启事务（每个应用实例必须用唯一前缀）
```

```java
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    // ▼ 多条消息在同一事务里：全部成功才对外可见，中途失败全部回滚
    @Transactional("kafkaTransactionManager")
    public void sendOrderBundle(String orderId) {
        kafkaTemplate.send("orders", orderId, "创建");
        kafkaTemplate.send("orders", orderId, "支付");
        kafkaTemplate.send("orders", orderId, "发货");
    }
}
```

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTransactionManager;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaTransactionConfig {

    @Bean
    public KafkaTransactionManager<Object, Object> kafkaTransactionManager(
            ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTransactionManager<>(producerFactory);
    }
}
```

> **关键**：`@Transactional("kafkaTransactionManager")` 里的 `"kafkaTransactionManager"` 是**事务管理器 bean 的名字**。只有配了 `transaction-id-prefix`，`ProducerFactory` 才是事务性的，`KafkaTransactionManager` 才有意义。第 9 章会把它升级成"DB + Kafka"跨资源一致性。

### 8.5 阻塞 listener + `Schedulers.boundedElastic()`——响应式的正确取舍

很多人一听到"高并发"就想上响应式。**对 Kafka 消费，诚实结论是：不要硬上响应式。**

**为什么**：
1. Kafka 消费模型是**拉取式**的——poll 一批、处理、再 poll。**这个模型天然带背压**（处理不过来说明你该少 poll 点 / 调小 `max-poll-records`），不需要响应式框架来背压。
2. Spring 官方的"响应式 Kafka"（`ReactiveKafkaProducerTemplate` / `ReactiveKafkaConsumerTemplate`、响应式 binder）在 **spring-kafka 3.2 起已标记废弃**——官方都不推荐了。
3. 响应式把错误处理、上下文传播搞复杂，收益（吞吐）在 Kafka 拉取模型下并不明显。

**正确姿势**：用普通阻塞 listener，**如果监听过种有慢操作（DB、外部 API），用 `Schedulers.boundedElastic()` 卸载，别占住容器线程**：

```java
import org.springframework.kafka.annotation.KafkaListener;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@KafkaListener(topics = "orders", groupId = "billing-group")
public void onOrder(Order order) {
    // ▼ 慢 IO 丢到 boundedElastic 线程池异步执行，容器线程立刻空闲去 poll 更多消息
    Mono.fromRunnable(() -> billingService.charge(order))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe();
}
```

**代价要讲清楚**：这样一改，**"处理成功"与"提交 offset"脱钩**了——`Mono.subscribe()` 是异步的，容器可能在异步任务完成前就提交了 offset，消息可能丢。**所以这个写法只适合"可以丢/可以重试补偿"的场景**；不能丢的业务，要么保持阻塞（牺牲一点吞吐换可靠），要么手动 ack + 等异步任务完成再 `ack.acknowledge()`：

```java
@KafkaListener(topics = "orders", groupId = "billing-group")
public void onOrder(Order order, Acknowledgment ack) {
    Mono.fromRunnable(() -> billingService.charge(order))
        .subscribeOn(Schedulers.boundedElastic())
        .doOnSuccess(v -> ack.acknowledge())    // ▼ 异步完成后才提交 offset
        .subscribe();
}
```

> **取舍总结**：Kafka 消费**优先用阻塞 listener**（简单、可靠、够用）。需要给容器线程减负时用 `boundedElastic()` 卸载，但要自己处理"提交时机"；不要为了"响应式"而响应式。

> **本章验证**：
> 1. 批处理：往 `orders` 发 100 条，监听 `List<ConsumerRecord>` 打印批次大小。
> 2. 手动 ack：故意抛异常不 ack，重启应用看同一条消息被重放。
> 3. 事务：`@Transactional` 里连发 3 条，`kafka-console-consumer` 观察要么 3 条同时出现、要么一条没有。

---

## 第 9 章：生产级——幂等、分区与 key、可观测、事务

到这一章，你已经"会用"了。下面是"上线后不出事"的部分。

### 9.1 幂等生产者——发送重试不重消息

第 6 章讲过 `enable.idempotence=true`（Kafka 3.0+ 默认开启）。它给每条消息加**生产者 ID + 序列号**，broker 端去重，保证"生产者重试导致的重复"被消除。

```yaml
spring:
  kafka:
    producer:
      enable-idempotence: true     # 幂等生产者（Broker 去重，防重试重复）
      acks: all                    # 开启幂等后，acks 自动提升为 all
```

> **局限要诚实**：幂等生产者防的是"**同一个生产者**重试产生的重复"。它**不防**"消费者重复消费"（那是 at-least-once 语义决定的）——所以消费者侧的去重仍然需要（9.2）。

### 9.2 消费者幂等——消息系统的第一准则

消息是 **at-least-once**（至少一次），即**可能重复投递**（处理成功但提交 offset 前崩溃 → 重启重读）。所以消费者**必须幂等**——同一条消息处理 N 次，效果和 1 次一样。

**实现幂等的通用方法**：用消息的唯一 ID 做去重。

```java
import org.springframework.kafka.annotation.KafkaListener;

@KafkaListener(topics = "orders", groupId = "billing-group")
public void chargeOrder(Order order) {
    // ▼ 先查幂等表：这个 order 处理过吗？
    if (idempotencyRepository.exists(order.getId())) {
        return;   // 处理过，直接跳过（幂等）
    }
    billingService.charge(order);
    idempotencyRepository.save(order.getId());   // 标记已处理（和扣款同一 DB 事务）
}
```

> **和 35 号文档的呼应**：35 号文档第 6 章讲的"幂等键（Idempotency-Key）"就是这个思想——用唯一键保证"同一操作只执行一次"。在消息系统里，**幂等是底线，不是优化**。更工程化的做法是 Outbox + 唯一约束（见 [04-生产级进阶](./04-生产级进阶-Outbox与Schema与分区调优.md)）。

### 9.3 分区与 key 设计

分区数在 topic 创建时就定了，**事后改要 rebalance、还可能乱序**，所以设计阶段就要想好：

1. **分区数怎么定**：`预期并发消费者数 × 每消费者并行度`。经验值：高峰消息量 / 单消费者处理能力，再乘冗余。**建议偏大**（分区只能增不能减，增了还可能乱序）。
2. **key 怎么选**：
   - 要求**严格顺序** → 用业务实体 ID（orderId / userId）做 key，同一实体消息串行有序；
   - 只要**吞吐** → 不传 key（粘性分区），全量并行；
   - **热 key**（某个 orderId 消息特别多）→ 该分区会成瓶颈，接受近似有序，或用 `key + 时间桶` 打散。
3. **key 的序列化**：`key-serializer` 要和生产/消费两端一致（一般 `StringSerializer`/`StringDeserializer`）。

```java
// ▼ 推荐实践：实体 ID 做 key，value 用 POJO
kafkaTemplate.send("orders", order.getUserId(), order);
```

### 9.4 跨资源事务——DB + Kafka 的原子性

第 8.4 的 `@Transactional("kafkaTransactionManager")` 只保证"Kafka 内部多条消息的原子性"。**真正难的是"DB 写 + 发消息"要么都成功、要么都失败**——比如下单要"写订单表 + 发订单事件"。

**直说结论**：**一个本地事务无法横跨"DB 连接 + Kafka Producer"**（两个独立的资源）。业界有几种解法：

| 方案 | 思路 | 评价 |
|------|------|------|
| **Outbox 模式**（推荐） | 订单表和 outbox 表在**同一个 DB 事务**里写；一个轮询/CDC 组件读 outbox 表发消息 | **最可靠**，业界标准（见 04 文档） |
| KafkaTransactionManager + @Transactional | 让 Kafka 事务和 DB 事务同生共死（`ChainedTransactionManager` 或 `KafkaTransactionManager` 结合本地事务） | **不推荐**：链式事务两阶段提交复杂、性能差、易出问题 |
| 先 DB 后发消息（best-effort） | DB 提交成功后再发消息，失败打补偿日志 | 简单但有窗口（DB 成功、消息没发出去） |

**如果坚持用 Spring 的事务抽象做"DB + Kafka"**，做法是把 `KafkaTransactionManager` 和 `DataSourceTransactionManager` 链起来——但**强烈不建议生产使用**：

```java
// 仅示意：链路事务（不推荐生产）——两阶段提交会持有 DB 连接，吞吐极低
@Bean
public ChainedTransactionManager chainedTransactionManager(
        DataSourceTransactionManager dbTm,
        KafkaTransactionManager<Object, Object> kafkaTm) {
    return new ChainedTransactionManager(dbTm, kafkaTm);
}
```

> **架构师判断**：跨资源一致性的**标准答案永远是 Outbox**（04 文档详讲）。Kafka 事务只用于"Kafka 内部的原子"（8.4），不要指望它救"DB + MQ"。

### 9.5 可观测性（Observability）

生产环境必须能"看到"消息在流动。Spring Kafka 对 Micrometer 是**原生支持**的，加一个 actuator 就自动上报指标：

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

**能看到的指标**（`/actuator/metrics` 或 Prometheus 抓取）：
- `kafka.producer.*`：发送速率、失败率、buffer 使用率；
- `kafka.consumer.*`：poll 速率、消费 lag（落后量）——**lag 是最重要的告警指标**；
- `spring.kafka.listener.*`：监听器处理耗时、失败次数。

**链路追踪（trace）**：想看"一条消息从生产到消费的完整路径"，加 Micrometer 追踪桥：

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
      probability: 1.0     # 生产一般 0.1~1.0（采样率）
```

spring-kafka 3.x 会自动用 Micrometer Observation 给 `KafkaTemplate` 发送和 `@KafkaListener` 消费打 span，配合 Zipkin/Tempo 能看到完整链路。

> **生产必须做**：消费 lag 监控（落后量持续上涨 = 消费者扛不住了）+ 死信监控（DLT 持续进消息 = 有系统性问题）。没有这两个告警，消息系统出问题就是黑盒。

> **本章验证**：启动带 actuator 的应用，`curl localhost:8080/actuator/metrics` 能看到 `kafka.consumer.*` 指标；发一批消息，观察 `kafka.consumer.lag` 先涨后落。

---

## 第 10 章：架构师视角——该不该直接写 Kafka、什么时候上 Stream

学完全部机制，现在站在架构师的高度看：**什么时候直接用 `spring-boot-starter-kafka`，什么时候该上 Spring Cloud Stream 抽象**。

### 10.1 诚实结论：Kafka-only 企业直接用 spring-boot-starter-kafka

企业级开发**绝大多数是 Kafka-only**：只有一种消息中间件、且未来也不打算换。这种场景，**直接写 Kafka 是正确选择**：

1. **少一层抽象**：`@KafkaListener` / `KafkaTemplate` 操作的就是 Kafka 自己，看到的就是 Kafka，调试直观。
2. **无抽象损耗**：Kafka 的所有能力（分区、事务、Exactly-once、Streams）都能直接用，不被抽象屏蔽。
3. **学习成本低**：一份知识体系（topic/分区/offset/消费组）通吃客户端 + 运维，不用学"binding/binder"那套映射。
4. **Spring Cloud Stream 的"换中间件不改代码"在你不需要换中间件时=零收益**。

> **这就是本专题（和 35 号文档）一律用 `spring-boot-starter-kafka` 的根本原因**。

### 10.2 什么时候才该考虑 Stream 抽象

Spring Cloud Stream 解决的是 **"中间件无关性"**。它的价值在下面这些场景才最大：

1. **可能换中间件**：多云、客户定制、技术演进（今天 Kafka、明天 RocketMQ/Pulsar）。
2. **多中间件并存**：一个应用要桥接 Kafka 和 RabbitMQ。
3. **团队统一消息编程模型**：多个系统、多种中间件，想用一套"函数式"模型通吃，降低学习成本。

如果你确定**只用 Kafka**，上面三条一条都不占——**别上 Stream**。上了只会多一层映射、多一层调试负担。

### 10.3 和 35 号文档手写 Kafka 的对比

| 维度 | 35 号文档手写 Kafka | 本文系统化的 Spring Kafka |
|------|--------------------|--------------------------|
| API | `KafkaTemplate` + `@KafkaListener`（同款） | 同一个 API，**系统化展开** |
| 重试/死信 | 没细讲（概念演示） | `DefaultErrorHandler` + DLT，**生产级兜底** |
| 客户端调优 | 没讲 | `acks` / 幂等 / `ack-mode` / 分区，**调优全解** |
| 幂等/事务 | 没讲 | 幂等生产者 + 消费者幂等 + Kafka 事务 + Outbox |
| 可观测 | 没讲 | Micrometer 指标 + 追踪 |
| 定位 | 讲"消息总线"概念 | 讲"生产级事件系统怎么造" |

> **架构师判断**：35 号文档用手写 Kafka 是**教学最佳选择**（让读者聚焦"消息总线"概念，不被框架抽象分心）。本文把**同一套写法**升级成完整知识体系。**两者是同一套技术栈，不冲突**——35 号文档是"点"，本文是"面"。

### 10.4 不要用消息系统的场景

- **简单同步 RPC**：要立即拿结果，别用消息（用 HTTP/Feign/gRPC）。
- **超低延迟**：消息系统天然有毫秒级延迟，亚毫秒场景别用。
- **消息量极小**：一天几十条消息，一个定时任务就搞定，引 Kafka 是过度设计。
- **单体内部强一致性**：同一个 DB 事务里能解决的问题，别引入分布式消息的最终一致性复杂度。

### 10.5 架构师的核心思维

学完本文，你应该建立的判断力：

1. **先有解耦需求，再选技术**。不是"学了个新队列就要用"，而是"业务有解耦/多消费者/削峰需求，才上 Kafka"。
2. **Kafka 不是银弹**。它是"高吞吐 + 最终一致性"的工具，换来的是"至少一次 + 必须幂等"的纪律。
3. **顺序和并行是矛盾的**。要保序就接受"同 key 串行"，要吞吐就别指望全局有序——分区设计阶段就要定。
4. **消息语义是底线**。at-least-once → 消费者必须幂等；exactly-once → 事务（且事务只解决 Kafka 内部）；DB+MQ 一致性 → Outbox。
5. **可观测性不是可选项**。lag 监控 + 死信监控是消息系统的心脏监护仪。
6. **抽象要算账**。Stream 的"换中间件不改代码"在 Kafka-only 企业是负资产，在多中间件企业是正资产。**架构师要算清这笔账**。

---

## 附录 A：API 签名校验表

> 所有签名对照 spring-kafka 官方文档（spring-kafka 3.x / 4.x 线）。版本以 Boot BOM 托管为准。

### A.1 KafkaTemplate 全签名（`org.springframework.kafka.core.KafkaTemplate<K,V>`）

```java
CompletableFuture<SendResult<K, V>> send(String topic, V data);
CompletableFuture<SendResult<K, V>> send(String topic, K key, V data);
CompletableFuture<SendResult<K, V>> send(String topic, Integer partition, K key, V data);
CompletableFuture<SendResult<K, V>> send(String topic, Integer partition, Long timestamp, K key, V data);
CompletableFuture<SendResult<K, V>> send(ProducerRecord<K, V> record);
CompletableFuture<SendResult<K, V>> send(Message<?> message);
void flush();                                        // 同步刷出缓冲中的所有消息
boolean isTransactional();                            // 底层 ProducerFactory 是否事务性
<T> T executeInTransaction(OperationsCallback<K, V, T> callback);  // 事务回调
```

> spring-kafka 3.x 起 `send` 返回 **`CompletableFuture`**（3.x 之前是 `ListenableFuture`，用 `addCallback`）。回调里用 `result.getRecordMetadata().partition() / offset()` 拿落盘信息。

### A.2 @KafkaListener 常用属性（`org.springframework.kafka.annotation.KafkaListener`）

| 属性 | 说明 |
|------|------|
| `topics` | 监听的 topic（`String[]`，可多值） |
| `topicPattern` | 用正则匹配 topic（与 `topics` 二选一） |
| `groupId` | 消费组（覆盖全局 `spring.kafka.consumer.group-id`） |
| `id` | 监听器 id（默认自动生成；日志/监控里可识别） |
| `concurrency` | 并发线程数（覆盖全局 `spring.kafka.listener.concurrency`） |
| `containerFactory` | 指定容器工厂 bean 名（多套配置时用） |
| `autoStartup` | 是否随应用启动自动消费（默认 true） |

方法参数按需自动注入：payload、`ConsumerRecord<K,V>`、`Acknowledgment`、`Consumer<?,?>`、`@Header(...)`、`@Payload(...)`。

### A.3 错误处理 API（`org.springframework.kafka.listener.*`）

```java
CommonErrorHandler                     // 通用错误处理器接口（Spring Kafka 2.8+）
DefaultErrorHandler(              // 默认实现：重试 + 兜底
    DeadLetterPublishingRecoverer recoverer,   // 重试耗尽后发死信
    BackOff backOff)                          // 退避策略（FixedBackOff / ExponentialBackOff）
new FixedBackOff(1000L, 3L);                  // 每 1000ms 重试，总共最多 3 次（含首次）
new ExponentialBackOff(1000L, 2.0, 10000L);   // 指数退避：1s 起，×2，最长 10s

// DeadLetterPublishingRecoverer 构造
new DeadLetterPublishingRecoverer(KafkaTemplate<?, ?> template);
new DeadLetterPublishingRecoverer(KafkaTemplate<?, ?> template,
    BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver);
```

```java
// ErrorHandlingDeserializer（org.springframework.kafka.support.serializer.ErrorHandlingDeserializer）
// 反序列化失败兜底：不抛死，把原始字节 + 异常装进消息头走错误处理链
new ErrorHandlingDeserializer<>(delegateDeserializer);
// 或经配置指定委托：
//   spring.deserializer.key.delegate.class
//   spring.deserializer.value.delegate.class
```

### A.4 `spring.kafka.*` 属性速查（已校验）

**公共**
| 属性 | 默认 | 说明 |
|------|------|------|
| `spring.kafka.bootstrap-servers` | 无 | Kafka 地址（逗号分隔多 broker） |

**producer（`spring.kafka.producer.*`）**
| 属性 | 默认 | 说明 |
|------|------|------|
| `acks` | `1` | 落盘确认级别：`0`/`1`/`all` |
| `retries` | `2147483647`* | 发送重试次数（*开启幂等后默认无限） |
| `enable-idempotence` | `true`（Kafka 3.0+） | 幂等生产者（防重试重复） |
| `batch-size` | 16384 | 批量发送的字节阈值 |
| `linger-ms` | 0 | 凑批等待时长（ms） |
| `buffer-memory` | 33554432 | 发送缓冲大小（字节） |
| `compression-type` | `none` | 压缩：`none`/`gzip`/`snappy`/`lz4`/`zstd` |
| `transaction-id-prefix` | 无 | 事务 ID 前缀（开启 Kafka 事务） |
| `key-serializer` / `value-serializer` | 无 | 序列化器类 |

**consumer（`spring.kafka.consumer.*`）**
| 属性 | 默认 | 说明 |
|------|------|------|
| `group-id` | 无 | 消费组 |
| `auto-offset-reset` | `latest` | 新组从哪读：`earliest`/`latest`/`none` |
| `enable-auto-commit` | `true` | 是否自动提交（生产建议 `false`，交给 ack-mode） |
| `max-poll-records` | 500 | 单次 poll 最多条数 |
| `key-deserializer` / `value-deserializer` | 无 | 反序列化器类 |
| `properties.*` | 无 | 透传任意 kafka-clients 配置（如 `max.poll.interval.ms`） |

**listener（`spring.kafka.listener.*`）**
| 属性 | 默认 | 说明 |
|------|------|------|
| `ack-mode` | `BATCH` | offset 提交时机（见 6.3） |
| `concurrency` | 1 | 每容器消费线程数 |
| `type` | `single` | `single`/`batch`（批处理监听） |
| `poll-timeout` | 5000 | 单次 poll 超时（ms） |
| `missing-topics-fatal` | `false` | topic 不存在是否启动失败（`false`=重试等待） |

---

## 附录 B：完整可跑项目

一个"HTTP 触发 → 发消息 → 消费打印 + 重试 + 死信"的完整最小项目（Boot 4.1.0 + `spring-boot-starter-kafka`）。

### B.1 pom.xml（关键部分）

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-kafka</artifactId>
    </dependency>
</dependencies>
```

> 不需要 Spring Cloud BOM。`spring-kafka` / `kafka-clients` 版本由 Boot 父工程托管。

### B.2 KafkaDemoApplication.java

```java
package com.example.kafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KafkaDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(KafkaDemoApplication.class, args);
    }
}
```

### B.3 Order.java（消息体 POJO）

```java
package com.example.kafka.model;

public class Order {
    private String id;
    private String userId;
    private int amount;

    public Order() { }
    public Order(String id, String userId, int amount) {
        this.id = id; this.userId = userId; this.amount = amount;
    }
    // getter / setter（省略）
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
}
```

### B.4 OrderController.java（HTTP 触发发消息）

```java
package com.example.kafka.controller;

import com.example.kafka.model.Order;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderController(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/orders")
    public String placeOrder(@RequestBody Order order) {
        kafkaTemplate.send("orders", order.getId(), order);   // ▼ 以 orderId 为 key（保序）
        return "sent: " + order.getId();
    }

    // ▼ 专门发一条"会失败"的消息，演示重试 + 死信
    @PostMapping("/send-error")
    public String sendError(@RequestBody String body) {
        kafkaTemplate.send("orders", "bad-order", "【会失败】" + body);
        return "sent error message";
    }
}
```

### B.5 OrderConsumer.java（消费 + 重试 + 死信）

```java
package com.example.kafka.consumer;

import com.example.kafka.model.Order;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

@Component
public class OrderConsumer {

    @KafkaListener(topics = "orders", groupId = "order-group")
    public void onOrder(Order order) {
        System.out.println("处理订单：" + order.getId() + "，金额=" + order.getAmount());
        // ▼ 演示重试/死信：payload 是字符串且含"会失败"时抛异常
        if (order.getId() != null && order.getId().contains("bad-order")) {
            throw new RuntimeException("模拟失败，触发重试与死信");
        }
        System.out.println("订单处理成功：" + order.getId());
    }
}

@Configuration
class KafkaErrorConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template) {
        // 重试 3 次（含首次），每 1s 一次；仍失败发到 orders-dlq
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
            (record, ex) -> new TopicPartition(record.topic() + "-dlq", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
```

### B.6 application.yaml

```yaml
spring:
  application:
    name: kafka-demo
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      enable-idempotence: true
    consumer:
      group-id: order-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.example
    listener:
      ack-mode: record
```

### B.7 跑起来

```bash
# 起 Kafka（见第 1 章）
# 跑应用
./mvnw spring-boot:run

# 发正常订单
curl -X POST -H "Content-Type: application/json" \
  -d '{"id":"order-1","userId":"u-100","amount":99}' \
  http://localhost:8080/orders
# → 控制台：处理订单：order-1，金额=99 / 订单处理成功：order-1

# 发会失败的消息（演示重试 + 死信）
curl -X POST -H "Content-Type: application/json" -d "bad-test" \
  http://localhost:8080/send-error
# → 控制台：处理订单：bad-order（重试 3 次后失败）
# → 消息进入 orders-dlq topic
docker exec -it kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic orders-dlq --from-beginning
# → 能看到这条死信
```

---

## 附录 C：版本对照与踩坑手册

### C.1 版本对照表

`spring-boot-starter-kafka` / `spring-kafka` / `kafka-clients` 版本由 **Boot BOM 统一托管**，你不需要（也不该）手写 spring-kafka / kafka-clients 版本号。大致对应关系（以官方发布为准）：

| Spring Boot | spring-kafka | kafka-clients |
|-------------|--------------|---------------|
| 3.2.x | 3.1.x | 3.6.x |
| 3.3.x | 3.2.x | 3.7.x |
| 3.4.x | 3.3.x | 3.8.x |
| 3.5.x | 3.4.x | 3.9.x |
| **4.0.x / 4.1.x** | **4.0.x / 4.1.x** | **4.0.x** |

> **本仓库 `demo01` 用的是 Boot 4.1.0** → `spring-boot-starter-kafka`（BOM 托管 `spring-kafka` 4.x + `kafka-clients` 4.x）。**本文示例即此组合**。Boot 3.x 的用法和本文完全一致（API 无破坏性差异），代码可直接照抄。

### C.2 Boot 4 项目接入方式

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
</parent>
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-kafka</artifactId>
    </dependency>
</dependencies>
```

> **不需要 Spring Cloud 任何依赖**。如果同时引入了 `spring-cloud-dependencies`，注意它可能会接管 `spring-kafka` 版本——纯 Kafka 项目直接去掉 Spring Cloud BOM。

### C.3 常见踩坑

#### 坑 1：不配消费组 → 每次都从最新开始、重复消费

**原因**：`@KafkaListener` 没写 `groupId`，全局 `spring.kafka.consumer.group-id` 也没配 → 每次重启随机组名，之前消费的 offset 不认账。
**解决**：生产环境**永远配消费组**（注解 `groupId` 或 `spring.kafka.consumer.group-id`）。

#### 坑 2：`auto-offset-reset` 没配 → 新组只收到"之后"的消息

**原因**：默认 `latest`，新消费组从启动时刻开始收，历史消息读不到。
**解决**：想重放历史配 `earliest`；只收新消息保持 `latest`。

#### 坑 3：反序列化失败卡线程 / 反复报错

**原因**：value 是坏 JSON，直接 `JsonDeserializer` 抛异常，消费者线程反复失败。
**解决**：用 `ErrorHandlingDeserializer` 包一层（第 5.5），让坏消息走重试 + 死信，不卡线程。

#### 坑 4：`JsonDeserializer` 报 `Untrusted package`

**原因**：JsonDeserializer 出于安全默认只信任 `java.*`，业务 POJO 没放行。
**解决**：配 `spring.json.trusted.packages: com.example`（或开发环境 `*`）。

#### 坑 5：处理太慢 → 被判定"死亡" → rebalance / 重复消费

**原因**：一批消息处理时间超过 `max.poll.interval.ms`（默认 5 分钟），broker 认为消费者失联。
**解决**：调大 `max.poll.interval.ms`、调小 `max-poll-records`、或用批处理（8.1）解耦拉取与处理。

#### 坑 6：`send()` 当 void 用，失败无感知

**原因**：`send()` 异步，异常不会抛到调用线程。
**解决**：用返回的 `CompletableFuture` 加 `whenComplete` / `exceptionally` 处理失败，至少打日志。

#### 坑 7：重试没生效

**原因**：没声明 `CommonErrorHandler` bean，用的是默认行为（默认对**不可恢复异常**不重试、对 `SeekToCurrentErrorHandler` 旧行为不熟悉）。
**解决**：声明 `DefaultErrorHandler` bean（第 5.3），Boot 会自动接到容器工厂。

#### 坑 8：配了 `transaction-id-prefix` 但没开事务就 `send()` → 报 `No transaction is in process`

**原因**：`ProducerFactory` 是事务性的，`send()` 必须在事务内执行。
**解决**：要么 `@Transactional("kafkaTransactionManager")`，要么不要配 `transaction-id-prefix`。

#### 坑 9：手写 `kafka-clients` 版本号导致冲突

**原因**：自己写 `kafka-clients` / `spring-kafka` 版本，和 Boot BOM 冲突。
**解决**：交给 Boot 父工程托管，**别写版本号**。

#### 坑 10：topic 不存在、应用启动卡住/报错

**原因**：topic 未创建，`missing-topics-fatal=false` 时容器会重试等待。
**解决**：用 `NewTopic` bean 预建 topic（第 2.5），或让 broker 自动创建，或运维预建。

---

## 配套学习资料

- [Spring for Apache Kafka 官方参考文档](https://docs.spring.io/spring-kafka/reference/)（权威，英文）
- [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 10 章（手写 Kafka 做消息总线，理解概念后回来读本文更顺）
- [Kafka 核心概念与 Spring Boot 实战](../Kafka消息队列实战专题/01-Kafka消息队列从入门到架构师.md)（Kafka 基础概念补充）
- [Reactor 响应式入门](../Reactor响应式编程/01-Reactor响应式入门.md)（第 8.5 节 `Schedulers.boundedElastic()` 前置）

---

## 下一步：继续进阶

学完本文（0-10 章），你已经"会用"Spring Kafka 了。如果你想达到**真正的架构师水平**——Kafka 原理、生产调优、流式计算、事件驱动架构——继续读进阶篇：

➡️ **[Kafka 进阶实战](./02-Kafka进阶实战.md)**

进阶篇专门为**Kafka 零基础**的人设计（第 1 章补 Kafka 地基），带你从"会用"走到"会设计事件驱动系统"。

---

> **写在最后**：这份文档从"它是什么"讲到"架构师怎么取舍"，每一步都配了可跑的代码和已校验的 API。如果你完整跟下来，你拥有的不只是"会用 Spring Kafka"，而是**一套关于"消息如何在分布式系统里可靠、解耦、可观测地流动"的思维模型**——topic/分区/offset/消费组、发布订阅、幂等、重试、死信、事务、可观测。这些模式在你日后做任何事件驱动系统时都会反复用到。祝你学习顺利。
