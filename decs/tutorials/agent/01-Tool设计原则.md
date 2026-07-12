# Agent 01 - Tool 设计原则

> Tool 写出来不难，**写好很难**。本节是 Agent 工程的核心。
> 一个 Agent 的好坏，80% 取决于 Tool 设计。

---

## 1. 为什么 Tool 设计是核心

### 1.1 同样的需求，两种 Tool 设计

**坏设计**：
```java
@Tool("查询")
public Object query(String type, String param1, String param2, String param3) {
    // 一个 Tool 干所有事
}
```

LLM 表现：
- 不知道何时调用
- 不知道填什么参数
- 经常调错

**好设计**：
```java
@Tool("根据员工姓名查询工号")
public EmployeeInfo queryEmployeeByName(@ToolParam("中文全名") String fullName) { ... }

@Tool("根据工号查询工位")
public String queryWorkstation(@ToolParam("6位工号") String employeeId) { ... }
```

LLM 表现：
- 调用准确率 90%+
- 参数填对率 95%+

### 1.2 核心结论

> **Tool 描述是给 LLM 看的"接口契约"**。
> 写得像优秀 PR 文档，LLM 就聪明；写得像草稿，LLM 就智障。

---

## 2. 五条铁律

### 铁律 1：单一职责

一个 Tool 只做一件事。

#### ❌ 反例
```java
@Tool("员工管理")
public Object manageEmployee(String action, String name, String dept, ...) {
    switch (action) {
        case "query": ...
        case "create": ...
        case "delete": ...
    }
}
```

LLM 困惑：何时用？怎么填 action？参数顺序？

#### ✅ 正例
```java
@Tool("根据姓名查询员工")
public EmployeeInfo queryByName(@ToolParam("姓名") String name) { ... }

@Tool("创建员工")
public EmployeeInfo create(@ToolParam("姓名") String name, @ToolParam("部门") String dept) { ... }

@Tool("删除员工")
public void delete(@ToolParam("工号") String id) { ... }
```

### 铁律 2：描述要回答"三个问题"

```
1. 这个工具做什么？
2. 什么场景下应该用？
3. 返回什么？
```

#### ✅ 优秀描述模板
```java
@Tool("""
    根据员工姓名查询其工号、部门、入职日期。
    使用场景：用户询问某员工的基础信息、想找人、需要工号时。
    返回：EmployeeInfo 对象（含 id、name、department、hireDate）
    """)
```

#### ❌ 烂描述
```java
@Tool("查询员工")
```

LLM 不知道：能查啥？什么时候用？

### 铁律 3：参数描述要具体到格式

```java
@Tool("查询指定日期的天气")
public Weather getWeather(
    @ToolParam("城市名，中文，如 '北京'、'上海'") String city,
    @ToolParam("日期，格式 yyyy-MM-dd，如 '2026-07-12'") String date
) { ... }
```

**关键**：
- 写明格式（"yyyy-MM-dd"）
- 给出示例（"如 '2026-07-12'"）
- 说明约束（"中文"、"6 位数字"）

### 铁律 4：参数类型用基本类型

LLM 填嵌套对象的成功率很低。优先用：

| 类型 | 推荐度 |
|------|--------|
| `String` | ⭐⭐⭐⭐⭐ |
| `int`/`long`/`double` | ⭐⭐⭐⭐⭐ |
| `boolean` | ⭐⭐⭐⭐⭐ |
| `enum` | ⭐⭐⭐⭐ |
| `List<String>` | ⭐⭐⭐ |
| 自定义嵌套对象 | ⭐ 不推荐 |

如果必须传复杂结构，**用 JSON 字符串 + 内部解析**：

```java
@Tool("创建订单，参数是 JSON：{\"product\":\"\",\"qty\":1,\"address\":\"\"}")
public Order createOrder(@ToolParam("JSON 格式订单数据") String orderJson) {
    OrderSpec spec = objectMapper.readValue(orderJson, OrderSpec.class);
    return orderService.create(spec);
}
```

### 铁律 5：返回值要"自描述"

LLM 看到 Tool 返回值，要能直接理解。

#### ❌ 返回值不友好
```java
@Tool("查询订单")
public String queryOrder(String id) {
    return "10086";   // LLM 不知道这是什么
}
```

#### ✅ 返回值清晰
```java
@Tool("查询订单")
public OrderInfo queryOrder(@ToolParam("订单号") String id) {
    return new OrderInfo(id, "iPhone 15", 1, 5999.0, "已发货");
}

public record OrderInfo(
    String orderId,
    String product,
    int quantity,
    double totalPrice,
    String status
) {}
```

LLM 看到字段名就知道怎么解读，不需要再问。

---

## 3. Tool 命名规范

### 3.1 方法名

虽然 LLM 主要看 `@Tool` 描述，但方法名也要规范：
- 用**动词**开头：`query`、`create`、`update`、`delete`、`send`、`generate`
- 避免缩写：`getEmpInfo` ❌ → `getEmployeeInfo` ✅
- 避免单字母：`q` ❌ → `query` ✅

### 3.2 参数名

LLM 会看到参数名（不是只有 `@ToolParam` 描述）：

```java
// ❌ LLM 困惑
public Order q(String i) { ... }

// ✅ LLM 清晰
public Order queryOrderById(String orderId) { ... }
```

---

## 4. 错误处理

### 4.1 设计原则

```
Tool 抛异常 → LLM 收到错误信息 → LLM 决策（重试/换工具/告知用户）
```

### 4.2 三种错误处理策略

#### 策略 1：抛异常（让 LLM 自己决策）
```java
@Tool("根据工号查询员工")
public EmployeeInfo queryEmployee(String id) {
    EmployeeInfo info = repo.findById(id);
    if (info == null) {
        throw new EmployeeNotFoundException("工号 " + id + " 不存在");
    }
    return info;
}
```

**适用**：偶发错误、外部依赖失败、需要 LLM 重试。

#### 策略 2：返回错误信息（让 LLM 告知用户）
```java
@Tool("根据工号查询员工")
public Object queryEmployee(String id) {
    EmployeeInfo info = repo.findById(id);
    if (info == null) {
        return Map.of("error", "工号 " + id + " 不存在，请确认");
    }
    return info;
}
```

**适用**：业务校验失败、用户输入错误。

#### 策略 3：返回空值/默认值（隐藏错误）
```java
@Tool("查询天气")
public Weather getWeather(String city) {
    try {
        return weatherApi.get(city);
    } catch (Exception e) {
        log.warn("Weather API failed", e);
        return Weather.unknown(city);  // 默认值
    }
}
```

**适用**：非关键 Tool、可降级场景。

### 4.3 错误信息友好度

#### ❌ 错误信息不友好
```java
throw new RuntimeException("error");   // LLM 不知道怎么办
```

#### ✅ 错误信息引导 LLM
```java
throw new RuntimeException(
    "工号格式错误，应为 6 位数字。当前输入: '" + id + "'。" +
    "请重新询问用户工号。"
);
```

---

## 5. 幂等性

### 5.1 为什么重要

Agent **可能重复调用同一个 Tool**（如 LLM 不确定结果时）。
- 幂等 Tool：重复调用无害
- 非幂等 Tool：可能造成数据混乱

### 5.2 设计建议

- **查询类 Tool**：天然幂等，无忧
- **创建类 Tool**：传入客户端生成的 requestId，重复调用检查
- **更新类 Tool**：用乐观锁（version 字段）
- **删除类 Tool**：先查再删，已删时返回成功

```java
@Tool("创建订单")
public Order createOrder(
    @ToolParam("订单内容") String product,
    @ToolParam("客户端生成的 requestId，防止重复创建") String requestId
) {
    if (orderRepo.existsByRequestId(requestId)) {
        return orderRepo.findByRequestId(requestId);  // 幂等返回
    }
    return orderRepo.create(product, requestId);
}
```

---

## 6. 性能优化

### 6.1 Tool 应该快

LLM 调 Tool 是同步阻塞的（在迭代循环里）。Tool 慢 = 整个 Agent 慢。

**目标**：Tool 内部操作 < 2 秒。

### 6.2 优化手段

| 手段 | 说明 |
|------|------|
| 缓存 | 查询类 Tool 加缓存（如 Caffeine） |
| 索引 | 数据库字段加索引 |
| 批量 | 一次返回多条而不是多次查询 |
| 异步 | 长任务返回 taskId，后续查询 |

### 6.3 超时控制

```java
@Tool("查询天气")
public Weather getWeather(String city) {
    return weatherApi.get(city)
            .timeout(Duration.ofSeconds(5))   // 单次最多等 5 秒
            .block();
}
```

---

## 7. Tool 分组（解决工具过多）

### 7.1 问题

Agent 工具超过 10 个时：
- LLM 选错工具的概率显著上升
- Token 浪费（每个 Tool 的 schema 都要发给 LLM）

### 7.2 解决：分组路由

```
顶层 Agent（路由）
   ├── HR Agent（人事相关 Tool）
   │   ├── queryEmployee
   │   └── queryDepartment
   ├── IT Agent（IT 相关 Tool）
   │   ├── queryK8s
   │   └── queryPrometheus
   └── 业务 Agent（业务相关 Tool）
```

实现见 [03-多 Tool 编排](./03-多Tool编排.md)。

---

## 8. Tool 描述的"打磨"工作流

### 8.1 第一遍：写得差不多就行
基于直觉写一版。

### 8.2 第二遍：跑测试集
准备 20 条用户问题，看 LLM 选 Tool 的准确率。

### 8.3 第三遍：根据失败案例迭代

每个失败案例都暴露 Tool 描述的缺陷：
- 调错 Tool → 描述不够差异化
- 参数填错 → 参数描述不清晰
- 该调不调 → 没说清"何时使用"

**示例迭代**：

```java
// V1: 60% 准确率
@Tool("查询天气")

// V2: 80% 准确率
@Tool("根据城市名查询天气。返回温度、湿度、天气情况")

// V3: 95% 准确率
@Tool("""
    根据城市名查询当前天气。
    使用场景：用户问'天气'、'温度'、'下雨吗'、'穿什么'等。
    不适用：用户问历史天气（用 queryHistoricalWeather）。
    返回：{ temperature: 度数, humidity: 百分比, condition: 描述 }
    """)
```

---

## 9. Tool 设计的反模式速查

| 反模式 | 表现 | 改进 |
|--------|------|------|
| 一个 Tool 干多事 | `query(type, ...)` | 拆成多个 Tool |
| 描述含糊 | "查询" | 写清"查什么、何时用、返回什么" |
| 参数无格式 | `String date` | `yyyy-MM-dd 格式` |
| 嵌套对象参数 | `UserDTO user` | 拆成扁平字段 |
| 返回值无字段名 | 返回 `String` | 返回 record/POJO |
| 不处理异常 | 抛 `Exception` | 抛带提示的运行时异常 |
| 同步阻塞慢操作 | 数据库全表扫 | 加索引 + 缓存 |
| 工具过多 | 20+ 个工具 | 分组路由 |

---

## 10. 实战练习

### 10.1 改造你的 Tool

回顾阶段 1-2 你写过的 Tool，逐个评估：

| 检查项 | 你的 Tool 是否达标 |
|--------|------------------|
| 单一职责？ | |
| 描述回答了"做什么/何时用/返回什么"？ | |
| 参数有格式说明？ | |
| 参数用基本类型？ | |
| 返回值字段名清晰？ | |
| 异常处理合理？ | |

### 10.2 设计一组完整 Tool

为"个人助理 Agent"设计 6 个 Tool：

1. **天气查询** - `getWeather(city, date)`
2. **创建待办** - `createTodo(title, dueDate, priority)`
3. **查询待办** - `listTodos(status)`
4. **发邮件** - `sendEmail(to, subject, body)`
5. **查询日历** - `getSchedule(date)`
6. **添加日历** - `addSchedule(title, startTime, duration)`

每个 Tool 用本节原则写：
- 方法签名
- `@Tool` 描述（"三个问题"）
- 每个参数的 `@ToolParam`
- 返回值类型（用 record）
- 异常处理

设计完成后，进入 [02-防止 Agent 失控](./02-防止Agent失控.md)。
