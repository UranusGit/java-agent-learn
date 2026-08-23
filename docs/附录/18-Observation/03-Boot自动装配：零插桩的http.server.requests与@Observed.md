# 03 Boot 自动装配：零插桩的 http.server.requests 与 @Observed

> **定位**：前两关你一直在**手写观测**。这一关让你看到**另一面**：Spring Boot 自动装好的"水电煤"——一个 Actuator 依赖，就替你埋好了 `http.server.requests`，还自动装配了 `ObservationRegistry`。你只要注入它、或者用 `@Observed` 注解，就能零插桩获得观测。**从"手写"走向"框架"**，这是从原理落地到生产的关键转折。
>
> **进阶路径**：把工程从"手写注册表"升级为"注入 Boot Bean"，并体验框架自带的自动化。
>
> **前置**：[02 核心 API] 已跑通。
>
> **版本基准**：Spring Boot 4.1.0 + micrometer 1.17。代码已实测。

---

## 1. 一个大转变：从"自己造注册表"到"注入 Boot 的 Bean"

从 00 关起，我们就一直**注入 Boot 的 `ObservationRegistry`**（00 关开局铁律）。这一关我们看清：为什么"注入"和"自己 `ObservationRegistry.create()` 造一个"天差地别——后者是**网上不少旧例踩的坑**，会绕过所有自动配置，框架埋的点全跟你没关系：

```java
// 正确的（本系列一直沿用）：构造器注入 == Boot 的 Bean
private final ObservationRegistry registry;                 // 单例、已带 handler/配置
public MyConfig(ObservationRegistry registry) {
    this.registry = registry;
}

// 错误示范（旧例常见，00 关坑 0）：自建裸注册表，handler 挂不上
private final ObservationRegistry registry = ObservationRegistry.create();
```

**它替你做（省）了什么？** 你手写的话，Handler（指标、Span）、Filter、Predicate 都要自己手动注册到 registry。Boot 自动装配帮你：

- **创建 `ObservationRegistry` Bean**（实测类型 `SimpleObservationRegistry`）；
- **自动注册指标 Handler**：`MeterObservationHandler`（把观测变成 Timer→指标）；
- **自动埋 HTTP 出入口**：`ServerHttpObservationFilter`（这就是 `http.server.requests` 的来源）；
- **自动注册各类内置 Convention**（HTTP/日志等）；
- **收集你的扩展 Bean**：你写的 Handler/Filter/Convention 注册为 `@Bean` 时被自动收集——第 04/05 关全靠这个，你不用手写注册。

> **关键（避免坑）**：永远**注入 Boot 的 Bean**，别 `new ObservationRegistry()` 想替代——那会绕过所有自动配置，框架埋的点全跟你没关系（[03 §常见误区] 专讲）。

---

## 2. 零插桩观测：http.server.requests

用这个工程（[00] 已含 actuator），**只要有个接口**（不写任何观测代码），框架就自动观测你的 HTTP 请求。

新建 `ObsStep4Config.java`（包 `demo.demo01.step4`），只写业务，零观测代码：

```java
package demo.demo01.step4;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObsStep4Config {

    @GetMapping("/auto/hello")
    public String hello() {
        // 普通业务接口，一行观测代码都没有
        return "auto hello";
    }
}
```

跑起来调接口：

```bash
curl http://localhost:18080/auto/hello
```

控制台出现 `http.server.requests`——**这是 Boot 的 `ServerHttpObservationFilter` 自动埋的，你没写一行观测代码**：

```
START - name='http.server.requests', context=null, error='null', low=[exception='none', method='GET', outcome='SUCCESS', status='200', uri='UNKNOWN'], high=[http.url='/auto/hello']
  STOP - name='http.server.requests', contextualName='http get /auto/hello', low=[exception='none', method='GET', outcome='SUCCESS', status='200', uri='/auto/hello'], ...
```

### 两个关键读点（实测）

**① `uri` 后置解析（很重要）**：START 时 `uri='UNKNOWN'`、STOP 时 `uri='/auto/hello'`——WebFlux 在处理完成、stop 时才解析出**路由模板**。这是 `uri` 能保持低基数的原因：它记录的是**模板**（`/user/{id}`），不是真实 URL。**❌ 别让 uri 变成高基数**：如果路由没参数化（直接 `/user/123`），那每来一个新用户就多一条 `uri=...` 序列 → 爆炸（[02 §3 基数]）。排障口诀：`GET /actuator/metrics/http.server.requests` 看 `availableTags` 里 `uri` 的取值数大不大。

**② 低基数标签自动规格统一**：`method/status/outcome/exception` 全都有。团队一看就知道"HTTP 出问题该看哪些维度"。

---

## 3. 指标接口：看到"低基数进指标"的实景 + 全局公共标签

在工程里注入 Bean 写一个业务观测，再用 actuator 看 `measurements`（[02 §3.3] 你已验证过一次，这关用注入 Bean 的正规姿势）：

```java
package demo.demo01.step4;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObsStep4MetricConfig {

    private final ObservationRegistry registry;   // ← 注入 Boot 的 Bean

    public ObsStep4MetricConfig(ObservationRegistry registry) {
        this.registry = registry;                 // Boot 已自动装配好
    }

    @GetMapping("/auto/mock")
    public String mock() {
        return Observation.createNotStarted("gen_ai.mock", () -> new Observation.Context(), registry)
                .lowCardinalityKeyValue("model", "mock")
                .observe(() -> "mock ok");
    }
}
```

「配置 `application.yaml` 暴露 metrics 端点」：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

跑一下再看指标：

```bash
curl http://localhost:18080/auto/mock
curl http://localhost:18080/actuator/metrics/gen_ai.mock
```

```json
{
  "availableTags": [ {"tag":"model","values":["mock"]}, {"tag":"error","values":["none"]} ],
  "measurements": [ {"statistic":"COUNT","value":1.0} ],
  "name": "gen_ai.mock"
}
```

**读它（对标第 02 关基数）**：`availableTags` 里只有低基数 `model`/`error`（进指标 tag）；你加的高基数标签**不出现**（只进 Span）。多 curl 几次，`COUNT` 递增——一个 Timer 的 `COUNT/TOTAL_TIME/MAX`（[06 §2] 会讲透）。

### 3.1 全局公共标签（用配置，不改代码）

想所有观测都带 `application`/`env`（方便按服务筛选），不用改代码：

```yaml
management:
  observations:
    key-values:
      application: demo01
      env: dev
```

之后所有观测（含 `http.server.requests`, `gen_ai.mock`）都多 `application=demo01, env=dev` 两个低基数 tag——这才是"全局公共标签"（等价旧版 common tags）。

---

## 4. @Observed：注解即埋点（方法边界）

不想手写 `Observation.createNotStarted`，可以用 `@Observed` 注解一个方法。它需要 AOP（ObservedAspect），pom 补：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

```java
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    // 方法被自动观测：进入时 start、返回时 stop；注解只给"名"
    @Observed(name = "agent.conversation", contextualName = "handle-conversation")
    public String handleConversation(String userId, String message) {
        return "processed " + message;   // 返回后自动 stop
    }
}
```

### @Observed 四条边界（必须知道，否则踩坑）

1. **走 Spring AOP 代理**——同类内部自调用（`this.handleConversation()`）不过代理，注解失效（经典 AOP 盲区，与 Spring AI 的 Advisor 链语义同源）。
2. **返回 Mono/Flux 的别用**——切面在**订阅前**就 stop，而 Mono 真正在订阅时执行（冷流）。响应式方法改用 Reactor 观测（[07 §Reactor]）。
3. **注解只负责"有与名字"**——标签一律用 Convention 补（[05 §Convention]；`@Observed` **没有** `lowCardinalityKeyValue` 属性，那是虚构 API）。
4. **虚拟线程（Java 21）下照常工作**——AOP 包在方法执行线程上，Scope 语义有效（非常适合阻塞式工具方法）。

---

## 5. 自动埋点全景：Boot 替你埋了哪些（你看不到的）

装的 starter 不同，Boot 自动埋的点不同（你"免费"得到的观测）：

| 埋点 | 触发组件 | 观测名 | 关键低基数标签 |
|------|---------|--------|---------------|
| 服务端 HTTP | `ServerHttpObservationFilter` | `http.server.requests` | method/uri(模板)/status/outcome/exception |
| 客户端 HTTP | WebClient/RestClient | `http.client.requests` | method/uri/status/outcome/client.name |
| 注解方法 | `@Observed` + `ObservedAspect` | 你给的 name | 你给的（标签走 Convention）|
| Spring AI 全家 | ChatClient/ChatModel/Tool/VectorStore | `gen_ai.*` / `spring.ai.*` | gen_ai.system/model、tool.name 等 |
| Kafka | spring-kafka 观测集成 | 客户端指标族 | — |

> 这一关你真正体会到"**插桩一次、按门面产出**"里"框架端帮你插桩"的那部分：`http.server.requests` 你**不写代码就有**；Spring AI 的工具/模型观测也是 Spring AI 内部帮你埋好（第 08 关的 Agent 就是）。

---

## 6. 你从这一关得到什么（零插桩 + 注入 Bean）

| 之前（02 关） | 这一关之后 |
|---|---|
| 手写 `ObservationRegistry.create()` | 注入 Boot Bean（单例、带配置、自动收集扩展）|
| 只观测自己写的 | 多了免费的 `http.server.requests`（HTTP 出入口）|
| 无指标接口 | `/actuator/metrics/<name>` 看指标与 tag |
| 手写埋点才能观测 | 可用 `@Observed` 注解方法边界 |

---

## 7. 这一关我该体会到的知识点（关联展开）

- **注入 Bean 而非 `new`**：Boot 的 `ObservationRegistry` 单例 + 自动装配了 Handler/Filter，你的扩展 Bean 会被自动收集（[04/05] 靠它）。
- **`http.server.requests` 是所有人的共享入口观测**：uri 模板低基数、method/status 规格统一。
- **`@Observed` 是方法边界快捷方式**：走 AOP，注意自调用与响应式边界。
- **配置 vs Bean 分工**：开关/公共标签用配置（声明式、profile 友好），逻辑性定制写 Bean（[05]）。能用配置的用配置。

---

## 8. 适用场景与不适用场景（这一关）

**适用**：标准 Spring Boot 服务"零插桩"获得 HTTP 观测；想用配置（key-values/enable）管理观测而不写 Bean；给方法边界用 `@Observed`。

**不适用**：非 Spring 环境（纯库，没有容器可注入）——只能用 `ObservationRegistry.create()` 自建注册表（此时记得手动 `observationConfig().observationHandler(...)` 挂 handler，否则像 00 关坑 0 那样白搭）；**在 Spring 工程里**手写 `new ObservationRegistry()` 想替代自动装配——会绕过所有自动配置，Observer 全部失联。

---

## 9. 常见误区（这一关）

1. **手写 `new ObservationRegistry()` 和自动装配混用**——两套注册表各持 Handler，一半观测消失；用注入 Bean + `ObservationRegistryCustomizer` 定制。
2. **`management.endpoints.web.exposure.include` 没加 metrics**——`/actuator/metrics` 空，以为是埋点问题；先看端点暴露。
3. **`uri` 出现真实 URL/查询串**——路径没参数化，基数爆炸；用路由模板 `/user/{id}`。
4. **把扩展 Bean 放"构造器注入 registry 的主配置类"**——可能循环依赖 `主类 ↔ registry`；扩展 Bean 放独立 `@Configuration`（[04 §4]示范，真实坑）。
5. **`@Observed` 标在返回 Mono/Flux 的方法上**——订阅前已 stop；走 Reactor（[07 §Reactor]）。
6. **给 `@Observed` 造不存在的属性**（如 `lowCardinalityKeyValue = {...}`）——虚构 API；标签进 Convention。

---

## 10. 总结

这一关你从"自己造门面"升级到"注入 Boot Bean + 零插桩 HTTP 观测"：**一个 `/actuator/metrics/gen_ai.mock` 看指标和 tag、`http.server.requests` 免费就有、`@Observed` 标方法边界、配置管全局标签**。从此你的工程"天然有观测"。

但注意：`http.server.requests` 是**通用**的，它不知道你的业务。下一关 [04 自定义扩展一] 用**领域 Context + 类型化 Handler** 给观测装上真正的业务语义（审计），你能自己造"懂业务的观测"了。

**外部来源**：[Spring Boot Observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html) · [Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
