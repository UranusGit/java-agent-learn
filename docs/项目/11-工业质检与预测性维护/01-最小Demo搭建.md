# 项目 11：工业质检与预测性维护 — 01-最小 Demo 搭建

> **定位**：把传感器时序接入 + 异常检测跑通的最小内核——MQTT 采集、时序存储、EWMA 统计检测。本篇刻意不用 LLM（先让"数据进得来、异常测得准"），后续迭代的 LLM 解释/质检/工单全部消费这份检测信号。本文给出**完整可手写代码**（一行不省略）。
>
> 「遇到阻塞？→ [教程 30-容错与弹性设计 §异常检测]、[附录 06-WebFlux与响应式编程/01-Reactor核心 §7]、[附录 05-SpringAI2-API基准]」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | MQTT 设备数据接入 → 时序存储 → EWMA 异常检测 → 异常事件流（"数据进得来、异常测得准"） |
| **影响了哪些模块** | 全部（这是地基，无历史包袱）——采集、存储、检测、事件流 |
| **架构如何演进** | 零 LLM 的确定性数据管线：`MqttPahoMessageDrivenChannelAdapter` → `SensorIngestService` → `EwmaAnomalyDetector` → `Sinks.Many` 事件流 |
| **上一版痛点是什么** | 无（v1 是起点）；痛点是**将要暴露的**——质检靠人眼、维护靠事后，后续迭代全部消费这份检测信号 |

### 1.1 本节核对（四问）

- [ ] 第 3 行"架构演进"与 §3 代码四件套（MqttPahoMessageDrivenChannelAdapter → SensorIngestService → EwmaAnomalyDetector → Sinks.Many 事件流）链路一一对应，无缺件
- [ ] 第 4 行"将要暴露的痛点"三个（质检靠人眼 / CV+LLM 接力未建 / 信号无可读解释）分别指向 02 / 03 迭代，映射一致

## 2. 目标与量化验收

| # | 目标 | 验收 |
|---|------|------|
| 1 | 数据接入 | 设备 MQTT 数据 100% 入时序库（字段不丢） |
| 2 | 检测有效 | 注入已知异常（振动突增），EWMA 触发率 ≥ 95% |
| 3 | 误报可控 | 正常工况误触发率 < 3%（回测 7 天正常数据） |
| 4 | 实时性 | 检测延迟 < 1s（采集→信号事件） |
| 5 | 零成本 | 检测全程零 LLM Token（纯统计） |

**本迭代明确不做**：LLM 解释（v3）、视觉质检（v2）、工单（v5）、边缘部署（v4）。

### 2.1 本节核对（目标与量化验收）

- [ ] 五项验收（接入 100% / 注入触发 ≥ 95% / 误触发 < 3% / 延迟 < 1s / 零 LLM Token）与本迭代明确不做的范围（LLM 解释/质检/工单/边缘）无冲突——v1 全程零 LLM
- [ ] 验收项 2/3/4 都能在 §3.11 单元测试与 §3.13 本节测试与验证中找到对应断言口径

## 3. 完整代码（照抄即可，一行不省略）

### 3.1 `pom.xml`（依赖）

以下新增依赖需在 pom.xml 中添加（`spring-integration-mqtt`、`taos-jdbcdriver` 为基线未声明的新依赖）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>
    <groupId>com.plant</groupId>
    <artifactId>iq-agent</artifactId>
    <version>0.1.0</version>
    <name>iq-agent</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>2.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- WebFlux 响应式 Web（项目铁律：非 MVC） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <!-- Spring AI OpenAI 集成（DeepSeek 兼容；v1 未调用 LLM，v2+ 复用） -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- JDBC（JdbcTemplate 写时序库） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <!-- 需在 pom.xml 中添加：MQTT 设备接入 -->
        <dependency>
            <groupId>org.springframework.integration</groupId>
            <artifactId>spring-integration-mqtt</artifactId>
        </dependency>
        <!-- ⚠ 以下依赖需显式添加（本地未下载，未 javap 实证）：spring-integration-mqtt -->

        <!-- 需在 pom.xml 中添加：TDengine 时序库 JDBC 驱动 -->
        <dependency>
            <groupId>com.taosdata.jdbc</groupId>
            <artifactId>taos-jdbcdriver</artifactId>
            <version>3.2.7</version>
        </dependency>
        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 3.2 `application.yml`

```yaml
spring:
  application:
    name: iq-agent
  ai:
    openai:
      base-url: https://api.deepseek.com          # v1 未调用 LLM；v2 起作为默认模型通道
      api-key: ${DEEPSEEK_API_KEY:sk-placeholder}  # 环境变量，不落明文；placeholder 避免启动失败
      chat:
        model: deepseek-chat          # Spring AI 2.0.0：无 options 中缀，参数直挂
  datasource:
    url: jdbc:TAOS-RS://taos-server:6041/iq_plant   # TDengine REST 连接
    username: root
    password: ${TAOS_PASSWORD:taosdata}
    driver-class-name: com.taosdata.jdbc.rs.RestfulDriver

mqtt:
  broker-url: tcp://mqtt-broker:1883
  client-id: sensor-edge-01
  topic-pattern: factory/sensors/+/reading

server:
  port: 8080
```

### 3.3 SQL DDL `db/schema-v1-tdengine.sql`

```sql
-- TDengine 时序库 DDL（db: iq_plant）
CREATE DATABASE IF NOT EXISTS iq_plant;

USE iq_plant;

-- 传感器读数超表：列固定，设备为 TAG（TDengine 按设备自动建子表）
CREATE STABLE IF NOT EXISTS sensor_reading (
    ts          TIMESTAMP,
    vibration   DOUBLE,
    temp        DOUBLE
) TAGS (device_id NCHAR(32));

-- 子表按设备自动创建，插入示例：
-- INSERT INTO sensor_pump_01 USING sensor_reading TAGS ('pump-01') VALUES (now, 0.42, 65.3);
```

### 3.4 主类 `IqAgentApplication.java`

```java
package com.plant.iq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IqAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(IqAgentApplication.class, args);
    }
}
```

### 3.5 `SensorReading.java`（不可变数据记录）

```java
package com.plant.iq.ingest;

import java.time.Instant;

/** 一次传感器读数：设备 + 振动 + 温度 + 时间戳 */
public record SensorReading(String deviceId, double vibration, double temp, Instant ts) {}
```

### 3.6 `SensorIngestService.java`（MQTT 解析 → 时序库 → 检测）

```java
package com.plant.iq.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plant.iq.detect.EwmaAnomalyDetector;
import com.plant.iq.detect.AnomalyEventSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

@Service
public class SensorIngestService {

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final EwmaAnomalyDetector detector;
    private final AnomalyEventSink eventSink;

    public SensorIngestService(ObjectMapper mapper, JdbcTemplate jdbc,
                               EwmaAnomalyDetector detector, AnomalyEventSink eventSink) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.detector = detector;
        this.eventSink = eventSink;
    }

    /** MQTT payload → SensorReading（设备发 JSON: {"deviceId":"pump-01","vibration":0.42,"temp":65.3}） */
    public SensorReading parse(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            return new SensorReading(
                    node.get("deviceId").asText(),
                    node.get("vibration").asDouble(),
                    node.get("temp").asDouble(),
                    Instant.now());
        } catch (Exception e) {
            throw new IllegalArgumentException("MQTT payload 解析失败: " + payload, e);
        }
    }

    /** 写时序库 + 触发统计预筛（零 LLM 的检测层） */
    public void write(SensorReading r) {
        // TDengine 子表按设备自动创建；USING 绑定超表与 TAG
        jdbc.update("INSERT INTO " + childTable(r.deviceId())
                        + " USING sensor_reading TAGS (?) VALUES (?, ?, ?)",
                r.deviceId(),
                r.vibration(),
                r.temp(),
                Timestamp.from(r.ts()));

        detector.onReading(r).ifPresent(eventSink::emit);
    }

    private String childTable(String deviceId) {
        return "sensor_" + deviceId.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
```

### 3.7 `EwmaAnomalyDetector.java`（统计预筛，零 LLM）

```java
package com.plant.iq.detect;

import com.plant.iq.ingest.SensorReading;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双轨制的"统计预筛"层：EWMA 残差监控（CPU 上持续跑，零 Token）。
 * 依据 [调研 工业质检 2026 §PdM 双轨制]：轻量统计持续跑，统计显著事件才触发 LLM（v3）。
 */
@Component
public class EwmaAnomalyDetector {

    private static final double LAMBDA = 0.1;      // EWMA 平滑因子
    private static final double Z_THRESHOLD = 3.0; // 3σ 触发

    private final Map<String, Double> ewma = new ConcurrentHashMap<>();
    private final Map<String, Double> variance = new ConcurrentHashMap<>();

    /** 每点判断: 若残差超阈值 → 标记统计显著事件（供 v3 LLM 唤醒） */
    public Optional<StatSignal> onReading(SensorReading r) {
        String key = r.deviceId();
        double prev = ewma.getOrDefault(key, r.vibration());
        double newEwma = LAMBDA * r.vibration() + (1 - LAMBDA) * prev;
        double diff = Math.abs(r.vibration() - newEwma);
        ewma.put(key, newEwma);

        double var = variance.getOrDefault(key, 0.01);
        double z = diff / Math.sqrt(var);
        variance.put(key, LAMBDA * diff * diff + (1 - LAMBDA) * var);

        return z > Z_THRESHOLD
                ? Optional.of(new StatSignal(r.deviceId(), r.vibration(), z, r.ts()))
                : Optional.empty();
    }
}
```

### 3.8 `StatSignal.java` + `AnomalyEventSink.java`（异常事件流）

```java
package com.plant.iq.detect;

import java.time.Instant;

/** 统计显著事件——v3 的 LLM 唤醒、v5 的工单都消费它 */
public record StatSignal(String deviceId, double value, double zScore, Instant ts) {}
```

```java
package com.plant.iq.detect;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 异常信号事件流（Sinks.Many 供下游订阅，[附录 01 §Flux 冷热]） */
@Component
public class AnomalyEventSink {

    private final Sinks.Many<StatSignal> sink =
            Sinks.many().multicast().onBackpressureBuffer();

    public void emit(StatSignal signal) {
        sink.tryEmitNext(signal);
    }

    public Flux<StatSignal> stream() {
        return sink.asFlux();
    }
}
```

### 3.9 `SensorIngestConfig.java`（MQTT → 管线）

```java
package com.plant.iq.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;   // ⚠ 需引入依赖 org.springframework.integration:spring-integration-mqtt（本地未下载，未 javap 实证；以引入依赖后 javap 输出为准）
import org.springframework.messaging.Message;

@Configuration
public class SensorIngestConfig {

    @Value("${mqtt.broker-url}")     private String brokerUrl;
    @Value("${mqtt.client-id}")      private String clientId;
    @Value("${mqtt.topic-pattern}")  private String topicPattern;

    @Bean
    public IntegrationFlow mqttInboundFlow(SensorIngestService service) {
        // 构造器参数顺序为 (clientId, url, topics)：第一个是 clientId，第二个才是 brokerUrl
        return IntegrationFlow.from(
                        new MqttPahoMessageDrivenChannelAdapter(
                                clientId, brokerUrl, topicPattern),
                        e -> e.id("sensorMqttInbound"))
                .transform(Message::getPayload)                     // 原始 payload → String
                .<String, SensorReading>transform(service::parse)   // 解析为 SensorReading
                .handle(SensorReading.class, service::write)        // 写时序库 + 触发检测
                .get();
    }
}
```

### 3.10 `AnomalyController.java`（SSE 异常事件流）

```java
package com.plant.iq.web;

import com.plant.iq.detect.AnomalyEventSink;
import com.plant.iq.detect.StatSignal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/anomalies")
public class AnomalyController {

    private final AnomalyEventSink eventSink;

    public AnomalyController(AnomalyEventSink eventSink) {
        this.eventSink = eventSink;
    }

    /** SSE 异常事件流——v3 的 LLM 唤醒、v5 的工单都订阅它 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StatSignal> stream() {
        return eventSink.stream();
    }
}
```

### 3.11 单元测试 `EwmaAnomalyDetectorTest.java`

```java
package com.plant.iq.detect;

import com.plant.iq.ingest.SensorReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EwmaAnomalyDetectorTest {

    private final EwmaAnomalyDetector detector = new EwmaAnomalyDetector();

    @Test
    void normal_reading_no_signal() {
        for (int i = 0; i < 100; i++) {
            Optional<StatSignal> signal = detector.onReading(
                    new SensorReading("pump-01", 0.40 + (i % 5) * 0.001, 65.0, Instant.now()));
            assertThat(signal).isEmpty();
        }
    }

    @Test
    void vibration_spike_triggers() {
        // 先灌入正常基线
        for (int i = 0; i < 50; i++) {
            detector.onReading(new SensorReading("pump-01", 0.40, 65.0, Instant.now()));
        }
        // 振动突增 → 统计显著事件
        Optional<StatSignal> signal = detector.onReading(
                new SensorReading("pump-01", 0.85, 65.0, Instant.now()));
        assertThat(signal).isPresent();
        assertThat(signal.get().deviceId()).isEqualTo("pump-01");
    }
}
```

### 3.12 运行

```sh
export DEEPSEEK_API_KEY=sk-xxx            # v1 用不到，但避免启动时占位符告警
mvn spring-boot:run

# 模拟设备上报（MQTT 客户端发一条 JSON）：
# mosquitto_pub -h mqtt-broker -t factory/sensors/pump-01/reading \
#   -m '{"deviceId":"pump-01","vibration":0.85,"temp":65.3}'

# 订阅异常事件流（SSE）：
# curl -N http://localhost:8080/api/anomalies/stream
```

### 3.13 本节测试与验证（主迭代"时序检测"）

**前置条件**：TDengine `iq_plant` 库已建（§3.3 DDL）；MQTT broker 可连（`mqtt-broker:1883`）；`DEEPSEEK_API_KEY` 环境变量已导出（占位符避免启动告警）。

**材料——来自正文**：

```sh
# A. 模拟设备上报正常读数（mosquitto_pub；§3.12）
mosquitto_pub -h mqtt-broker -t factory/sensors/pump-01/reading \
  -m '{"deviceId":"pump-01","vibration":0.42,"temp":65.3}'

# B. 模拟注入异常：振动突增 → 应触发统计显著事件（§3.12）
mosquitto_pub -h mqtt-broker -t factory/sensors/pump-01/reading \
  -m '{"deviceId":"pump-01","vibration":0.85,"temp":65.3}'

# C. 订阅异常事件流（SSE，§3.12）
curl -N http://localhost:8080/api/anomalies/stream
```

```bash
# D. 单元测试（§3.11 EwmaAnomalyDetectorTest：正常 100 点无信号 / 突增触发）
mvn test -Dtest=EwmaAnomalyDetectorTest
```

```sql
-- E. 时序入库核对（TDengine）：pump-01 子表应有推送的读数行
SELECT * FROM sensor_pump_01 ORDER BY ts DESC LIMIT 5;
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `mvn clean test -Dtest=EwmaAnomalyDetectorTest` | 两个用例全绿：正常读数 `signal().isEmpty()`，振动 0.85 突增 `signal().isPresent()` |
| 2 | 间隔若干次推送材料 A（正常 0.42） | 时序库 `sensor_pump_01` 落行、字段不丢（材料 E count ≥ 推送次数，vibration/temp 正确） |
| 3 | SSE 订阅材料 C 后推送材料 B（0.85 突增） | 事件流秒级出现 `StatSignal`（deviceId=pump-01、zScore 显著），延迟 < 1s |
| 4 | 连续推送 100 次正常读数 | 事件流无异常事件（误触发率 < 3%，对应验收项 3） |
| 5 | 观察运行日志 / 事件流 | 全程无 LLM Token 消耗（零成本，对应验收项 5） |

**失败排查**：①SSE 无事件→EWMA 基线未灌（第 1 步单测保底）或 z 阈值未超；②时序库无行→MQTT topic 与 §3.9 `topic-pattern` 不一致或 TDengine 子表命名（`sensor_` + 净化后 deviceId）对不上；③启动缺 `MqttPahoMessageDrivenChannelAdapter` 类→`spring-integration-mqtt` 未加入 pom。

## 4. 本迭代的 ADR

### ADR 011-00：v1 时序检测用统计（EWMA）而非 LLM
- **决策**：检测层用 EWMA 残差监控（确定性、零成本），LLM 解释留给 v3
- **取舍理由**：检测是每点实时判定的地基，统计在 CPU 上持续跑零 Token；LLM 只对"统计显著事件"做叙事解释（v3 双轨制），Token 降 12 倍
- **参考**：[调研 工业质检 2026 §PdM 双轨制]、[教程 30-容错与弹性设计 §异常检测]

### 4.1 本节核对（本迭代 ADR）

- [ ] ADR-011-00 的"检测层用统计（EWMA）而非 LLM"与正文 §3.7 `EwmaAnomalyDetector` 实现、§1 四问第 3 行"零 LLM 的确定性数据管线"三处口径一致
- [ ] 该 ADR 与 13-ADR架构决策记录 的 ADR-011-00 编号衔接、无重复

## 5. 全篇回归验证

**回归断言**（§3.13 本节测试与验证通过后，按 §2 验收表整体验收）：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 连续推送正常读数 ≥ 7 天回测样本 | 正常工况误触发率 < 3%（验收项 3） |
| 2 | 回放历史故障时段，统计已知异常时刻 | 注入/历史异常 EWMA 触发率 ≥ 95%（验收项 2） |
| 3 | 抽样比对时序库行数与推送数 + 事件流时间戳 | 字段 100% 不丢（验收项 1）；采集→信号延迟 < 1s（验收项 4） |
| 4 | 检查日志与 token 观测 | 全程零 LLM Token（验收项 5） |

**失败排查**：触发率 < 95%→`Z_THRESHOLD=3.0` 过严或 EWMA 平滑因子需调；误触发高→基线方差初始化（`0.01`）偏小放大了早期抖动；字段丢失→MQTT payload 与 §3.6 `SensorReading` 字段名（deviceId/vibration/temp）不一致。

## 6. 验收与已知痛点

**验收**：MQTT 数据 100% 入时序库、注入异常触发率 ≥ 95%、正常误触发 < 3%、检测延迟 < 1s、零 Token。

**已知痛点（驱动下一迭代）**：
1. 时序异常能测了，但**质检靠人眼**——注塑件气孔/缺料漏检多、判级不一
2. 边缘 CV 快筛（YOLO）与多模态 LLM 精判的接力架构还没建
3. 检测信号只有数值，没有可读解释（v3 解决）

> **定位回顾**：v1 立住"感知层"——数据进得来、异常测得准。下一站 [02-视觉质检多模态.md](02-视觉质检多模态.md)。
