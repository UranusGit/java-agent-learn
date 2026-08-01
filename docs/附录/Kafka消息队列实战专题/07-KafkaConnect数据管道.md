# Kafka Connect：把数据搬进/搬出 Kafka（数据管道专题）

> **这份文档是什么**：[Kafka 消息队列实战专题](./README.md) 的第 07 篇，**数据集成管道（data pipeline）**。前面 01-06 教的都是**在应用进程里**发收消息（`KafkaTemplate` 发 / `@KafkaListener` 收，全用 `spring-boot-starter-kafka`）。本篇换一个视角：把**"把数据搬进/搬出 Kafka"**这件事，从你的业务进程里**抽出来**，交给一个**独立运行的数据集成框架**——Kafka Connect。
>
> **和 06 的关系**：[06 Kafka 可靠性专题](./06-Kafka可靠性专题.md) 讲的是**应用内**的消息可靠性（at-least-once 语义、消费端去重幂等、重试与 DLT 死信）。本篇的 Kafka Connect 是一条**批量的、独立进程的管道**，但可靠性问题**一模一样**：Source 重读会重复投递、Sink 重放会重复落库，你仍然要靠**幂等去重**兜底。所以 06 的"幂等表 / 去重键"思路，本篇第 6 章会在**管道语境**下再讲一遍——**先读 06 再读本篇，理解会顺很多**。
>
> **和 Debezium 的关系**：[Debezium-CDC 专题](../Debezium-CDC实战/README.md) 里的 Debezium 就是**跑在 Kafka Connect 里的一款 connector**——它把数据库 binlog/WAL 搬进 Kafka。本篇把 Kafka Connect 这个**框架**讲透，你会真正理解 Debezium 那篇里的 `connect` 容器、REST 注册、SMT 到底是什么。
>
> **写给谁**：读完 01-06、能熟练用 `KafkaTemplate`/`@KafkaListener` 的人。本篇**编程为主**：重点不是"怎么配现成的 connector"，而是**怎么写一个自定义 connector**（Source / Sink 各一个完整可跑的例子）。
>
> **版本前提（已校验）**：主项目用 **Spring Boot 4.1.0 + `spring-boot-starter-kafka`**（版本由 Boot BOM 托管，`kafka-clients` 为 4.0.x）。Kafka Connect 是**独立于 Spring 的框架**——用 **Apache Kafka 4.x 发行版自带的 `connect-standalone`/`connect-distributed`**，或 **Confluent 的 `confluentinc/cp-kafka-connect` 镜像**。写 connector 只需引入 `org.apache.kafka:connect-api`（示例用 `4.0.0`，与仓库的 kafka-clients 同版本线）。

---

## 目录

- [第 1 章：Kafka Connect 是什么](#第-1-章kafka-connect-是什么)
- [第 2 章：跑现成 connector 快速上手](#第-2-章跑现成-connector-快速上手)
- [第 3 章：写自定义 Source connector](#第-3-章写自定义-source-connector)
- [第 4 章：写自定义 Sink connector](#第-4-章写自定义-sink-connector)
- [第 5 章：converter 与 schema](#第-5-章converter-与-schema)
- [第 6 章：生产要点——幂等、死信、多 worker](#第-6-章生产要点幂等死信多-worker)
- [附录：本篇 API 速查表](#附录本篇-api-速查表)

---

## 第 1 章：Kafka Connect 是什么

### 1.1 一句话

> **Kafka Connect 是 Kafka 官方的"数据集成框架"**：它把"从某个系统读数据写进 Kafka"（Source）和"从 Kafka 读数据写到某个系统"（Sink）做成**可配置、可扩展、可重试的管道**，并且这些管道**跑在独立的 worker 进程里，不占你的业务进程**。

你前面写的 `kafkaTemplate.send(...)` 是"业务事件随手发一条"。Connect 是另一码事：**批量、异构源、长时间跑、断点续传**。典型场景：

- 把 MySQL / PostgreSQL 的整张表**批量**同步到 Kafka（再用 Flink/Spark/Kafka Streams 消费）→ 数仓前置管道。
- 把日志文件、云存储（S3）、NoSQL 里的数据**持续**灌进 Kafka。
- 反过来：把 Kafka 里的事件**落地**到 S3 / HDFS / Elasticsearch / 数据库 / 文件，供离线分析。

和 01-06 的定位对比：

| | 01-06 直接 Kafka | 本篇 Kafka Connect |
|---|---|---|
| 形态 | 在**业务进程里**发/收消息 | **独立 worker 进程**里的管道 |
| 场景 | 业务事件（订单、库存……） | 批量数据搬移 / 异构系统集成 |
| 你写什么 | `KafkaTemplate` / `@KafkaListener` | **`SourceTask` / `SinkTask`**（本篇重点） |
| 可靠性 | 06 讲的消费端幂等、DLT | 第 6 章：connector 的 offset 断点续传、死信、幂等 |

### 1.2 架构：六个角色一张图

```
                    ┌───────────────────────────── Kafka Connect 集群 ─────────────────────────────┐
                    │                                                                              │
   数据源            │   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                   │
  ┌────────┐        │   │  Worker 1    │    │  Worker 2    │    │  Worker 3    │                   │
  │ DB/文件 │───────┼──▶│  Source Task │    │  Source Task │    │   Sink Task  │──────┐            │
  │ /HTTP/  │        │   │  （读源数据）│    │  （读源数据）│    │  （写目标）  │      │            │
  │ NoSQL   │        │   └──────────────┘    └──────────────┘    └──────────────┘      │            │
  └────────┘        │        │ converter / SMT                              │          │            │
                    │        ▼                                              ▼          ▼            │
                    │   ┌───────────────────────────────────────────────────────────────┐          │
                    │   │                          Kafka                               │          │
                    │   └───────────────────────────────────────────────────────────────┘          │
                    │                                                                              │
                    └──────────────────────────────────────────────────────────────────────────────┘
                                          ▲                          │
                               （Source：搬进 Kafka）      （Sink：搬出 Kafka）
                                          │                          ▼
                                        REST API                  ┌─────────┐
                                   http://worker:8083            │ ES/DB/文件 │
                                   注册/管理 connector            └─────────┘
```

**六个角色，各司其职**：

| 角色 | 是什么 | 干什么 |
|---|---|---|
| **Connect cluster** | 一组 worker 组成的集群 | 分布式跑管道、自动 rebalance、高可用 |
| **Worker** | 一个 JVM 进程 | 跑 connector 的运行时；standalone 单进程 / distributed 多进程 |
| **Connector** | 一个 Java 类（插件） | 定义"连什么系统、怎么拆任务"（`Connector` 抽象类） |
| **Task** | 实际干活的线程 | 真正读写数据（`SourceTask` / `SinkTask`），一个 connector 拆成 N 个 task 并行 |
| **Converter** | 序列化/反序列化插件 | 把 Connect 内部数据模型（带 schema）和 Kafka 里的 bytes 互转（JSON/AVRO） |
| **Schema** | 数据类型的描述 | Connect 内部每条记录都带 schema（`Schema` 接口），converter 靠它决定怎么序列化 |

另外还有个**隐藏角色 SMT**（Single Message Transform，单消息转换）：在 Source/Sink 的 task 和 converter 之间**改写每条消息**（比如 Debezium 那篇的 `ExtractNewRecordState` 把 `after` 字段抽出来）。它不是本篇重点，但你在配 Debezium connector 时见过它（[Debezium-CDC 专题](../Debezium-CDC实战/README.md) 第 4 章）。

### 1.3 Source vs Sink

- **Source connector（源连接器）**：把数据**从外部系统搬进 Kafka**。核心是你实现 `SourceTask.poll()`，返回一批 `SourceRecord`（每条 = 一条要发进 Kafka 的消息）。
- **Sink connector（汇连接器）**：把数据**从 Kafka 搬到外部系统**。核心是你实现 `SinkTask.put(Collection<SinkRecord>)`，拿到一批 Kafka 消息，写进目标系统。

```
  DB ─▶[Source Task]─▶ Kafka ─▶[Sink Task]─▶ ES / 文件 / 数仓
```

### 1.4 和"直接用 KafkaTemplate 发"的区别

| 维度 | `KafkaTemplate.send()` | Kafka Connect |
|---|---|---|
| 进程 | 业务进程内 | **独立 worker 进程**，业务挂了管道照跑 |
| 触发 | 业务代码主动调 | **常驻任务**，按 `poll()` 自动拉取，天生"持续同步" |
| 断点续传 | 自己管（06 的 offset 手动管理） | **框架管**：SourceTask 返回 `sourceOffset`，Connect 自动记到 offset topic，重启从断点续传 |
| 并行扩展 | 自己起多线程/多实例 | **tasks.max + 多 worker**，框架自动 rebalance |
| 适合 | 业务事件、请求驱动 | **批量数据搬移、异构源、无人值守的长管道** |
| 学习成本 | 低（几行 API） | 高（Connector/Task/Converter/SMT 一套模型） |

> **怎么选**：业务事件（订单创建了、库存扣了）→ 继续用 01-06 的 `KafkaTemplate`/`@KafkaListener`。**"把某系统的一堆数据持续搬进/搬出 Kafka"**（表同步、日志采集、落地数仓）→ Kafka Connect。两者不是竞争，**经常同台**：Connect 把数据搬进 Kafka，业务用 `@KafkaListener` 消费。

### 1.5 两种运行模式

| 模式 | 进程 | 适合 |
|---|---|---|
| **standalone** | 单进程，一个 worker | 本地开发、单机验证（本篇第 2、3、4 章用它，最直观） |
| **distributed** | 多进程，组成集群 | 生产：多 worker 高可用、自动 rebalance、多 connector（第 6 章） |

### 1.6 本章验证：起一个 Connect 并看它活着

```bash
# 用一个 Kafka + Connect 的 docker-compose（完整版在第 2 章 2.1 给出）
docker-compose up -d

# Connect 的 REST API 活着
curl http://localhost:8083/
# {"version":"7.x.0","commit":"...","kafka_cluster_id":"..."}

# 当前没有任何 connector
curl http://localhost:8083/connectors
# []
```

看到 `version`/`kafka_cluster_id` 返回 JSON，Connect worker 就起来了。第 2 章开始往里面塞 connector。

---

## 第 2 章：跑现成 connector 快速上手

先用 **Apache Kafka 发行版自带的两个现成 connector**（`FileStreamSourceConnector` 把文件行搬进 Kafka、`FileStreamSinkConnector` 把 Kafka 消息写进文件）把管道跑通，建立"数据流动"的直觉，第 3、4 章再写自己的。

### 2.1 环境：Kafka + Connect

**方案 A（推荐，快）**：docker-compose 起 Kafka + `cp-kafka-connect`。

```yaml
# docker-compose.yml
version: "3"
services:
  kafka:
    image: confluentinc/cp-kafka:latest
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9092
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      CLUSTER_ID: demo-connect-cluster-1
    ports: ["9092:9092"]

  connect:
    image: confluentinc/cp-kafka-connect:latest
    depends_on: [kafka]
    environment:
      BOOTSTRAP_SERVERS: kafka:9092
      GROUP_ID: connect-cluster
      CONFIG_STORAGE_TOPIC: connect-configs        # ▼ 三个内部 topic（distributed 模式必需）
      OFFSET_STORAGE_TOPIC: connect-offsets
      STATUS_STORAGE_TOPIC: connect-status
      CONNECT_KEY_CONVERTER: org.apache.kafka.connect.json.JsonConverter
      CONNECT_VALUE_CONVERTER: org.apache.kafka.connect.json.JsonConverter
      CONNECT_KEY_CONVERTER_SCHEMAS_ENABLE: "false"
      CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE: "false"
      CONNECT_PLUGIN_PATH: /usr/share/java,/usr/share/confluent-hub-components,/home/user/connectors
      CONNECT_REST_ADVERTISED_HOST_NAME: connect
    ports: ["8083:8083"]            # ▼ Connect REST API 端口
    volumes:
      - ./connectors:/home/user/connectors   # ▼ 第 3、4 章自定义 connector 挂进来
```

```bash
docker-compose up -d
```

> **三个内部 topic（`connect-configs`/`connect-offsets`/`connect-status`）** 是 distributed 模式的骨架：configs 存 connector 配置、offsets 存 SourceTask 的断点、status 存任务状态。**standalone 模式不用 topic**，offset 存在本地文件里（见 2.2）。

**方案 B（本地，用 Apache 发行版）**：下载 [Apache Kafka](https://kafka.apache.org/downloads) 4.x，自带 `connect-standalone.sh` / `connect-distributed.sh` 和现成示例配置文件。

```bash
# 先起 Kafka（KRaft 单节点）
KAFKA_CLUSTER_ID=$(bin/kafka-storage.sh random-uuid)
bin/kafka-storage.sh format -t $KAFKA_CLUSTER_ID -c config/kraft/server.properties
bin/kafka-server-start.sh config/kraft/server.properties &

# 启动 standalone Connect
bin/connect-standalone.sh config/connect-standalone.properties
```

### 2.2 standalone 模式的 connect-standalone.properties

```properties
# config/connect-standalone.properties
# ▼ 连哪个 Kafka
bootstrap.servers=localhost:9092

# ▼ converter：Connect 内部数据 ↔ Kafka bytes
key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
# schemas.enable=false：不把 schema 包进 JSON（裸 JSON）；true 则包一层 schema 信封
key.converter.schemas.enable=false
value.converter.schemas.enable=false

# ▼ standalone 模式：offset 存本地文件（distributed 模式存 connect-offsets topic）
offset.storage.file.filename=/tmp/connect.offsets
offset.flush.interval.ms=10000

# ▼ 插件搜索路径（connector jar 放这里）
plugin.path=/home/user/connectors
```

> **`schemas.enable` 是新手最常踩的坑**：`true` 时 JsonConverter 会输出 `{"schema":{...},"payload":...}` 信封，裸 JSON 消费端会一脸懵；本篇全用 `false`（裸 JSON），第 5 章详解。

### 2.3 File Source：把文件行搬进 Kafka

**Step 1**：准备源文件。

```bash
echo "hello connect" > /tmp/input.txt
echo "second line" >> /tmp/input.txt
```

**Step 2**：写 connector 配置（Apache 发行版自带示例 `config/connect-file-source.properties`，字段如下）。

```properties
# config/connect-file-source.properties
name=file-source              # ▼ connector 名字（唯一）
connector.class=FileStreamSourceConnector   # ▼ 用哪个 connector 类（发行版内置）
tasks.max=1                   # ▼ 拆几个 task 并行（文件只有一个读指针，1 个）
file=/tmp/input.txt           # ▼ 读哪个文件
topic=demo-file-lines         # ▼ 写进 Kafka 哪个 topic
```

**Step 3**：启动（standalone：配置文件里可跟多个 connector 配置文件）。

```bash
bin/connect-standalone.sh \
  config/connect-standalone.properties \
  config/connect-file-source.properties
```

**Step 4：验证数据流动**。

```bash
# 消费端挂着
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic demo-file-lines --from-beginning
# hello connect
# second line

# ▼ 往源文件再追加一行（不用重启 connector！）
echo "third line" >> /tmp/input.txt
# 消费端立刻多一条：third line
```

> **验证点**：文件加一行，Kafka 立刻多一条——这就是"持续数据管道"。再看断点续传：`Ctrl-C` 停掉 connect，追加一行再启动，**新行仍会发出来**（offset 记在 `/tmp/connect.offsets`），但旧行不会重发（SourceTask 返回的 offset 起了作用，第 3 章细讲）。

### 2.4 File Sink：把 Kafka 消息写进文件

**Step 1**：写 sink connector 配置。

```properties
# config/connect-file-sink.properties
name=file-sink
connector.class=FileStreamSinkConnector
tasks.max=1
topics=demo-file-lines        # ▼ 订阅哪些 topic（Source 用 topic，Sink 用 topics）
file=/tmp/output.txt          # ▼ 写到哪个文件
```

**Step 2**：两个 connector 一起跑。

```bash
bin/connect-standalone.sh \
  config/connect-standalone.properties \
  config/connect-file-source.properties \
  config/connect-file-sink.properties
```

**Step 3：验证**。

```bash
cat /tmp/output.txt
# hello connect
# second line

echo "fourth line" >> /tmp/input.txt     # 源加一行
cat /tmp/output.txt                       # ▼ 链路全通：文件 → Kafka → 文件
# hello connect
# second line
# fourth line
```

> **验证点**：一条 `echo` 触发 源文件 → Kafka → 目标文件 的完整闭环，这就是 Source + Sink 拼接成的一条数据管道。

### 2.5 JDBC connector（异构源的典型）

文件 connector 只是开胃菜。生产上最常见的"异构源"是**数据库 → Kafka**。JDBC connector 不内置在 Apache 发行版里，来自 Confluent Hub：

```bash
# 在 connect 容器里装（或宿主机 confluent-hub 装到 plugin.path）
docker exec -it <connect容器> confluent-hub install confluentinc/kafka-connect-jdbc:latest --no-prompt
```

**JDBC Source 配置**（把 MySQL 的 `orders` 表搬进 Kafka）：

```properties
# config/jdbc-source.properties
name=jdbc-orders-source
connector.class=io.confluent.connect.jdbc.JdbcSourceConnector
tasks.max=1
connection.url=jdbc:mysql://localhost:3306/demo
connection.user=root
connection.password=secret
table.whitelist=orders          # ▼ 只同步这张表
topic.prefix=demo-db-           # ▼ 生成的 topic 名 = topic.prefix + 表名 → demo-db-orders
mode=incrementing               # ▼ 增量模式：靠自增主键 id 轮询新行
incrementing.column.name=id
```

**验证**：

```bash
mysql> INSERT INTO orders(id, customer, amount) VALUES(1, 'alice', 99.9);

bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic demo-db-orders --from-beginning
# {"id":1,"customer":"alice","amount":99.9}
```

**JDBC Sink 配置**（把 Kafka 消息写回 MySQL，反向）：

```properties
# config/jdbc-sink.properties
name=jdbc-orders-sink
connector.class=io.confluent.connect.jdbc.JdbcSinkConnector
tasks.max=1
connection.url=jdbc:mysql://localhost:3306/demo
connection.user=root
connection.password=secret
topics=demo-db-orders
insert.mode=upsert              # ▼ 幂等：存在则更新，配合第 6 章去重
pk.mode=record_key
```

> **跑通即可，不用深究**：JDBC connector 配置项很多，本篇重点是**能跑**。真正的主角是第 3、4 章的**自定义 connector**——那时你才真正掌握 Connect 的编程模型。

---

## 第 3 章：写自定义 Source connector

> **本章是编程重点**。你要写两个类：
> 1. **`SourceConnector`**：负责"配置管理 + 任务拆分"——拿到配置，决定拆几个 `SourceTask`、每个 task 拿什么配置。
> 2. **`SourceTask`**：负责"真正干活"——`poll()` 从源头读一批数据，转成 `SourceRecord` 列表返回给框架，框架负责发进 Kafka。

### 3.1 工程与依赖

自定义 connector 是**独立的 Java 工程**，不依赖 Spring，只依赖 `connect-api`：

```xml
<!-- pom.xml（关键部分） -->
<dependencies>
    <dependency>
        <groupId>org.apache.kafka</groupId>
        <artifactId>connect-api</artifactId>
        <version>4.0.0</version>   <!-- 与仓库 kafka-clients 4.0.x 同版本线；worker 运行时会提供 connect-api，所以 provided -->
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>3.5.1</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals><goal>shade</goal></goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

> **重要**：`connect-api` 用 `provided`（worker 自带），**不要**把它打进插件包，否则和 worker 的版本冲突。打成 jar 后，需要一个 `META-INF/services` 文件让 Connect 发现你的 connector（见 3.6）。

### 3.2 例子：把文件每一行搬进 Kafka（`FileLineSourceConnector`）

和内置 `FileStreamSourceConnector` 类似的完整实现，但**带清晰的 offset 断点续传**——你写的代码，就是框架帮你做管道的核心。

**先写 `SourceConnector`**：

```java
package com.example.connect.source;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceConnector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SourceConnector：只管"配置 + 拆任务"。
 * 真正的数据搬运在 FileLineSourceTask（poll()）里。
 */
public class FileLineSourceConnector extends SourceConnector {

    public static final String FILE_CONFIG = "file";
    public static final String TOPIC_CONFIG = "topic";

    private String filename;
    private String topic;

    @Override
    public String version() {
        return "1.0.0";
    }

    /** worker 启动时调用：校验并保存配置。 */
    @Override
    public void start(Map<String, String> props) {
        filename = props.get(FILE_CONFIG);
        topic = props.get(TOPIC_CONFIG);
        if (filename == null || topic == null) {
            throw new ConnectException(FILE_CONFIG + " 和 " + TOPIC_CONFIG + " 两个配置必填");
        }
    }

    /** 真正干活的 task 类。 */
    @Override
    public Class<? extends Task> taskClass() {
        return FileLineSourceTask.class;
    }

    /**
     * 把 connector 的配置拆成每个 task 的配置。
     * 注意：一个文件只有一个"读指针"，拆多个 task 会重复读，所以固定返回 1 个 task。
     * （如果要并行，应该让每个 task 读不同的文件/分片，比如按 offset 分片。）
     */
    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        Map<String, String> config = new java.util.HashMap<>();
        config.put(FILE_CONFIG, filename);
        config.put(TOPIC_CONFIG, topic);
        List<Map<String, String>> configs = new ArrayList<>(1);
        configs.add(config);
        return configs;
    }

    /** worker 停止时调用：释放资源。 */
    @Override
    public void stop() {
    }

    /** 可选：给 REST API / 校验器提供配置项的元数据。 */
    @Override
    public ConfigDef config() {
        return new ConfigDef()
                .define(FILE_CONFIG, ConfigDef.Type.STRING, ConfigDef.Importance.HIGH, "要读取的文件路径")
                .define(TOPIC_CONFIG, ConfigDef.Type.STRING, ConfigDef.Importance.HIGH, "要写入的 Kafka topic");
    }
}
```

### 3.3 `SourceTask`：`poll()` 返回 `SourceRecord` 列表

**再写 `SourceTask`**——真正的核心：

```java
package com.example.connect.source;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SourceTask：poll() 从文件读行，转成 SourceRecord 列表返回。
 * 框架拿到 SourceRecord 后自动用 converter 序列化并发进 Kafka。
 */
public class FileLineSourceTask extends SourceTask {

    public static final String FILE_CONFIG = "file";
    public static final String TOPIC_CONFIG = "topic";
    private static final String OFFSET_KEY = "line";     // offset 里记"读到第几行"
    private static final String PARTITION_KEY = "file";  // 分区标识：哪个文件

    private BufferedReader reader;
    private String filename;
    private String topic;
    private long currentLine;   // 下次要读的行号（0 起）

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void start(Map<String, String> props) {
        filename = props.get(FILE_CONFIG);
        topic = props.get(TOPIC_CONFIG);

        // ▼ 关键：从框架的 offset 存储里恢复"上次读到哪"，实现断点续传
        Map<String, Object> offset = context.offsetStorageReader().offset(sourcePartition());
        if (offset != null && offset.containsKey(OFFSET_KEY)) {
            currentLine = ((Number) offset.get(OFFSET_KEY)).longValue();
        }

        try {
            BufferedReader br = Files.newBufferedReader(Paths.get(filename));
            // 跳过已消费的行，把读指针挪到断点处
            for (long i = 0; i < currentLine; i++) {
                if (br.readLine() == null) {
                    break;
                }
            }
            reader = br;
        } catch (IOException e) {
            throw new RuntimeException("无法打开文件: " + filename, e);
        }
    }

    /**
     * 框架循环调用 poll() 拿数据。
     * 有数据返回 SourceRecord 列表；没数据返回空列表（框架会稍后重试）。
     */
    @Override
    public List<SourceRecord> poll() {
        try {
            List<SourceRecord> records = new ArrayList<>();
            // 一次最多读 100 行（控制批量大小，别一次性读太多）
            for (int i = 0; i < 100; i++) {
                String line = reader.readLine();
                if (line == null) {
                    break;   // 文件当前没有新行，等下次 poll
                }
                // ▼ 每条 SourceRecord 就是"要发进 Kafka 的一条消息"
                records.add(new SourceRecord(
                        sourcePartition(),                    // sourcePartition：标识来源（哪个文件）
                        sourceOffset(),                       // sourceOffset：断点（读到第几行）
                        topic,                                // topic：写进哪个 topic
                        null,                                 // kafka partition：null = 交给 Kafka 决定
                        null,                                 // key
                        Schema.STRING_SCHEMA,                 // value 的 schema
                        line                                  // value：这一行的内容
                ));
                currentLine++;
            }
            return records;
        } catch (IOException e) {
            throw new RuntimeException("读文件失败: " + filename, e);
        }
    }

    @Override
    public void stop() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** sourcePartition：连接器用它唯一标识"一条数据流"，offset 按它分文件存。 */
    private Map<String, String> sourcePartition() {
        return Collections.singletonMap(PARTITION_KEY, filename);
    }

    /** sourceOffset：这行数据在源头的位置。刚发出的那行记 currentLine（0 起）。 */
    private Map<String, Long> sourceOffset() {
        return Collections.singletonMap(OFFSET_KEY, currentLine);
    }
}
```

### 3.4 `SourceRecord` 五要素详解（最重要的知识点）

`SourceRecord` 是 Source 端的数据载体，**填错一个字段，断点续传或分区逻辑就歪了**。构造函数的五个关键参数：

| 参数 | 是什么 | 填什么 | 填错了会怎样 |
|---|---|---|---|
| `sourcePartition` | 标识**数据来自哪条流** | 文件路径、表名、分片号 | 记错 → 断点存错地方，重启用错 offset |
| `sourceOffset` | **这条数据在源头的断点** | 行号、自增 id、binlog 位点 | 填错 → 重启后重复读或丢数据 |
| `topic` | 写进 Kafka 哪个 topic | topic 名 | 数据进错 topic |
| `kafkaPartition` | 写进 Kafka 哪个分区 | `null` = 交给 Kafka 按 key 哈希/轮询 | 乱指定会破坏顺序 |
| `key` / `value` + 各自的 schema | 消息的键和值 | key 可为 null；value 是业务数据 | schema 写错 → converter 序列化错 |

> **sourcePartition / sourceOffset 和 kafkaPartition / kafkaOffset 是两套东西**，别混：
> - **source***：指向**外部源**（文件、DB）——Connect 把它存进 `connect-offsets`，用于**断点续传**（重启接着读）。
> - **kafka***：指向 **Kafka 本身**——`SourceRecord` 构造时 kafka 分区可空，由框架分配；`SinkRecord` 里 `kafkaPartition()`/`kafkaOffset()` 是这条消息在 Kafka 里的真实位置。
>
> **规律**：`sourcePartition` 必须是**稳定不变**的（同一个文件永远同一个分区键），`sourceOffset` 每次发一条要**前进**一点——这样重启才能精确续传。这也是第 6 章幂等的根基。

### 3.5 打包成插件 + 注册 + 验证

**Step 1**：加服务发现文件（Connect 靠它发现你的 connector 类）。

```
# src/main/resources/META-INF/services/org.apache.kafka.connect.connector.Connector
com.example.connect.source.FileLineSourceConnector
com.example.connect.sink.FileLineSinkConnector    # 第 4 章会用到，一起写上
```

**Step 2**：打包并把 jar 放进 worker 的 plugin.path。

```bash
mvn clean package
# target/connect-demo-1.0.0.jar

# docker 方案：把 jar 放进 ./connectors（已挂载到 CONNECT_PLUGIN_PATH 的 /home/user/connectors）
cp target/connect-demo-1.0.0.jar ./connectors/

# 本地方案：cp 到 connect-standalone.properties 里 plugin.path 指定的目录
cp target/connect-demo-1.0.0.jar /home/user/connectors/
```

> **改 plugin 后要重启 worker**。distributed 模式下连接器插件变更也要逐个滚动重启 worker，让集群重扫 `plugin.path`。重启后可先确认插件被识别：

```bash
curl http://localhost:8083/connector-plugins
# 输出里应能看到 {"class":"com.example.connect.source.FileLineSourceConnector", ...}
```

**Step 3**：用 REST API 注册自定义 connector（不用写 properties 文件了，REST 是生产标准方式）。

```bash
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d '{
  "name": "my-file-source",
  "config": {
    "connector.class": "com.example.connect.source.FileLineSourceConnector",
    "tasks.max": "1",
    "file": "/tmp/input.txt",
    "topic": "demo-my-lines",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false"
  }
}'
```

**Step 4：验证**。

```bash
# 写三行源文件
printf 'line-A\nline-B\nline-C\n' > /tmp/input.txt

# 消费 Kafka
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic demo-my-lines --from-beginning
# line-A
# line-B
# line-C

# 追加一行（管道是活的）
echo line-D >> /tmp/input.txt
# 消费端出现 line-D
```

**Step 5：验证断点续传（重点）**。

```bash
# 1) 停掉 connect（Ctrl-C）
# 2) 再追加一行
echo line-E >> /tmp/input.txt
# 3) 重启 connect
# 4) 看消费端：只出现 line-E，没有 line-A~D（旧行不会重发）
```

> **这就是 offset 断点续传的价值**：`sourceOffset` 让 Connect 精确知道"上次读到第 5 行"，重启从第 5 行继续，**既不丢、也不重**。对比 06 里消费者手动提交 offset 的思路——框架层帮你做了同样的事。

### 3.6 变形：随机数 Source（最简版，可不管 offset）

如果源头本身"没有断点概念"（比如生成随机数），offset 可以给个随便递增的序列号，甚至不给。

```java
package com.example.connect.source;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** 最简 Source：每轮生成 10 个随机数发进 Kafka，无需文件、无需真实断点。 */
public class RandomNumberSourceTask extends SourceTask {

    private long seq = 0;

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void start(Map<String, String> props) {
    }

    @Override
    public List<SourceRecord> poll() {
        List<SourceRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int value = ThreadLocalRandom.current().nextInt(1000);
            records.add(new SourceRecord(
                    Collections.singletonMap("source", "random"),   // sourcePartition：稳定即可
                    Collections.singletonMap("seq", seq++),         // sourceOffset：递增序列
                    "random-numbers",                               // topic
                    null, null,                                     // kafka partition / key
                    Schema.INT32_SCHEMA, value                      // schema + value
            ));
        }
        return records;
    }

    @Override
    public void stop() {
    }
}
```

**对比**：文件 Source 的 offset 是"行的真实位置"，重启要精确续传；随机数 Source 没有外部状态，offset 只是占位。**设计 Source 时要想清楚：我的源头"读到哪了"是由什么决定的？**——这就是你要填进 `sourceOffset` 的东西。

---

## 第 4 章：写自定义 Sink connector

> **编程重点之二**。同样的两个类：
> 1. **`SinkConnector`**：配置管理 + 任务拆分（和 Source 的 connector 几乎一样）。
> 2. **`SinkTask`**：核心是 **`put(Collection<SinkRecord>)`**——框架把一批 Kafka 消息交给它，它写进目标系统。

### 4.1 `SinkConnector`

```java
package com.example.connect.sink;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SinkConnector：配置管理 + 任务拆分。真正写文件的是 FileLineSinkTask。 */
public class FileLineSinkConnector extends SinkConnector {

    public static final String FILE_CONFIG = "file";

    private String filename;

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void start(Map<String, String> props) {
        filename = props.get(FILE_CONFIG);
        if (filename == null) {
            throw new org.apache.kafka.connect.errors.ConnectException(FILE_CONFIG + " 配置必填");
        }
    }

    @Override
    public Class<? extends Task> taskClass() {
        return FileLineSinkTask.class;
    }

    /**
     * 注意：多个 task 同时写一个文件会互相覆盖/交错。
     * 文件 sink 只支持 tasks.max=1（分布式写同一文件要按不同文件/分片拆 task）。
     */
    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        List<Map<String, String>> configs = new ArrayList<>(1);
        Map<String, String> cfg = new HashMap<>();
        cfg.put(FILE_CONFIG, filename);
        configs.add(cfg);
        return configs;
    }

    @Override
    public void stop() {
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef().define(FILE_CONFIG, ConfigDef.Type.STRING,
                ConfigDef.Importance.HIGH, "输出文件路径");
    }
}
```

### 4.2 `SinkTask`：`put()` 接收一批消息

```java
package com.example.connect.sink;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;

/** SinkTask：put() 把 Kafka 消息写进文件。 */
public class FileLineSinkTask extends SinkTask {

    public static final String FILE_CONFIG = "file";

    private BufferedWriter writer;

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void start(Map<String, String> props) {
        String filename = props.get(FILE_CONFIG);
        try {
            writer = Files.newBufferedWriter(
                    Paths.get(filename),
                    StandardOpenOption.CREATE,     // 不存在则创建
                    StandardOpenOption.APPEND      // 追加写（不覆盖已有内容）
            );
        } catch (IOException e) {
            throw new ConnectException("无法打开输出文件: " + filename, e);
        }
    }

    /**
     * 核心方法：框架把一批消费到的消息交给 put()。
     * 一条 SinkRecord = Kafka 里的一条消息（已经过 value.converter 反序列化）。
     */
    @Override
    public void put(Collection<SinkRecord> records) {
        for (SinkRecord record : records) {
            // ▼ 从 SinkRecord 里读这条消息的一切（详见 4.3）
            String topic = record.topic();
            int partition = record.kafkaPartition();
            long offset = record.kafkaOffset();
            Object value = record.value();

            try {
                writer.write(String.format("[%s/%d@%d] %s%n", topic, partition, offset, value));
                writer.flush();   // 教学用立即 flush 便于观察；生产按批次 flush
            } catch (IOException e) {
                throw new ConnectException("写文件失败", e);
            }
        }
    }

    /**
     * 可选实现：框架提交 offset 前回调，用于把缓冲的数据真正落盘。
     * currentOffsets 是"这批记录在 Kafka 的位置"，用于幂等去重（第 6 章）。
     */
    @Override
    public void flush(Map<Object, Object> currentOffsets) {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new ConnectException("flush 失败", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
    }
}
```

### 4.3 `SinkRecord` 怎么读（最常用的字段）

| 方法 | 返回 | 含义 |
|---|---|---|
| `record.topic()` | `String` | 这条消息来自哪个 topic |
| `record.kafkaPartition()` | `int` | Kafka 里的分区号 |
| `record.kafkaOffset()` | `long` | 这条消息在分区里的偏移量（**去重的天然幂等键**） |
| `record.key()` | `Object` | 消息 key（可能为 null） |
| `record.value()` | `Object` | **消息 value**（converter 已反序列化：JSON→Map、AVRO→Avro 对象、String→String） |
| `record.timestamp()` | `Long` | 消息时间戳 |
| `record.headers()` | `Headers` | 消息头（配合第 5 章自定义 converter / DLQ 头） |

> **反序列化时机**：Sink 端拿到的 `value` 已经是 **converter 反序列化后**的对象了。配了 `JsonConverter` 时，`value` 是一个 `Map`（或 `String`，取决于源头的 schema）；配了 `AvroConverter` 时是 Avro 对象。**你的 put() 只看对象，不看 bytes**——这正是 converter 帮你挡掉序列化细节的体现。

### 4.4 打包 + 注册 + 验证

**Step 1**：`META-INF/services/...Connector` 文件里加上 `com.example.connect.sink.FileLineSinkConnector`（第 3.5 步已写）。重新 `mvn clean package` 并放进 plugin.path，重启 worker。

**Step 2**：注册 sink connector。

```bash
curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d '{
  "name": "my-file-sink",
  "config": {
    "connector.class": "com.example.connect.sink.FileLineSinkConnector",
    "tasks.max": "1",
    "topics": "demo-my-lines",
    "file": "/tmp/sink-output.txt",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false"
  }
}'
```

**Step 3：验证**。

```bash
# 往 demo-my-lines 手动发两条
echo "msg-1" | bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic demo-my-lines
echo "msg-2" | bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic demo-my-lines

cat /tmp/sink-output.txt
# [demo-my-lines/0@1] msg-1
# [demo-my-lines/0@2] msg-2
```

> **验证点**：Kafka 发两条 → 文件出现两行，且每行带 `topic/分区@offset`——你把"Kafka 消息的位置信息"一起落盘了，这就是后面做去重的基础。

---

## 第 5 章：converter 与 schema

### 5.1 converter 在管道里的位置

```
SourceTask.poll() ──▶ SourceRecord（Connect 内部对象，带 Schema）
                          │
                    [ converter 序列化 ]   ← key.converter / value.converter
                          ▼
                    Kafka bytes
                          ▼
                    [ converter 反序列化 ]
                          │
SinkTask.put()  ◀── SinkRecord（又变回带 Schema 的对象）
```

**converter 是管道的数据格式转换层**：Connect 内部统一用**带 schema 的对象模型**（`Schema` + `Struct` 等），但 Kafka 里存的必须是 **bytes**。converter 负责这两个世界的互转。**它和 Source/Sink 无关**（每个 worker 配一套，所有 connector 共用）。

### 5.2 JsonConverter：最常用，两种模式

```properties
key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
key.converter.schemas.enable=false
value.converter.schemas.enable=false
```

| 模式 | 效果 | 适用 |
|---|---|---|
| `schemas.enable=false` | 裸 JSON：`{"id":1,"name":"alice"}` | 消费者是普通 JSON 反序列化（01-06 的 `JsonSerializer` 消费端可直接读），**最常用** |
| `schemas.enable=true` | 信封 JSON：`{"schema":{...},"payload":{...}}` | 需要 schema 元数据自描述（但裸消费端会懵） |

> **验证裸 JSON 模式**：第 2 章 JDBC source 用 `schemas.enable=false` 时，消费端看到的就是 `{"id":1,...}`；改成 `true` 会看到 `{"schema":{...,"type":"struct",...},"payload":{...}}`。**跨系统对接时先确认双方对 schema 信封的约定**。

### 5.3 AvroConverter：配合 04 的 Schema Registry

[04 生产级进阶的 Schema Registry](./04-生产级进阶-Outbox与Schema与分区调优.md) 讲的是**应用内**（`KafkaAvroSerializer`）用 Avro + Registry。Connect 里配 AvroConverter 是同一套思想，**由 worker 的 converter 完成序列化 + 自动注册 schema**：

```properties
key.converter=io.confluent.connect.avro.AvroConverter
value.converter=io.confluent.connect.avro.AvroConverter
key.converter.schema.registry.url=http://localhost:8081
value.converter.schema.registry.url=http://localhost:8081
```

- **Source 端**：`poll()` 里构造 `SourceRecord` 时，如果 value 是 `Struct`（或 Avro 对象），AvroConverter 会把 schema 注册到 Registry、消息里只带 schema ID——和 04 的 `KafkaAvroSerializer` 完全同构。
- **Sink 端**：AvroConverter 凭消息里的 schema ID 去 Registry 取 schema 反序列化，`put()` 里拿到的是 Avro 对象。

> **要点**：Avro 需要 `schema.registry.url`，所以用 Avro 前得先有 [Schema Registry 服务](04-生产级进阶-Outbox与Schema与分区调优.md)（04 方向 B 就是讲它）。**JSON 不需要任何额外服务**，所以小项目默认 JSON，规模到了再上 Avro（消息体积小、schema 强管理）。

### 5.4 自定义 converter：实现 `Converter` 接口

业务有时需要**自定义格式**（加密、压缩、特殊编码）。实现 `org.apache.kafka.connect.storage.Converter` 接口，两个方法：

```java
package com.example.connect.converter;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.storage.Converter;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 示例：把字符串 value 大写后写进 Kafka（源端和汇端对称，Sink 端再转回来）。
 * 生产上这里常是加密/压缩/自定义协议。
 */
public class UpperStringConverter implements Converter {

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        // 可在此读取自定义配置，如加密密钥
    }

    /**
     * fromConnectData：Connect 内部对象 → Kafka bytes（Source 端序列化）。
     * 参数 topic 让 converter 可以"按 topic 区别对待"。
     */
    @Override
    public byte[] fromConnectData(String topic, Schema schema, Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value).toUpperCase().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * toConnectData：Kafka bytes → Connect 内部对象（Sink 端反序列化）。
     * 返回 SchemaAndValue：schema + 值。schemaless 时用 Schema.STRING_SCHEMA 即可。
     */
    @Override
    public SchemaAndValue toConnectData(String topic, byte[] value) {
        if (value == null) {
            return new SchemaAndValue(Schema.STRING_SCHEMA, null);
        }
        return new SchemaAndValue(Schema.STRING_SCHEMA, new String(value, StandardCharsets.UTF_8));
    }
}
```

配成 worker 级 converter 即可全局生效：

```properties
key.converter=com.example.connect.converter.UpperStringConverter
value.converter=com.example.connect.converter.UpperStringConverter
```

> **自定义 converter 要点**：`fromConnectData` 和 `toConnectData` 是**互逆**的，Source 端怎么编、Sink 端就得怎么解。接口还带 `topic` 参数，可做 topic 维度路由。更细粒度的是 `HeaderConverter`（处理消息头，DLQ 场景会用到）。

### 5.5 小结：schema 的三种形态

| 形态 | 在哪 | 说明 |
|---|---|---|
| **Connect Schema 对象** | SourceTask/SinkTask 内部 | `Schema.STRING_SCHEMA`、`SchemaBuilder.struct()` 等 |
| **Kafka bytes** | Kafka topic 里 | converter 序列化后的结果 |
| **外部 schema（Avro/JSON Schema）** | Schema Registry / JSON 信封 | 供消费者解析 |

---

## 第 6 章：生产要点——幂等、死信、多 worker

### 6.1 幂等与去重（呼应 06）

Connect 默认是 **at-least-once**：Source 断点没提交就崩溃 → 重启**重发**；Sink 落库成功后 offset 未提交就崩溃 → 重启**重放**。这和 [06 Kafka 可靠性专题](./06-Kafka可靠性专题.md) 讲的是同一个问题，只是发生在了"管道"里。

**对策**（和 06 的消费端幂等一模一样，只是幂等键不同）：

1. **Source 端**：把 `sourceOffset` 设计精确（文件行号、自增 id、binlog 位点），让断点尽量"准"。**Source 重发无法完全避免**，但 offset 精确能把重复窗口压到最小。
2. **Sink 端**：**用 `kafkaOffset`（或业务唯一键）做幂等键**——这是最有效的去重点，因为 `SinkRecord` 自带"这条消息在 Kafka 的唯一位置"。

写文件的幂等去重示例（伪代码，重点看 `processedOffsets` 去重逻辑）：

```java
// FileLineSinkTask 里加一个"已处理过哪些 offset"的内存去重
private final java.util.HashSet<Long> processedOffsets = new java.util.HashSet<>();

@Override
public void put(Collection<SinkRecord> records) {
    for (SinkRecord record : records) {
        long offset = record.kafkaOffset();
        // ▼ 幂等键 = topic + partition + offset（本文件场景 topic/partition 固定，用 offset 即可）
        if (!processedOffsets.add(offset)) {
            continue;   // 这条已处理过，跳过（防止重启重放重复写文件）
        }
        // ... 写文件
    }
}
```

> **真实 Sink 通常不止内存去重**：写入数据库的 Sink 用**唯一键 + upsert**（第 2.5 节 JDBC sink 的 `insert.mode=upsert` + `pk.mode`）；写文件的 Sink 可以用"partition 文件按 offset 记录已写位置"。**原则不变：让重复执行的结果等于一次执行**。

**Source 端更进一步：Exactly-Once（Connect 3.3+）**

```properties
# worker 级配置
exactly.once.support=requested
```

Source 任务会用 **Kafka 事务**把"数据 + offset"原子提交，配合下游幂等消费可实现端到端精确一次。**代价是吞吐下降**，生产默认 at-least-once + 下游幂等即可，把 EXACTLY_ONCE 当"可选的最后手段"。

### 6.2 死信与错误容忍（`errors.*`）

Sink 处理失败（目标系统临时故障、数据格式错）时，别让任务卡死。worker/connector 级配置：

```properties
# 错误容忍与死信
errors.tolerance=all                                    # all = 跳过坏消息继续跑；none = 失败即停（默认）
errors.log.enable=true                                  # 打印错误日志
errors.log.include.messages=true                        # 日志里带上消息内容（方便排查）
errors.deadletterqueue.topic.name=demo-connect-dlq     # ▼ 坏消息投进死信 topic
errors.deadletterqueue.context.headers.enable=true     # 死信消息头带上原始 topic/partition/offset
```

- `errors.tolerance=none`（默认）：一条坏消息就让 task 失败、重试，**管道停摆**。
- `errors.tolerance=all`：跳过坏消息进 DLQ，管道**继续跑**。生产管道建议 `all` + DLQ，另起消费者处理 DLQ（呼应 06 的 DLT 死信思路）。

**编程方式投死信**（更精细）：Sink 里可以逐条决定"这条投死信，那条重试"：

```java
// SinkTask 里，处理失败时把这条消息单独投进 DLQ，而不是整批失败
try {
    process(record);
} catch (Exception e) {
    // context.errantRecordReporter()：把这条错误记录投到 errors.deadletterqueue.topic.name 配的 topic
    context.errantRecordReporter().report(record, e);
}
```

**验证死信**：

```bash
# 配好 errors.tolerance=all + DLQ 后，发一条格式错误的坏消息
echo 'not-json' | bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic demo-my-lines

# 任务不崩，坏消息进了 DLQ
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic demo-connect-dlq --from-beginning
```

### 6.3 多 worker 扩展（distributed 模式）

生产用 **distributed** 模式：多个 worker 组成集群，connector/task 自动分配、worker 挂掉自动迁移（rebalance）。

**connect-distributed.properties**（关键项）：

```properties
# config/connect-distributed.properties
bootstrap.servers=localhost:9092

# ▼ 集群身份：同一集群的 worker 用同一个 group.id
group.id=connect-cluster

# ▼ 三个内部 topic（必须提前建好或允许自动创建，副本数=集群建议 3）
config.storage.topic=connect-configs
offset.storage.topic=connect-offsets
status.storage.topic=connect-status
config.storage.replication.factor=3
offset.storage.replication.factor=3
status.storage.replication.factor=3

key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
key.converter.schemas.enable=false
value.converter.schemas.enable=false

plugin.path=/home/user/connectors
```

启动：在**每台机器**上跑 `bin/connect-distributed.sh config/connect-distributed.properties`，它们自动组成集群。

**扩展三连**：

1. **加 worker**：水平加机器 → 集群能承载更多 task、且高可用（一个 worker 挂，任务自动挪走）。
2. **加 task**：把 connector 的 `tasks.max` 调大 → 框架把任务分到多个 worker。但**不是所有 connector 都能多 task**（3.2 的文件 source 只能 1 个 task；分片源如按 `id` 分片、按 topic 分区分的 sink 才能并行）。
3. **REST 管理**：`GET /connectors/{name}/status` 看任务分到了哪个 worker、健康与否。

```bash
curl http://localhost:8083/connectors/my-file-source/status
# {
#   "name": "my-file-source",
#   "connector": {"state": "RUNNING", "worker_id": "connect:8083"},
#   "tasks": [{"id": 0, "state": "RUNNING", "worker_id": "connect:8083"}]
# }
```

> **扩不扩得动，取决于你的 connector 怎么拆 task**。这也是第 3、4 章 `taskConfigs(maxTasks)` 方法的含金量所在——**写 connector 时就在设计并行度**：单一文件只有一个读指针，拆了也白拆；数据库按主键范围分片、Kafka 按分区分配，才是能真正水平扩展的 source/sink。

### 6.4 生产检查清单

| 项 | 配置/做法 |
|---|---|
| 运行模式 | distributed（≥3 worker），不用 standalone |
| 内部 topic | `config/offset/status` 三个 topic，副本数 ≥3，提前建好 |
| 插件 | 放进统一 `plugin.path`，改插件要滚动重启 |
| 可靠性 | Source 精确 offset；Sink 幂等键去重（`kafkaOffset` / 业务唯一键）；必要时 `exactly.once.support` |
| 错误 | `errors.tolerance=all` + DLQ + `errors.deadletterqueue.context.headers.enable=true` |
| 监控 | `GET /connectors/{name}/status` 看 state；`metrics` 端点；对接 Prometheus |
| 配置变更 | 用 REST 改配置（热生效，触发 rebalance），别直接改文件 |

---

## 附录：本篇 API 速查表

| 你要实现/用 | 接口/类 | 关键方法 | 本篇章节 |
|---|---|---|---|
| Source 配置层 | `org.apache.kafka.connect.source.SourceConnector` | `start()` / `taskClass()` / `taskConfigs(int)` / `stop()` | 3.2 |
| Source 干活层 | `org.apache.kafka.connect.source.SourceTask` | `poll()` 返回 `List<SourceRecord>`；`context.offsetStorageReader()` | 3.3 |
| Source 数据载体 | `org.apache.kafka.connect.source.SourceRecord` | `sourcePartition` / `sourceOffset` / `topic` / `kafkaPartition` / `key` / `value` | 3.4 |
| Sink 配置层 | `org.apache.kafka.connect.sink.SinkConnector` | 同 SourceConnector | 4.1 |
| Sink 干活层 | `org.apache.kafka.connect.sink.SinkTask` | `put(Collection<SinkRecord>)` / `flush(Map)` | 4.2 |
| Sink 数据载体 | `org.apache.kafka.connect.sink.SinkRecord` | `topic()` / `kafkaPartition()` / `kafkaOffset()` / `key()` / `value()` | 4.3 |
| converter | `org.apache.kafka.connect.storage.Converter` | `fromConnectData()` / `toConnectData()` | 5.4 |
| schema | `org.apache.kafka.connect.data.Schema` | `Schema.STRING_SCHEMA` / `Schema.INT32_SCHEMA` 等 | 3、5 |
| 服务发现 | `META-INF/services/org.apache.kafka.connect.connector.Connector` | 每行一个 connector 全类名 | 3.5 |
| 死信编程 | `SinkTaskContext.errantRecordReporter()` | `report(record, error)` | 6.2 |
