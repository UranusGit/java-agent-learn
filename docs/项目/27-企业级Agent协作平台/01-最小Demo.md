# 01-最小 Demo：两 Agent 最小委托协作

> **定位**：用不到百行造出协作平台的最小骨架：**① AgentA 查注册表发现 AgentB ② 按 AgentCard 校验能力匹配 ③ 一次同步任务委托 + 结果回传**。验证三件事：能力可发现、委托可执行、结果可校验。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 97-Agent间协作协议工程化](../../教程/97-Agent间协作协议工程化.md)。
>
> **铁律 0**：AgentCard/委托协议为开放协议无官方 SDK（适配层「概念代码」）；基座 `ChatClient` 实证。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①内存 AgentCard 注册表（两 Agent）②能力匹配查询 ③一次委托调用（同步）+ 结果 schema 校验 |
| **影响了哪些模块** | 单体 CollabMediator + AgentCardRegistry |
| **架构如何演进** | 从无到有：先证明"发现→匹配→委托→结果"闭环 |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①按能力查询能发现目标 Agent ②能力不匹配的委托被拒 ③委托结果按 schema 校验通过/拒绝。

### 一.1 本节核对（四问与迭代验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问口径齐全 | 新增需求/影响模块/架构演进/上一版痛点四行均有；"上一版痛点=无（起点）"表述自洽 |
| 2 | 本迭代验收可度量 | ①能力发现 ②能力不匹配拒绝 ③结果 schema 校验，三项均可判定 |

## 二、最小闭环（发现→匹配→委托→校验）

```java
// 概念代码：两 Agent 最小委托协作
@Component
public class CollabMediator {
    private final AgentCardRegistry registry;         // AgentCard 注册表(内存起步)
    private final ChatClient client;                  // 被委托 Agent 的执行(实证基座)

    // 1) 能力发现：按能力查 AgentCard
    public List<AgentCard> discover(String capability) {
        return registry.findAll().stream()
            .filter(c -> c.capabilities().contains(capability)).toList();
    }

    // 2) 委托：目标 + 入参 + 期望 schema → 执行 → 结果校验
    public DelegationResult delegate(DelegationRequest req) {
        AgentCard target = discover(req.capability()).stream()
            .findFirst().orElseThrow(() -> new NoAgentFound(req.capability()));
        if (!matches(target.inputSchemas(), req.params()))     // 能力/参数匹配
            throw new MismatchedCapability("参数不满足 AgentCard schema");
        String raw = client.prompt()                            // 委托执行(实证 ChatClient)
            .system(target.taskSystemPrompt())
            .user(req.renderTask()).call().content();
        return DelegationResult.validated(raw, req.expectedSchema()); // 结果校验
    }
}
```

### 二.1 本节测试与验证（最小委托闭环）

**前置条件**：`CollabMediator` + `AgentCardRegistry` 可编译运行；注册表预置 finance-report-agent（能力 `report.generate`）；一个委托入口可打（HTTP/CLI）。

**材料——核准用例**（对照下方案例表逐一触发 `delegate`）：

| 输入 | 期望 |
|------|------|
| `discover("report.generate")` | 命中 finance-report-agent |
| `discover("send.email")` | 返回空（无该能力 Agent） |
| 委托 `period` 缺缺 | `MismatchedCapability`（参数不满足 schema） |
| 正常委托（period=Q3） | 结果按 schema 校验通过返回 |

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | discover 有能力的 query | 返回目标 Agent，不抛 `NoAgentFound` |
| 2 | discover 无能力 query | 空列表 / `NoAgentFound` 明确报错（不落回错误结果） |
| 3 | 参数缺 period 委托 | `MismatchedCapability`，委托被拒 |
| 4 | 正常委托 | 返回 `DelegationResult.validated(...)`，schema 校验通过 |

**失败排查**：①有 Agent 查不到→`capabilities()` 集合大小写/格式不匹配；②缺参仍通过→`matches()` 未真正比对 inputSchemas；③返回 schema 校验失败→`expectedSchema()` 与实测结果结构不符。

## 三、最小版的两处"偷懒"（后续迭代补）

1. **同步调用**：真实协作多是长任务（报表生成/巡检）——需异步任务语义（03/04 迭代补）。
2. **无鉴权**：任何人都能委托任何 Agent——跨域身份与最小授权在 05 迭代补。

### 三.1 本节核对（两处偷懒）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 每处偷懒都有后续落点 | 同步→03/04、无鉴权→05，均有明确迭代篇承接，非搁置 |
| 2 | 承认边界 | 两处"偷懒"都如实标注为当前版本取舍，未伪装成已实现能力 |

## 四、全篇回归验证

> 本节为最小闭环整体验收——§二.1 章节级用例逐条通过后的最终回归。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 全链路委托一次（发现→匹配→委托→校验） | 四步完整走通产出 `DelegationResult.validated` |
| 2 | 能力不匹配 + 无人提供能力各触发一次 | 分别落 `MismatchedCapability` / `NoAgentFound`，委托失败路径闭环 |
| 3 | 重跑 §二.1 四用例 | 全部 PASS，结果可重复 |

**回归失败排查**：任一步 FAIL 按 §二.1 排查项回溯（能力名匹配 / schema 比对 / expectedSchema 结构）。

> **下一步**：闭环已通但 AgentCard 是内存硬编码。02 迭代做 **AgentCard 注册中心 + 能力发现** 的工程化（版本/心跳/撤销）。
