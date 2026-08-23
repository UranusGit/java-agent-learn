# 02 核心 API：创建方式 · Context 状态袋 · KeyValue 基数

> **定位**：第 01 关你把"一次观测的一生"看清楚了。这一关给观测装上**大脑与口袋**：用不同方式创建它、用 Context 携带状态、用 KeyValue 打标签——并且讲清那个贯穿始终的**基数**分辨。这是你从"看懂一次观测"走向"会造自己的观测类型"的桥梁。
>
> **进阶路径**：在之前工程上加"核心 API"这一层，新增 `ObsStep3Config`。
>
> **前置**：[01 生命周期深挖] 已跑通。
>
> **版本基准**：Spring Boot 4.1.0 + micrometer-observation 1.17。代码已实测。

---

## 1. 三种创建方式：从"名字 + 自动 Context"到"全约定式"

第 01 关你用了最常用的一种（方式①）。完整有三种，别只用一种：

```java
// 方式①：名字 + Registry（Context 自动创建）——你已经用过
Observation obs1 = Observation.createNotStarted("task.run", () -> new Observation.Context(), registry);

// 方式②：携带自定义 Context（领域观测上下文）——第 04 关的关键
MyTaskContext ctx = new MyTaskContext("t-1", "extract");
Observation obs2 = Observation.createNotStarted("task.run", () -> ctx, registry);

// 方式③：全约定式——命名与标签全交给 Convention（第 05 关展开）
Observation.createNotStarted(new MyConvention(), () -> new Observation.Context(), registry);
```

> **⚠ 一个初学者必踩的坑（实测）**：方式②的第二个参数是 **`Supplier<Context>`**，不是 Context 实例。你写 `createNotStarted("x", ctx, registry)`（直接传实例）会编译报错——必须 `() -> ctx`。原因：框架需要"惰性创建" Context（观测还没 start 时不一定马上要）。

**为什么需要方式②（自定义 Context）？** 因为 Handler（第 04 关的审计）要通过 `supportsContext` 识别你**带了什么样的上下文**。用一个默认 `Observation.Context` 时，所有观测都是一模一样的"通用"观测；用自定义的 `MyTaskContext`，Handler 就能 `instanceof MyTaskContext` 判断"这是任务观测"并拿到业务字段。**这是领域观测的基石。**

---

## 2. Context 状态袋：不只是标签，还装"对象"

`Observation.Context` 干两件事：

**① 装 KeyValue（标签）**——`addLowCardinalityKeyValue(...)` 等，第 01 关见过。
**② 装任意对象（`put(key, value)` / `get(key)`）**——Handler 与业务之间传递"业务对象"的通道，**这是 Context 最强大也最易被忽视的一点**。

看 Context 的完整真实 API（javap 实证，都是你后面会用的）：

```
public class Observation$Context implements Observation$ContextView {
    // —— 标签操作 ——
    public Context addLowCardinalityKeyValue(KeyValue)          // 加低基数标签
    public Context addHighCardinalityKeyValue(KeyValue)         // 加高基数标签
    public KeyValues getLowCardinalityKeyValues()               // 读低基数标签集合
    public KeyValues getHighCardinalityKeyValues()              // 读高基数标签集合
    public KeyValues getAllKeyValues()                          // 读全部

    // —— 状态袋（装任意对象）——
    public <T> Context put(Object key, T value)                 // 存对象
    public <T> T get(Object key)                                // 取对象
    public <T> T remove(Object key)                             // 删
    public <T> T getRequired(Object key)                        // 取（必存在，否则抛）
    public Object computeIfAbsent(Object key, Function)         // 取或算

    // —— 生命周期信息 ——
    public ObservationView getParentObservation()               // 父观测（第 07 关父子树）
    public Throwable getError()                                 // 本次观测的错误（第 01 关见过）
    public String getName() / setName()                         // 名
    public String getContextualName() / setContextualName()     // Span 展示名
}
```

看例子（在 `ObsStep3Config` 里加一个接口演示 put/get）：

```java
.andRoute(GET("/api/ctx"), req -> {
    Observation.Context ctx = new Observation.Context();
    Observation.createNotStarted("api.ctx", () -> ctx, registry)
            .observe(() -> {
                // put 一个"业务中间结果"进 Context（不必是标签，可以是任意对象）
                ctx.put("startedAt", System.nanoTime());          // 存一个 Long
                ctx.put("中间结果", new java.util.HashMap<>());      // 存一个 Map 对象
                return "stored";
            });
    Long startedAt = ctx.get("startedAt");                        // get 读回
    return ServerResponse.ok().bodyValue("startedAt=" + startedAt);
});
```

> **`put/get` 与 KeyValue 的区别（易混，实测）**：`put/get` 收的是**任意对象**（`Context.put(Object, T)`，key 是 Object 引用，常以静态常量或 Class 做 key）；KeyValue 是**指标/标签**。别用字符串去翻标签——写 Handler 时用领域 Context 的类型化 getter（第 04 关示范）。
>
> **⚠ 生命周期提醒**：Context 的存活期 = 观测的存活期（[01 §生命周期] 讲观测 stop 即结束）。所以别往 Context 塞大对象又长活——会阻止 GC。另一个坑：观测 stop 之后改 Context 的标签已经晚了（[01 §onStop 快照]），要补标签走 ObservationFilter（[05 §Filter]）。

---

## 3. KeyValue 与基数：管好你指标的生死线

这是 Observation 里**最重要的一条认知**，这篇一次说透。

### 3.1 什么是低基数 / 高基数

```java
KeyValue low  = KeyValue.of("path", "/hello");            // 取值有限 → 低基数
KeyValue high = KeyValue.of("session.id", "s-12345");      // 取值无界 → 高基数
```

| | 低基数 lowCardinality | 高基数 highCardinality |
|---|---|---|
| **取值空间** | 有界（method/status/path/model/tool.name） | 无界（sessionId/userId/traceId/完整参数） |
| **进指标 tag 吗** | ✅ 是 | ❌ 否（只进 Span） |
| **典型例子** | `tool.name`、`model`、`status`、`path` | `session.id`、`user.id`、`trace.id` |

### 3.2 为什么不能把高基数放指标里

因为指标系统（Prometheus）按**"标签组合"存时间序列**：

```
时间序列数 = Σ(每个指标名 × 其标签取值组合数)
例：hello.request{path=2 种}  = 2 条序列（可接受）
    hello.request{session.id=1 万} = 1 万条序列（Prometheus 内存爆炸）
```

所以**低基数 = 指标 tag；高基数 = 只进 Span**。你后续写 Convention、审计、成本、治理，处处要问："这个标签取值有没有上界？"

### 3.3 亲手验证基数分家（跑 `api/base` 后用 actuator 看）

`application.yaml` 确保暴露 metrics 端点（[03] 会细讲），先调接口再查指标：

```bash
curl http://localhost:18080/api/base      # 触发一个"低基数 status + 高基数 user.id"的观测
curl http://localhost:18080/actuator/metrics/api.base
```

你会看到：

```json
{
  "availableTags": [ {"tag":"status","values":["ok"]} ],   // ← 只有低基数 status
  "measurements": [ {"statistic":"COUNT","value":1.0} ],
  "name": "api.base"
}
```

**高基数 `user.id` 没有出现在 `availableTags`**——它被隔离进了 Span（本机没配 tracing 时，你只能看到它被"隔离"的效果；第 07 关你会看到它在 Span 里）。这就是基数纪律的机器证明，也是全书最重要的一条直觉。

---

## 4. 动手：ObsStep3Config（核心 API 演示完整版）

把上面三块的接口集合成一个配置类（包 `demo.demo01.step3`），**同样用 `@RestController` 注册接口**，完整可复制：

```java
package demo.demo01.step3;

import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class ObsStep3Config {

    private final ObservationRegistry registry;   // 注入 Boot 的注册表（00 关铁律）

    public ObsStep3Config(ObservationRegistry registry) {
        this.registry = registry;
    }

    // ---------- ① 三种创建方式（方式② 自定义 Context）----------
    @GetMapping("/api/create")
    public String createDemo() {
        TaskContext ctx = new TaskContext("t-1");
        Integer n = Observation.createNotStarted("api.create",
                        () -> ctx,                          // 方式②：自定义 Context（Supplier！）
                        registry)
                .lowCardinalityKeyValue("way", "ctx")
                .observe(() -> 42);
        return "create n=" + n + ", ctx.getTaskId=" + ctx.getTaskId();
    }

    // ---------- ② Context 状态袋 put/get ----------
    @GetMapping("/api/ctx")
    public String ctxDemo() {
        Context ctx = new Context();
        Observation.createNotStarted("api.ctx", () -> ctx, registry)
                .observe(() -> {
                    ctx.put("startedAt", System.nanoTime());
                    ctx.put("中间对象", Map.of("k", "v"));
                    return 1;
                });
        Long startedAt = ctx.get("startedAt");
        return "startedAt=" + startedAt;
    }

    // ---------- ③ 基数演示 ----------
    @GetMapping("/api/base")
    public String baseDemo() {
        return Observation.createNotStarted("api.base", () -> new Context(), registry)
                .lowCardinalityKeyValue("status", "ok")
                .highCardinalityKeyValue("user.id", "u-" + System.nanoTime())
                .observe(() -> "base done");
    }

    // 领域 Context：第 04 关正式展开，这里先给方式②一个最简单形态
    public static class TaskContext extends Context {
        private final String taskId;
        public TaskContext(String taskId) { this.taskId = taskId; }
        public String getTaskId() { return taskId; }
    }
}
```

跑起来调三个接口，重点看 `api/base` 的指标（§3.3）：

```bash
curl http://localhost:18080/api/create
curl http://localhost:18080/api/ctx
curl http://localhost:18080/api/base
curl http://localhost:18080/actuator/metrics/api.base
```

---

## 5. 接口测试：断言基数的正确分流

用 `TestObservationRegistryAssert`（[01 §6]）断言"哪个标签在、标签值对不对、高低基数分流"：

```java
package demo.demo01.step3;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;

class KeyValueTest {

    @Test
    void base_has_low_and_high_distinctly() {
        TestObservationRegistry reg = TestObservationRegistry.create();
        Observation.createNotStarted("api.base", () -> new Observation.Context(), reg)
                .lowCardinalityKeyValue("status", "ok")
                .highCardinalityKeyValue("user.id", "u-1")
                .observe(() -> {});
        TestObservationRegistryAssert.assertThat(reg)
                .hasObservationWithNameEqualTo("api.base")
                .that()
                .hasLowCardinalityKeyValue("status", "ok")       // 低基数可断言
                .hasHighCardinalityKeyValue("user.id", "u-1");    // 高基数可断言（进 Span）
    }
}
```

> 断言失败消息精确到"哪个 key、期望值 vs 实际值"（实测）：`Observation should have a low cardinality tag with key <status> and value <ok>. The key is correct but the value is <ng>`。

**这个测试的价值**：它把"基数分家对不对"变成了一个可复现的断言。你以后写 Convention（[05]）/ 领域 Context（[04]）时，都能用类似断言保证"标签进对通道"。

---

## 6. 这一关我该体会到的知识点（关联展开）

这一关你开始从"一次观测"走向"观测的规律"：

- **三种创建方式**：方式②（自定义 Context）是领域观测的钥匙。
- **Context 是观测的"口袋"** → 第 04 关用**领域 Context** 装"任务/工具"让 Handler 类型化识别。
- **KeyValue 是观测的"标签"** → 第 05 关用 **Convention** 批量定标签、Filter 加工标签。
- **基数分家是整套体系的红线** → 第 06 关指标治理、第 08 关综合项目都会回到它。

目前我们还在**手写观测**（`createNotStarted`）。下一关 [03 Boot 自动装配] 你将看到：**很多观测（尤其 `http.server.requests`）Boot 已经替你埋好了**，你不用写。这是"从手写走向框架"的转折点。

---

## 7. 适用场景与不适用场景（这一关）

**适用**：给业务特定边界（工具、任务、外部调用）建带状态、带标签的观测；理解基数。

**不适用**：想知道"怎么不写代码就有观测"——下一关；想知道"怎么批量定制观测"——[05]。

---

## 8. 常见误区（这一关）

1. **`createNotStarted(String, ctx, registry)` 直接传 Context 实例**——编译不过，要 `() -> ctx`（实测）。
2. **把高基数标签（userId）塞进 lowCardinalityKeyValue**——指标序列爆炸；一律 high，进 Span。
3. **用 `Context.get("标签名")` 去翻 KeyValue**——get 拿的是 put 进去的对象，不是标签；标签用领域 getter 或 KeyValues 遍历。
4. **往 Context 塞大对象又长活**——Context 存活期=观测存活期，长活引用阻止 GC。
5. **在观测 stop 后再改 KeyValue**——晚了，onStop 已快照；补标签走 ObservationFilter（[05 §Filter]）。

---

## 9. 总结

这一关你给观测装了口袋（Context 状态袋）与人设（高/低基数标签），并亲手验证了基数分家：**低基数进指标 tag、高基数只进 Span**。三种创建方式里，方式②（自定义 Context）是你后续做领域观测的钥匙。

下一关 [03 Boot 自动装配]：让 Spring 替你埋 `http.server.requests`，看零插桩观测长什么样，并学会注入 `ObservationRegistry` Bean。

**外部来源**：[Micrometer Observation Javadoc](https://javadoc.io/doc/io.micrometer/micrometer-observation) · [Prometheus 基数概念](https://prometheus.io/docs/practices/naming/)
