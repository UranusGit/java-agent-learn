# Spring Cloud Stream 从入门到架构师

> **这份文档是什么**：一份**从零开始、循序渐进、最终达到架构师水平**的 Spring Cloud Stream 专题手册。你不需要懂消息队列、不需要懂微服务，只要会 Java 和 Spring Boot 基础，跟着读、跟着抄代码，就能从"它到底是什么"一路学到"什么时候该用它、什么时候不该用、怎么在生产环境扛住千万级消息"。
>
> **它和 35 号文档的关系**：[35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 10 章用了**原生 `spring-kafka`**（手写 `KafkaTemplate` 发、`@KafkaListener` 收）做 chunk 持久总线。那份文档教的是"消息总线"这个**概念**。本文教的是它的**升级版架构**——Spring Cloud Stream：一个把 Kafka/RabbitMQ/RocketMQ 等中间件**抽象统一**的框架，让你换中间件不用改业务代码。学完本文，你会明白 35 号文档第 10 章的手写 Kafka 在生产环境**为什么应该换成 Spring Cloud Stream**。
>
> **版本前提（重要，已校验）**：本文基于 **Spring Cloud Stream 4.2.x / 4.3.x（Spring Cloud 2024.0 / 2025.0）**，对应 **Spring Boot 3.4.x / 3.5.x**。如果你的项目是 **Spring Boot 4.x**（如本仓库 `demo01` 用的是 Boot 4.1.0），请用 **Spring Cloud 2025.1.x (Oakwood)**——这是第一个正式支持 Boot 4 的发布列车。文末附录有版本对照表和 Boot 4 项目的接入方式。**所有 API 签名、配置项均已对照官方文档逐字校验**（版本信息见 [官方文档](https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/producing-and-consuming-messages.html)，stable 列：5.0.2 / 4.3.3 / 4.2.3 / 4.1.6）。

---

## 目录

- [第 0 章：先搞清楚它是什么](#第-0-章先搞清楚它是什么)
- [第 1 章：30 分钟跑起来——你的第一个消息流](#第-1-章30-分钟跑起来你的第一个消息流)
- [第 2 章：核心三件套——Binder / Binding / Destination](#第-2-章核心三件套binder--binding--destination)
- [第 3 章：编程模型——为什么是函数式，不是注解](#第-3-章编程模型为什么是函数式不是注解)
- [第 4 章：生产者全解——Supplier 与 StreamBridge](#第-4-章生产者全解supplier-与-streambridge)
- [第 5 章：消费者全解——Consumer、消费组、重试、死信](#第-5-章消费者全解consumer消费组重试死信)
- [第 6 章：Kafka Binder 详解——topic/分区/offset 如何映射](#第-6-章kafka-binder-详解topic分区offset-如何映射)
- [第 7 章：RabbitMQ Binder 详解——体会"换中间件不改代码"](#第-7-章rabbitmq-binder-详解体会换中间件不改代码)
- [第 8 章：进阶——响应式、批处理、多输入输出、函数组合](#第-8-章进阶响应式批处理多输入输出函数组合)
- [第 9 章：生产级——可观测、分区、幂等、事务](#第-9-章生产级可观测分区幂等事务)
- [第 10 章：架构师视角——该不该用、怎么取舍](#第-10-章架构师视角该不该用怎么取舍)
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
订单服务 ──发消息──> [ 消息系统(Kafka/RabbitMQ) ] ──┬──> 库存服务
                                                   ├──> 通知服务
                                                   ├──> 计费服务
                                                   └──> 市场分析服务
```

订单服务**根本不知道**下游有几个、是谁——它只管发消息。这就叫**发布-订阅（Pub/Sub）**。

### 0.2 那为什么需要 Spring Cloud Stream？直接用 Kafka 不行吗？

可以直接用 Kafka。35 号文档第 10 章就是这么做的——`KafkaTemplate.send()` 发、`@KafkaListener` 收。**能用，但有个大问题**：

> 你的代码**和 Kafka 死死绑在一起**。哪天公司决定从 Kafka 迁到 RabbitMQ（或反过来，或上 RocketMQ、Pulsar），你的每一处 `KafkaTemplate`、每一个 `@KafkaListener`、每一条 Kafka 配置都要改。业务代码没动，全在改中间件胶水代码。

Spring Cloud Stream 解决的就是这个。它做的事用一句话讲：

> **把"发消息/收消息"抽象成 Java 标准的函数（`Supplier`/`Function`/`Consumer`），让你只写业务函数，由框架把它接到具体的中间件上。换中间件只改配置，不改代码。**

看这个对比（先不用懂细节，感受一下）：

**手写 Kafka（35 号文档第 10 章的方式）：**
```java
// 业务代码里混着 Kafka 的 API
@Autowired KafkaTemplate<String, String> kafka;

public void placeOrder(Order o) {
    kafka.send("orders", o.getId(), o.toJson());   // ← Kafka 专属
}

@KafkaListener(topics = "orders", groupId = "inventory")   // ← Kafka 专属
public void onOrder(String msg) { inventory.deduct(msg); }
```

**Spring Cloud Stream 的方式：**
```java
// 业务代码里完全没有 Kafka 的影子，只是一个普通函数
@Bean
public Function<Order, Void> processOrder() {     // ← 纯 Java 函数
    return order -> { inventory.deduct(order); return null; };
}
```

```yaml
# 换中间件？只改这里，上面的 Java 代码一个字不动
spring:
  cloud:
    stream:
      bindings:
        processOrder-in-0:
          destination: orders
```

从 Kafka 换到 RabbitMQ，你**只改依赖和这一段 YAML**，业务函数 `processOrder` 一行不改。**这就是 Spring Cloud Stream 的全部价值。**

### 0.3 它在整个 Spring 生态的位置

```
你的业务代码（Function/Consumer/Supplier）
        ↓
Spring Cloud Stream   ← 本文主角：提供统一抽象
        ↓
Spring Cloud Function ← 底层：把函数当一等公民
        ↓
Binder（Kafka Binder / RabbitMQ Binder / ...）← 适配具体中间件
        ↓
Spring Kafka / Spring AMQP ← 真正连中间件的客户端
        ↓
Kafka / RabbitMQ / RocketMQ（真正的消息中间件）
```

**记住这张图**。你写的函数在最上层，中间件在最下层，中间几层是 Spring 帮你屏蔽的。架构师必须能在脑子里画出这张分层。

### 0.4 适合谁、不适合谁

| 适合 | 不适合 |
|------|--------|
| 微服务之间的事件通信 | 单体内部的方法调用（杀鸡用牛刀） |
| 需要解耦多个消费者 | 强同步、要立即拿结果的请求-响应（用 Feign/HTTP） |
| 可能换中间件、或多中间件并存 | 消息量极小（一天几十条）的简单通知 |
| 流式数据处理（配合 Kafka Streams） | 超低延迟（亚毫秒）场景 |

---

## 第 1 章：30 分钟跑起来——你的第一个消息流

> **目标**：用最快的方式跑起来一个"发一条消息、收到并打印"的程序，建立直觉。细节后面慢慢讲。

### 1.1 建项目

用 [Spring Initializr](https://start.spring.io/) 建一个项目，选：
- **Spring Boot 3.4.x**（或 3.5.x）
- 依赖：**Cloud Stream** + **Spring for Apache Kafka**（或直接加下面的依赖）

手写 `pom.xml` 的关键部分：

```xml
<!-- Spring Cloud 版本管理（放在 <dependencyManagement> 里） -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2024.0.0</version>   <!-- 对应 Boot 3.4；Boot 3.5 用 2025.0.0 -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- ▼ Spring Cloud Stream 核心（已校验：这是标准 artifact） -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-stream-kafka</artifactId>
    </dependency>
</dependencies>
```

> **注意 artifact 名字**：是 `spring-cloud-starter-stream-kafka`（Kafka 的 binder starter）。它**已经包含了** `spring-cloud-stream` 核心 + Kafka binder + spring-kafka 客户端，不用再单独引。这是新手最容易搞错的点。

### 1.2 写你的第一个 Consumer（收消息）

新建主类 + 一个 `Consumer` bean：

```java
package com.example.stream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.function.Consumer;

@SpringBootApplication
public class StreamApp {
    public static void main(String[] args) {
        SpringApplication.run(StreamApp.class, args);
    }

    // ▼ 这就是一个"消费者"：收到字符串，打印它
    @Bean
    public Consumer<String> logMessage() {
        return message -> System.out.println("收到消息：" + message);
    }
}
```

### 1.3 配置：告诉它从哪收

`application.yaml`：

```yaml
spring:
  cloud:
    function:
      definition: logMessage          # ▼ 声明要绑定哪个函数（多函数用 ; 分隔）
    stream:
      bindings:
        logMessage-in-0:              # ▼ 消费者的绑定名（函数名 + "-in-0"）
          destination: hello-topic    # ▼ 对应 Kafka 的 topic 名
          group: log-group            # ▼ 消费组名
  # Kafka 连接地址
  kafka:
    bootstrap-servers: localhost:9092
```

**先别急着跑——这有两个你必须记住的命名规则**（第 2 章细讲）：
1. `spring.cloud.function.definition: logMessage` → 告诉框架"我要用 `logMessage` 这个函数 bean"。
2. `logMessage-in-0` → 这是绑定的名字。规则是 **`<函数名>-in-0`**（消费者用 `-in-`，第 0 个输入）。框架靠这个名字把配置和函数对应起来。

### 1.4 起 Kafka + 跑起来

```bash
# 起 Kafka（KRaft 模式，不需要 Zookeeper）
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9092 -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  confluentinc/cp-kafka:latest

# 跑你的应用
./mvnw spring-boot:run
```

### 1.5 发一条消息试试

用 Kafka 自带的命令行工具往 `hello-topic` 发一条：

```bash
docker exec -it kafka kafka-console-producer --bootstrap-server localhost:9092 --topic hello-topic
# 然后输入：你好 Spring Cloud Stream
# 按 Ctrl+D 退出
```

回到你的应用控制台，会看到：

```
收到消息：你好 Spring Cloud Stream
```

**恭喜——你的第一个 Spring Cloud Stream 程序跑起来了。** 你写了一个普通的 `Consumer<String>` 函数，没碰任何 Kafka API，框架就把它接到了 Kafka 上。

### 1.6 本章小结

记住这三件事，后面的所有内容都是它们的展开：

1. **业务逻辑就是一个函数 bean**（`Consumer` / `Function` / `Supplier`）。
2. **`spring.cloud.function.definition`** 声明用哪个函数。
3. **`spring.cloud.stream.bindings.<函数名>-in-0/out-0>`** 配置这个函数接到哪个目的地（destination）。

---

## 第 2 章：核心三件套——Binder / Binding / Destination

这三个词贯穿全文，必须彻底搞懂。它们是 Spring Cloud Stream 的**抽象骨架**。

### 2.1 Destination（目的地）——最具体的概念

**Destination 就是一条逻辑上的"消息管道"**。在 Kafka 里它对应一个 **topic**，在 RabbitMQ 里对应一个 **exchange + queue** 的组合。

```
你的代码里写的是：destination: orders
                    ↓
Kafka：orders 是一个 topic
RabbitMQ：orders 是一个 exchange（绑定到某个 queue）
```

**关键**：你的代码里**永远只写 destination 名字**（如 `orders`），不写"topic"或"exchange"这种中间件专属词。这就是"换中间件不改代码"的根基。

### 2.2 Binder（绑定器）——中间件的适配器

Binder 是**"把 Spring Cloud Stream 的抽象接到某个具体中间件"的适配器**。

- `KafkaBinder`：知道怎么把 destination 变成 Kafka topic、怎么用 Kafka 的 producer/consumer。
- `RabbitMQBinder`：知道怎么把 destination 变成 RabbitMQ exchange/queue。

你引哪个 starter，就有哪个 Binder：
- `spring-cloud-starter-stream-kafka` → KafkaBinder
- `spring-cloud-starter-stream-rabbit` → RabbitMQBinder

**你的业务代码永远不直接和 Binder 打交道**——它由框架自动装配。你只在配置里间接影响它（比如"连哪个 Kafka"）。

> **架构师视角**：Binder 是 SPI（服务提供者接口）。理论上你可以自己写一个 Binder 适配任何消息系统。Spring 官方提供了 Kafka、RabbitMQ、Kafka Streams、Pulsar、Solace、Kinesis 等。

### 2.3 Binding（绑定）——函数和目的地之间的桥

**Binding 是"你的某个函数的输入/输出"和"某个 destination"的连接关系。**

回忆第 1 章的 `logMessage-in-0`——**它就是一个 binding 的名字**。Binding 名字的规则是这份框架最核心的约定：

```
<函数bean的名字>-in-<序号>     ← 输入绑定（消费者用）
<函数bean的名字>-out-<序号>    ← 输出绑定（生产者用）
```

举例：

| 函数 bean | 类型 | 输入 binding | 输出 binding |
|-----------|------|-------------|-------------|
| `Consumer<String> logMessage()` | 消费者 | `logMessage-in-0` | 无 |
| `Supplier<String> emit()` | 生产者 | 无 | `emit-out-0` |
| `Function<String,String> upper()` | 处理器 | `upper-in-0` | `upper-out-0` |

**为什么要 `-0` 这个序号？** 因为一个函数可以有多个输入输出（第 8 章讲）。单输入输出的，序号就是 `0`。

### 2.4 三者关系一张图

```
   你的函数 bean
       │
       │ (Binding：函数的某条输入/输出线)
       │   名字：<func>-in-0
       ▼
   Destination（逻辑目的地：如 "orders"）
       │
       │ (Binder：把 destination 映射到具体中间件)
       ▼
   Kafka topic / RabbitMQ exchange
```

**背下来**：函数 →（Binding）→ Destination →（Binder）→ 中间件实体。

### 2.5 配置项速查（已校验）

所有配置都在 `spring.cloud.stream.bindings.<binding名字>.*` 下：

```yaml
spring:
  cloud:
    stream:
      bindings:
        logMessage-in-0:              # binding 名字
          destination: hello-topic    # 对应的 destination
          group: log-group            # 消费组（消费者才有意义）
          content-type: text/plain    # 内容类型（影响序列化）
          consumer:                   # 消费者专属配置（第 5 章详讲）
            max-attempts: 3
          producer:                   # 生产者专属配置（第 4 章详讲）
            partition-count: 3
```

> **`content-type` 是什么**：它告诉框架"这条消息的 payload 是什么格式"，从而决定怎么序列化/反序列化。比如 `application/json` 会让框架自动把 POJO 和 JSON 互转。第 8 章详讲。

---

## 第 3 章：编程模型——为什么是函数式，不是注解

这一章不长，但极其重要——它决定了你**用对还是用错**这个框架。

### 3.1 旧时代：注解模型（`@EnableBinding` / `@StreamListener`）

Spring Cloud Stream 2.x 时代的写法长这样：

```java
// ❌ 旧写法（已废弃，别学）
@EnableBinding(Sink.class)
public class OldConsumer {
    @StreamListener(Sink.INPUT)
    public void handle(String msg) { ... }
}
```

**为什么废弃**：它把"消息处理"做成了框架专属的注解（`@StreamListener`），你的代码被框架"侵入"了——脱离 Spring Cloud Stream 就不能复用。

### 3.2 新时代：函数式模型（3.x 起，本文用法）

Spring Cloud Stream 3.x 起全面转向**函数式编程模型**（底层是 Spring Cloud Function）：

```java
// ✅ 新写法（本文全程用这个）
@Bean
public Consumer<String> handle() {
    return msg -> { ... };   // 就是一个普通的 java.util.function.Consumer
}
```

**好在哪里**：

1. **零侵入**：`Consumer`/`Function`/`Supplier` 是 **JDK 自带的标准函数式接口**（`java.util.function` 包）。你的处理逻辑是一个纯函数，不依赖任何 Spring Cloud Stream 的类。
2. **可测试**：单元测试时直接 `handle().accept("test")`，不用启动整个消息中间件。
3. **可复用**：同一个函数 bean 既能接消息流，也能（配合 Spring Cloud Function）暴露成 HTTP 端点。

> **铁律**：从今天起，看到任何教程用 `@StreamListener` / `@EnableBinding`，**直接跳过**——那是 3.x 之前的写法，已被官方标注为不推荐。本文所有代码都是函数式模型。

### 3.3 三种函数的角色

| 接口 | 角色 | 有输入？ | 有输出？ | 典型场景 |
|------|------|---------|---------|---------|
| `Supplier<T>` | 源 Source | 否 | 是 | 定时/事件驱动地**产生**消息 |
| `Function<T,R>` | 处理器 Processor | 是 | 是 | 收一条、处理、**产出**另一条 |
| `Consumer<T>` | 汇 Sink | 是 | 否 | 收一条、处理、**到此为止** |

记忆：**Supplier 只出不进，Consumer 只进不出，Function 进出都有**。

---

## 第 4 章：生产者全解——Supplier 与 StreamBridge

"怎么发消息"有两种方式，对应两种真实场景。都要会。

### 4.1 方式一：`Supplier`——自动/定时产生消息

`Supplier` 是"消息的源头"。它**没有输入**，所以由框架的**轮询机制**触发。

#### 4.1.1 命令式 Supplier（默认每秒轮询一次）

```java
@Bean
public Supplier<String> ticker() {
    return () -> "tick-" + System.currentTimeMillis();   // 每次被调用返回一条
}
```

```yaml
spring:
  cloud:
    function:
      definition: ticker
    stream:
      bindings:
        ticker-out-0:
          destination: ticks
      # ▼ 轮询配置（已校验，前缀是 spring.integration.poller）
      poller:
        fixed-delay: 1000        # 每 1000ms 触发一次（默认就是 1000）
```

**发生了什么**：框架每 1 秒调一次 `ticker.get()`，把返回值发到 `ticks` 这个 destination。

> **轮询配置项（官方校验）**，都在 `spring.integration.poller.*` 下：
> - `fixed-delay`：固定间隔（毫秒），默认 1000
> - `max-messages-per-poll`：每次轮询最多发几条，默认 1
> - `cron`：用 cron 表达式触发
> - `initial-delay`：初始延迟，默认 0

#### 4.1.2 命令式 Supplier 的问题：它只能"定时发"

很多时候你不是"定时发"，而是"**某个事件发生时才发**"——比如用户点了下单按钮，才发订单消息。`Supplier` + 轮询不适合这种。这就需要方式二。

### 4.2 方式二：`StreamBridge`——在你想要的时候发

`StreamBridge` 是 Spring Cloud Stream 提供的一个工具 bean，**让你在代码任意位置主动发消息**。这是生产环境最常用的发送方式。

```java
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {

    private final StreamBridge streamBridge;

    public OrderController(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @PostMapping("/orders")
    public String placeOrder(@RequestBody Order order) {
        // 业务逻辑...
        // ▼ 在事件发生时，主动发消息
        streamBridge.send("orders-out-0", order);   // 第一个参数是 binding 名，第二个是数据
        return "ok";
    }
}
```

#### 4.2.1 `StreamBridge.send()` 的全部签名（官方校验）

`send` 方法有多个重载，逐个记住（来自[官方文档](https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/producing-and-consuming-messages.html)）：

```java
// 1. 最常用：binding 名 + 数据
boolean send(String bindingName, Object data);

// 2. 指定输出内容类型（MimeType）
boolean send(String bindingName, Object data, MimeType outputContentType);

// 3. 多 Binder 场景：指定用哪个 binder（如同时连 Kafka 和 RabbitMQ）
boolean send(String bindingName, String binderType, Object data);

// 4. 多 Binder + 内容类型
boolean send(String bindingName, String binderType, Object data, MimeType outputContentType);
```

> **`send` 第一个参数到底是什么？** 官方原文：它是一个 **binding 名字**（如 `orders-out-0`），也可以是一个**还不存在的动态目的地**——如果该 binding 没预定义，StreamBridge 会自动创建它（动态目的地，缓存在内存，默认缓存 10 个，可用 `spring.cloud.stream.dynamic-destination-cache-size` 调）。**`send` 接收 `Object`**——你可以传 POJO（自动序列化）或 `Message` 对象。

#### 4.2.2 `StreamBridge` 的异步发送

默认 `send` 是**阻塞**的（用调用者线程）。要异步：

```java
streamBridge.setAsync(true);   // 之后所有 send 异步执行
```

> **可观测性提示**：异步发送会跨线程，影响链路追踪。若用了 Micrometer 追踪，需加 `io.micrometer:context-propagation` 依赖保持上下文传播。

#### 4.2.3 提前预创建 binding（推荐）

如果用 StreamBridge 但想启动时就建好 binding（而不是第一次 send 时懒创建），用：

```yaml
spring:
  cloud:
    stream:
      output-bindings: orders-out-0;notifications-out-0   # ▼ 分号分隔多个
```

#### 4.2.4 实战建议：给 StreamBridge 配上语义化的 destination

新手常困惑：`streamBridge.send("orders-out-0", order)` 发出去的消息，topic 名到底叫什么？答案是——**如果 `orders-out-0` 这个 binding 没配 destination，框架就把 `orders-out-0` 这个字符串本身当成 topic 名**（动态目的地）。于是你的 Kafka topic 真的叫 `orders-out-0`，名字很怪。

**干净的做法**：给 binding 配一个语义化的 destination，代码里用 binding 名发：

```yaml
spring:
  cloud:
    stream:
      bindings:
        orders-out-0:                 # ▼ binding 名（代码里 send 用它）
          destination: orders          # ▼ 真正的 topic 名（语义化）
      output-bindings: orders-out-0    # ▼ 启动时预创建
```

```java
streamBridge.send("orders-out-0", order);   // 实际发到 topic "orders"，不是 "orders-out-0"
```

这样：代码用 binding 名（`orders-out-0`，遵循命名约定），topic 名干净（`orders`，运维好认）。**生产环境推荐这种配法。**

### 4.3 Supplier vs StreamBridge 怎么选

| 场景 | 用哪个 |
|------|--------|
| 定时轮询数据源发消息（如每分钟读 DB 发增量） | `Supplier` + poller |
| 响应式流（`Supplier<Flux<T>>`，一次产生持续流） | 响应式 Supplier（第 8 章） |
| 由 HTTP 请求 / 业务事件触发发消息 | **`StreamBridge`** |
| 不确定、两者都沾边 | 优先 `StreamBridge`——它最灵活，生产最常用 |

---

## 第 5 章：消费者全解——Consumer、消费组、重试、死信

消费者是消息系统的"半边天"。这一章把消费端的**全部生产级问题**讲透。

### 5.1 基础 Consumer

```java
@Bean
public Consumer<Order> chargeOrder() {
    return order -> billingService.charge(order);
}
```

```yaml
spring:
  cloud:
    function:
      definition: chargeOrder
    stream:
      bindings:
        chargeOrder-in-0:
          destination: orders
          group: billing-group      # ▼ 消费组（见 5.2）
          content-type: application/json   # ▼ 自动把 JSON 反序列化成 Order 对象
```

注意：**`chargeOrder` 收到的是 `Order` 对象，不是 JSON 字符串**——框架根据 `content-type` 自动反序列化。这是 Spring Cloud Stream 的一大便利。

### 5.2 消费组（Consumer Group）——最容易误解的概念

**消费组是消息系统的一个核心机制**。先看问题：

> 假设订单消息发给库存服务，但库存服务部署了 **3 个实例**（扩容了）。一条订单消息来了，3 个实例**都会**收到吗？那扣库存不就扣了 3 次？

**消费组解决这个问题**：把 3 个库存实例放进**同一个消费组**（`group: inventory-group`），那么**一条消息只会被组内的一个实例消费**（负载均衡）。不同组（如 inventory-group 和 analytics-group）则**各自都能收到**全量消息（发布订阅）。

```
                      orders topic
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
   inventory-group   analytics-group   inventory-group
   (实例1收)         (收全量做分析)    (实例1和实例2分担，不重复)
```

**记住**：
- **同组 = 负载均衡**（一条消息组内只一个实例处理）。
- **不同组 = 各自全量**（每组都收到）。

配置就是 `group: 组名`。不配 group 会怎样？Kafka 下每次重启都用随机组，会重复消费历史——**生产环境一定要配 group**。

### 5.3 重试（Retry）——消息处理失败怎么办

消费者处理消息时可能抛异常（比如 DB 暂时连不上）。Spring Cloud Stream 提供**自动重试**。配置（官方校验，在 `consumer` 下）：

```yaml
spring:
  cloud:
    stream:
      bindings:
        chargeOrder-in-0:
          consumer:
            max-attempts: 3                 # ▼ 最大尝试次数（含首次），默认 3
            back-off-initial-interval: 1000 # ▼ 首次重试等待 1000ms，默认 1000
            back-off-multiplier: 2.0        # ▼ 每次等待 ×2（指数退避），默认 2.0
            back-off-max-interval: 10000    # ▼ 单次最长等待 10000ms，默认 10000
```

**发生了什么**：消息处理失败 → 等 1s 重试 → 失败 → 等 2s 重试 → 失败 → 等 4s 重试... 直到 `max-attempts` 用尽。

> **重要限制（官方明确）**：这套框架级重试**只对命令式函数（`Consumer<T>`）生效，对响应式函数（`Consumer<Flux<T>>`）无效**。因为响应式函数是"初始化一次"的流，框架拿不到每条消息的处理边界。响应式要自己用 Reactor 的 `.retry()` / `.onErrorResume()`。第 8 章详讲。

### 5.4 死信（Dead Letter）——重试用尽后的兜底

重试 3 次还失败的消息怎么办？直接丢掉太危险（比如订单扣款失败就丢了，钱就没了）。**死信机制**把这种"处理不了的消息"发到一个**专门的死信目的地**，留待人工介入或后续修复。

Kafka binder 下，开启死信（DLT，Dead Letter Topic）：

```yaml
spring:
  cloud:
    stream:
      bindings:
        chargeOrder-in-0:
          consumer:
            max-attempts: 3
      kafka:
        bindings:
          chargeOrder-in-0:
            consumer:
              enable-dlq: true               # ▼ Kafka binder 专属：开启死信 topic
              dlq-name: orders-dlq           # ▼ 死信 topic 名（不配则自动命名）
```

**发生了什么**：重试用尽 → 消息被发到 `orders-dlq` 这个 topic。你可以单独起一个消费者处理它（发告警、记库、人工修复）。

> **Kafka vs RabbitMQ 的死信配置不同**：Kafka binder 用 `enable-dlq`（在 `spring.cloud.stream.kafka.bindings.<name>.consumer` 下）；RabbitMQ binder 用 `republish-to-dlq` + `auto-bind-dlq`（在 `spring.cloud.stream.rabbit.bindings.<name>.consumer` 下）。这正是 Binder 抽象"中间件差异"的地方——配置项不同，但**业务函数代码完全一样**。

### 5.5 自定义错误处理

除了重试+死信，你也可以**自己接管错误**。消息处理失败时，框架会把异常包装成 `ErrorMessage` 发到一个**错误通道**（error channel），名字是 `<destination>.<group>.errors`（如 `orders.billing-group.errors`）。这个错误通道还会桥接到 Spring Integration 的全局 `errorChannel`。

> **正确订阅方式**：错误通道是 Spring Integration 的 `MessageChannel`，**不是** binder destination。所以**不能用** `destination: orders.billing-group.errors` 配一个 `Consumer` 的方式去订阅（那是把错误通道当成普通 topic，方向错了）。正确做法是用 `@ServiceActivator` 注解，通过 `inputChannel` 指定错误通道名（官方校验过的写法）：

```java
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
public class ChargeErrorHandler {

    // ▼ 订阅特定错误通道：<destination>.<group>.errors
    @ServiceActivator(inputChannel = "orders.billing-group.errors")
    public void handleError(Message<?> errorMessage) {
        // errorMessage 的 payload 是 ErrorMessage，其 getPayload() 是原始异常
        Throwable cause = ((org.springframework.messaging.support.ErrorMessage) errorMessage).getPayload();
        System.err.println("订单计费失败：" + cause.getMessage());
        // 记库、告警、人工补偿...
    }
}
```

```java
// ▼ 或者订阅全局 errorChannel（所有 binding 的错误都汇聚到这里）
@ServiceActivator(inputChannel = "errorChannel")
public void handleAnyError(Message<?> errorMessage) {
    // 处理所有错误（可从消息头判断来源 binding）
}
```

> **关键**：`@ServiceActivator` 来自 Spring Integration（`org.springframework.integration.annotation.ServiceActivator`），是订阅内部消息通道的标准方式，和 5.1 的 `Consumer` bean（接 binder destination）是两回事，别混淆。**配置里不需要为错误通道加 binding**——错误通道由框架自动创建，你只需用 `@ServiceActivator` 订阅它的名字。

---

## 第 6 章：Kafka Binder 详解——topic/分区/offset 如何映射

前面讲的抽象落到 Kafka 上，要理解几件具体的事。这一章让你从"会用"到"会调优"。

### 6.1 destination → topic

`destination: orders` 直接对应 Kafka 的一个 topic（不存在时，binder 默认会自动创建）。

### 6.2 group → consumer group

`group: billing-group` 对应 Kafka 的 consumer group。这就是 5.2 讲的消费组，决定负载均衡 vs 发布订阅。

### 6.3 offset——消费进度的托管

Kafka 用 **offset** 记录"消费到哪了"。Spring Cloud Stream 默认**自动提交 offset**（消息处理成功后提交）。这意味着：
- 消费者重启，从上次提交的 offset 续读，**不丢不重**（at-least-once，至少一次）。
- 处理失败的消息（未提交 offset）会**重投递**。

> **这是 at-least-once 语义，不是 exactly-once**。意味着同一条消息**可能被消费多次**（比如处理成功但提交 offset 前崩溃）。所以你的消费者**必须幂等**（同一条消息处理多次结果一致）。第 9 章讲幂等设计。

### 6.4 分区（Partitioning）——保序与并行

Kafka 的 topic 分成多个 **partition**。分区有两个意义：

1. **保序**：同一个 key 的消息进同一个 partition，**保证顺序**。比如同一个订单的"创建→支付→发货"事件要按顺序，就把 orderId 作为分区 key。
2. **并行**：一个消费组内，每个 partition 只能被一个消费者消费——所以 partition 数 ≥ 消费者数才能并行。

**在 Spring Cloud Stream 里配置分区**：

```yaml
spring:
  cloud:
    stream:
      bindings:
        orders-out-0:               # 生产者
          destination: orders
          producer:
            partition-count: 6       # ▼ topic 分几个区
            partition-key-expression: headers['partitionKey']  # ▼ 用哪个值算分区
        chargeOrder-in-0:            # 消费者
          consumer:
            # 消费组内多个实例会自动分担分区
```

生产时指定分区 key（用 `Message` 带 header）：

```java
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

streamBridge.send("orders-out-0",
    MessageBuilder.withPayload(order)
        .setHeader("partitionKey", order.getUserId())   // ▼ 同 userId 进同分区，保序
        .build());
```

### 6.5 常用 Kafka binder 配置（官方校验）

```yaml
spring:
  cloud:
    stream:
      kafka:
        binder:                       # ▼ binder 级配置（全局）
          brokers: localhost:9092     # ▼ Kafka 地址（也可以用 spring.kafka.bootstrap-servers）
          auto-create-topics: true    # ▼ topic 不存在时自动创建（默认 true）
          min-partition-count: 1      # ▼ 最小分区数
        bindings:
          chargeOrder-in-0:
            consumer:
              enable-dlq: true        # ▼ 死信 topic
              auto-offset-reset: latest  # ▼ 新消费组从哪开始读（latest/earliest）
          orders-out-0:
            producer:
              sync: false             # ▼ 是否同步发送
```

---

## 第 7 章：RabbitMQ Binder 详解——体会"换中间件不改代码"

这一章的核心不是教你 RabbitMQ，而是**让你亲眼看到"换中间件，业务代码一个字不改"**——这是 Spring Cloud Stream 最激动人心的能力。

### 7.1 换依赖

把 Kafka starter 换成 RabbitMQ starter：

```xml
<!-- 换成 RabbitMQ binder starter -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-stream-rabbit</artifactId>
</dependency>
```

### 7.2 业务代码——完全不变

```java
// 这段和第 1 章一模一样，一个字没改！
@Bean
public Consumer<String> logMessage() {
    return message -> System.out.println("收到消息：" + message);
}
```

### 7.3 配置——只改连接信息

```yaml
spring:
  cloud:
    function:
      definition: logMessage
    stream:
      bindings:
        logMessage-in-0:
          destination: hello-topic    # ▼ 同样的 destination 名
          group: log-group
  rabbitmq:                           # ▼ 换成 RabbitMQ 连接
    host: localhost
    port: 5672
    username: guest
    password: guest
```

**见证奇迹**：你的 Java 代码**一个字符都没改**，从 Kafka 切到了 RabbitMQ。这就是 Binder 抽象的力量。

> **destination 在 RabbitMQ 里是什么**：它对应一个 **exchange**（名字就是 destination 名），binder 会自动建一个同名的 queue（`destination.group`）并绑定。这些细节由 RabbitMQ Binder 自动处理，你不用管。

### 7.4 多 Binder 并存

更酷的场景：**一个应用同时连 Kafka 和 RabbitMQ**——比如从 Kafka 收、发到 RabbitMQ。

```java
@Bean
public Function<String, String> bridge() {
    return s -> s;   // 收啥发啥
}
```

```yaml
spring:
  cloud:
    stream:
      bindings:
        bridge-in-0:
          destination: from-kafka
          binder: kafka              # ▼ 输入用 Kafka binder
        bridge-out-0:
          destination: to-rabbit
          binder: rabbit             # ▼ 输出用 RabbitMQ binder
      binders:
        kafka:
          type: kafka
          environment:
            spring.kafka.bootstrap-servers: localhost:9092
        rabbit:
          type: rabbit
          environment:
            spring.rabbitmq.host: localhost
```

这就是架构师级的用法——**消息在不同中间件间流转，业务函数浑然不觉**。

---

## 第 8 章：进阶——响应式、批处理、多输入输出、函数组合

基础够用了。这一章是"高级玩家"的内容，也是架构师必须知道的。

### 8.1 响应式函数（Reactive）

Spring Cloud Function 底层是 Project Reactor，所以你可以直接用 `Flux`/`Mono`：

```java
import reactor.core.publisher.Flux;
import java.util.function.Function;

@Bean
public Function<Flux<String>, Flux<String>> reactiveUpper() {
    return flux -> flux.map(String::toUpperCase);
}
```

**响应式的两个重要真相（官方明确，别踩坑）**：

1. **框架级重试（5.3 的 `max-attempts`）对响应式函数无效**。因为响应式函数是"初始化一次返回 Flux"的模式，框架对流的内部没有每消息的可见性。响应式要用 Reactor 自己的 `.retryWhen()`、`.onErrorResume()`。
2. **背压（backpressure）只在响应式 Binder 下才真正生效**。如果你用的是普通 Kafka/RabbitMQ binder（非响应式版），用 `Flux` 只是享受 API 的便利，**拿不到真正的背压**——因为底层客户端不是响应式的。要真背压得用**响应式 Kafka binder**（Reactive Kafka Binder，单独的 artifact）。

### 8.2 批处理（Batch Consumer）

一个 `Consumer<List<T>>` 可以一次处理一整批消息：

```java
@Bean
public Consumer<List<Person>> findFirst() {
    return persons -> System.out.println("收到 " + persons.size() + " 条");
}
```

```yaml
spring:
  cloud:
    stream:
      bindings:
        findFirst-in-0:
          consumer:
            batch-mode: true       # ▼ 开启批处理
```

> **用途**：批量入库（一次 insert 一批，比逐条快得多）、批量聚合统计。注意：批中某条转换失败会缩减批大小，框架提供 `MessageConverterHelper` 接口处理这种情况。

### 8.3 多输入输出

一个函数可以消费多个输入流、或产生多个输出流。用 Reactor 的 `Tuple`：

```java
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import java.util.function.Function;

// ▼ 两个输入（String + Integer），合并成一个输出
@Bean
public Function<Tuple2<Flux<String>, Flux<Integer>>, Flux<String>> gather() {
    return tuple -> {
        Flux<String> strings = tuple.getT1();
        Flux<String> ints = tuple.getT2().map(String::valueOf);
        return Flux.merge(strings, ints);
    };
}
// 绑定名：gather-in-0、gather-in-1、gather-out-0
```

```java
// ▼ 一个输入，分成两个输出（奇偶分流）
@Bean
public Function<Flux<Integer>, Tuple2<Flux<String>, Flux<String>>> scatter() {
    return flux -> {
        // ... 拆成 even 和 odd 两个流
        return Tuples.of(evenFlux, oddFlux);
    };
}
// 绑定名：scatter-in-0、scatter-out-0、scatter-out-1
```

### 8.4 函数组合（Functional Composition）

用 `|`（管道）把多个简单函数串成一个：

```java
@Bean
public Function<String, String> upper() { return String::toUpperCase; }

@Bean
public Function<String, String> wrap() { return s -> "[" + s + "]"; }
```

```yaml
spring:
  cloud:
    function:
      definition: upper|wrap     # ▼ 管道：先 upper 再 wrap
```

输入 `hi` → `upper` 变 `HI` → `wrap` 变 `[HI]`。**组合后的绑定名会是 `upper|wrap-in-0`，很长，可以用 `spring.cloud.stream.function.bindings` 重命名**：

```yaml
spring:
  cloud:
    stream:
      function:
        bindings:
          upper|wrap-in-0: myInput   # ▼ 给这个长绑定名起短名
      bindings:
        myInput:
          destination: my-topic
```

### 8.5 多个独立函数并存

一个应用里有多个函数，用 `;` 分隔：

```yaml
spring:
  cloud:
    function:
      definition: upper;reverse     # ▼ 两个独立函数
```

---

## 第 9 章：生产级——可观测、分区、幂等、事务

到这一章，你已经"会用"了。下面是"上线后不出事"的部分。

### 9.1 幂等性——消息系统的第一准则

前面说过，消息是 **at-least-once**（至少一次），即**可能重复投递**。所以消费者**必须幂等**——同一条消息处理 N 次，效果和 1 次一样。

**实现幂等的通用方法**：用消息的唯一 ID 做去重。

```java
@Bean
public Consumer<Order> chargeOrder() {
    return order -> {
        // ▼ 先查幂等表：这个 order 处理过吗？
        if (idempotencyRepository.exists(order.getId())) {
            return;   // 处理过，直接跳过（幂等）
        }
        billingService.charge(order);
        idempotencyRepository.save(order.getId());   // 标记已处理
    };
}
```

> **和 35 号文档的呼应**：35 号文档第 6 章讲的"幂等键（Idempotency-Key）"就是这个思想——用唯一键保证"同一操作只执行一次"。在消息系统里，幂等是底线，不是优化。

### 9.2 分区与保序

如果业务要求"同一实体的消息按顺序处理"（如同一账户的扣款），必须用**分区**（6.4 讲过）——同一 key 进同一 partition，单 partition 内天然有序。**但注意**：保序和并行是矛盾的（一个 partition 只能一个消费者），要按业务权衡。

### 9.3 可观测性（Observability）

生产环境必须能"看到"消息在流动。Spring Cloud Stream 内建 Micrometer 支持：

```xml
<!-- 自动接入 Micrometer + actuator -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

启用后，框架自动上报消息处理的**指标**（处理量、耗时）和**链路追踪**（trace，能看到一条消息从生产到消费的完整路径）。配合 Zipkin / Tempo 可视化。

### 9.4 事务（Transactional Producer）

Kafka 支持事务性生产（多条消息要么全成功要么全失败）。Kafka binder 配置：

```yaml
spring:
  cloud:
    stream:
      kafka:
        binder:
          transaction:
            transaction-id-prefix: tx-   # ▼ 开启事务性生产
```

开启后，binder 用 Kafka 事务发消息，配合 `@Transactional` 可实现"DB 操作 + 发消息"的原子性。

### 9.5 健康检查

Spring Cloud Stream 自动注册健康指标（`/actuator/health`），会检查 binder 是否连得上中间件。Kafka/RabbitMQ 挂了，健康检查会显示 DOWN。

---

## 第 10 章：架构师视角——该不该用、怎么取舍

学完全部机制，现在站在架构师的高度看：**什么时候用 Spring Cloud Stream，什么时候不用**。

### 10.1 Spring Cloud Stream 的真正价值

它解决的核心问题是 **"中间件无关性"**。如果你**确定永远只用一种中间件**（比如铁定只用 Kafka），那它的抽象收益有限——直接用 `spring-kafka` 反而更直接、性能更好、调试更简单（少一层抽象）。

它的价值在这些场景最大：
1. **可能换中间件**（多云、客户定制、技术演进）。
2. **多中间件并存**（一个应用桥接 Kafka 和 RabbitMQ）。
3. **团队要统一消息编程模型**（降低学习成本，新人一套模型通吃）。

### 10.2 和 35 号文档手写 Kafka 的对比

| 维度 | 35 号文档手写 Kafka | Spring Cloud Stream |
|------|--------------------|--------------------|
| 中间件耦合 | 强（绑死 Kafka） | 弱（换中间件只改配置） |
| 学习成本 | 低（直接 Kafka API） | 中（要学抽象） |
| 调试 | 直接（看到的就是 Kafka） | 多一层（要懂抽象才能定位） |
| 性能 | 略好（无抽象开销） | 略有抽象开销（通常可忽略） |
| 灵活性 | 高（能用 Kafka 所有特性） | 中（抽象可能屏蔽某些特性，需 binder 专属配置补） |
| 适用 | 只用 Kafka、要极致控制 | 可能换/多中间件、要统一模型 |

> **架构师判断**：35 号文档用手写 Kafka 是**教学最佳选择**（让读者聚焦"消息总线"概念，不被框架抽象分心）。但**生产环境**，如果系统会长期演进、可能涉及多中间件，应该上 Spring Cloud Stream。

### 10.3 不要用的场景

- **简单同步 RPC**：要立即拿结果，别用消息（用 HTTP/Feign/gRPC）。
- **超低延迟**：消息系统天然有毫秒级延迟，亚毫秒场景别用。
- **单一中间件 + 需要极致调优**：抽象层会限制你对 Kafka 细节的掌控。
- **消息量极小**：一天几十条消息，一个定时任务就搞定，引全套框架是过度设计。

### 10.4 架构师的核心思维

学完本文，你应该建立的判断力：

1. **先有解耦需求，再选技术**。不是"学了个新框架就要用"，而是"业务有解耦/多消费者/换中间件需求，才用它"。
2. **理解抽象的代价**。每一层抽象都是用"灵活性/性能"换"开发效率/可移植性"。架构师要算清这笔账。
3. **消息语义是底线**。at-least-once → 消费者必须幂等。exactly-once → 要事务。这两个不搞清，上线必出数据问题。
4. **可观测性不是可选项**。分布式消息系统，没有链路追踪和指标，出问题就是黑盒。

---

## 附录 A：API 签名校验表

> 所有签名对照[官方文档](https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/producing-and-consuming-messages.html)逐字校验。版本：4.2.x / 4.3.x / 5.0.2。

### A.1 函数式接口（`java.util.function`）

```java
Supplier<T>       // T get();              ——生产者，无入参
Function<T,R>     // R apply(T t);         ——处理器，一进一出
Consumer<T>       // void accept(T t);     ——消费者，一进无出
```

### A.2 StreamBridge.send() 全签名

```java
boolean send(String bindingName, Object data);
boolean send(String bindingName, Object data, MimeType outputContentType);
boolean send(String bindingName, String binderType, Object data);
boolean send(String bindingName, String binderType, Object data, MimeType outputContentType);
void setAsync(boolean async);    // 切换异步发送
```
> `StreamBridge` 实现了 `StreamOperations` 接口，可按该接口注入（便于单元测试 mock）。

### A.3 绑定命名约定

```
<funcName>-in-<index>     // 输入（消费者/处理器入端）
<funcName>-out-<index>    // 输出（生产者/处理器出端）
```

### A.4 Poller 配置（`spring.integration.poller.*`）

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `fixed-delay` | 1000 | 固定轮询间隔（ms） |
| `max-messages-per-poll` | 1 | 每次轮询最多发几条 |
| `cron` | 无 | cron 触发 |
| `initial-delay` | 0 | 初始延迟（ms） |

### A.5 消费者重试（`spring.cloud.stream.bindings.<name>.consumer.*`）

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `max-attempts` | 3 | 最大尝试次数（含首次） |
| `back-off-initial-interval` | 1000 | 首次重试等待（ms） |
| `back-off-multiplier` | 2.0 | 退避乘数 |
| `back-off-max-interval` | 10000 | 单次最长等待（ms） |

> ⚠️ 仅对命令式函数生效，响应式函数无效。

### A.6 死信配置

- **Kafka**：`spring.cloud.stream.kafka.bindings.<name>.consumer.enable-dlq=true` + `dlq-name`
- **RabbitMQ**：`spring.cloud.stream.rabbit.bindings.<name>.consumer.auto-bind-dlq=true` + `republish-to-dlq=true`

---

## 附录 B：完整可跑项目

一个"HTTP 触发 → 发消息 → 消费打印 + 重试 + 死信"的完整最小项目。

### B.1 pom.xml（关键部分）

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2024.0.0</version>
            <type>pom</type><scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-stream-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

### B.2 DemoStreamApplication.java

```java
package com.example.stream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;

import java.util.function.Consumer;

@SpringBootApplication
@RestController
public class DemoStreamApplication {

    private final StreamBridge bridge;
    public DemoStreamApplication(StreamBridge bridge) { this.bridge = bridge; }

    public static void main(String[] args) { SpringApplication.run(DemoStreamApplication.class, args); }

    // ▼ HTTP 触发发消息
    @PostMapping("/send")
    public String send(@RequestBody String body) {
        bridge.send("demo-out-0", body);
        return "sent: " + body;
    }

    // ▼ 消费者（故意抛异常演示重试）
    @Bean
    public Consumer<String> demo() {
        return msg -> {
            System.out.println("处理：" + msg);
            if (msg.contains("error")) {
                throw new RuntimeException("模拟失败，触发重试/死信");
            }
            System.out.println("成功：" + msg);
        };
    }
}
```

### B.3 application.yaml

```yaml
spring:
  cloud:
    function:
      definition: demo
    stream:
      bindings:
        demo-in-0:
          destination: demo-topic
          group: demo-group
          content-type: text/plain
          consumer:
            max-attempts: 3
            back-off-initial-interval: 1000
      kafka:
        bindings:
          demo-in-0:
            consumer:
              enable-dlq: true
              dlq-name: demo-dlq
  kafka:
    bootstrap-servers: localhost:9092
```

### B.4 跑起来

```bash
# 起 Kafka（见第 1 章）
# 跑应用
./mvnw spring-boot:run

# 发正常消息
curl -X POST -d "hello" http://localhost:8080/send
# → 控制台：处理：hello / 成功：hello

# 发会失败的消息（演示重试+死信）
curl -X POST -d "error-test" http://localhost:8080/send
# → 控制台：处理：error-test（重试 3 次后失败）
# → 消息进入 demo-dlq topic
docker exec -it kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic demo-dlq --from-beginning
# → 能看到 error-test 这条死信
```

---

## 附录 C：版本对照与踩坑手册

### C.1 版本对照表（已校验）

| Spring Boot | Spring Cloud | Spring Cloud Stream |
|-------------|-------------|--------------------|
| 3.2.x / 3.3.x | 2023.0.x (Leyton) | 4.1.x |
| 3.4.x | 2024.0.x (Moorgate) | 4.2.x |
| 3.5.x | 2025.0.x (Northfields) | 4.3.x |
| **4.0.x / 4.1.x** | **2025.1.x (Oakwood)** | **5.0.x** |

> **本仓库 `demo01` 用的是 Boot 4.1.0** → 用 **Spring Cloud 2025.1.x (Oakwood) + Spring Cloud Stream 5.0.x**。

### C.2 Boot 4 项目接入方式

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2025.1.0</version>   <!-- Oakwood：支持 Boot 4 -->
            <type>pom</type><scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### C.3 常见踩坑

#### 坑 1：函数没被绑定（消息发不进/收不到）

**原因**：忘了配 `spring.cloud.function.definition`，或配的名字和 bean 名不一致。
**解决**：`definition` 的值必须和 `@Bean` 的方法名**完全一致**（大小写敏感）。多函数用 `;`，组合用 `|`。

#### 坑 2：binding 名写错

**原因**：把 `demo-in-0` 写成了 `demoInput` 之类自定义名。
**解决**：binding 名必须遵循 `<func>-in-0` / `<func>-out-0` 约定。要自定义用 `spring.cloud.stream.function.bindings.<原名>=<新名>`。

#### 坑 3：响应式函数重试不生效

**原因**：`max-attempts` 只对命令式函数生效。
**解决**：响应式函数自己用 `.retryWhen()`、`.onErrorResume()`。

#### 坑 4：消息重复消费

**原因**：at-least-once 语义 + 消费者不幂等。
**解决**：消费者**必须幂等**（用幂等键/唯一约束去重）。这是铁律。

#### 坑 5：topic 自动创建被关

**原因**：某些环境 Kafka 禁止客户端自动建 topic。
**解决**：运维侧预先建好 topic，或确认 `spring.cloud.stream.kafka.binder.auto-create-topics=true` 且 Kafka 允许。

#### 坑 6：用了 `@StreamListener` / `@EnableBinding`（旧注解）

**原因**：照着老教程抄。
**解决**：全面改用函数式模型（`@Bean Consumer/Function/Supplier` + `spring.cloud.function.definition`）。

---

## 配套学习资料

- [Spring Cloud Stream 官方参考文档](https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream/producing-and-consuming-messages.html)（权威，英文）
- [35-管数分离实战](../../tutorials/spring-ai-2.0/35-管数分离实战-从Sinks到Kafka演进.md) 第 10 章（手写 Kafka，对比理解 Stream 的价值）
- [Kafka 核心概念与 Spring Boot 实战](../Kafka专题/01-Kafka核心概念与SpringBoot实战.md)（Kafka 基础，学 Kafka Binder 前可补）
- [Reactor 响应式入门](../Reactor响应式编程/01-Reactor响应式入门.md)（学响应式函数前补 Flux/Mono）

---

## 下一步：继续进阶

学完本文（0-10 章），你已经"会用"Spring Cloud Stream 了。如果你想达到**真正的架构师水平**——生产调优、流式计算、响应式真相、事件驱动架构——继续读进阶篇：

➡️ **[Spring Cloud Stream 进阶实战](./02-Spring-Cloud-Stream进阶实战.md)**

进阶篇专门为**Kafka 零基础**的人设计（第 1 章补 Kafka 地基），并包含 2025 年的重要变化（响应式 Kafka Binder 已废弃），带你从"会用"走到"会设计事件驱动系统"。

---

> **写在最后**：这份文档从"它是什么"讲到"架构师怎么取舍"，每一步都配了可跑的代码和已校验的 API。如果你完整跟下来，你拥有的不只是"会用 Spring Cloud Stream"，而是**一套关于"消息如何在分布式系统里可靠、解耦、可移植地流动"的思维模型**——发布订阅、消费组、幂等、重试、死信、分区、中间件抽象。这些模式在你日后做任何事件驱动系统时都会反复用到。祝你学习顺利。
