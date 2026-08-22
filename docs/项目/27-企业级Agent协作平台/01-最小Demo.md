# 01-最小 Demo：两 Agent 最小委托协作

> **定位**：用不到百行造出协作平台的最小骨架：**① AgentA 查注册表发现 AgentB ② 按 AgentCard 校验能力匹配 ③ 一次同步任务委托 + 结果回传**。验证三件事：能力可发现、委托可执行、结果可校验。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 54-Agent间协作协议工程化](../../教程/54-Agent间协作协议工程化.md)。
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

## 三、最小版的两处"偷懒"（后续迭代补）

1. **同步调用**：真实协作多是长任务（报表生成/巡检）——需异步任务语义（03/04 迭代补）。
2. **无鉴权**：任何人都能委托任何 Agent——跨域身份与最小授权在 05 迭代补。

## 四、验收

| 输入 | 期望 |
|------|------|
| 按 "report.generate" 发现 | 找到 finance-report-agent |
| 参数缺 period | schema 匹配失败拒绝 |
| 委托执行 | 结果通过校验返回 |
| 无人提供该能力 | NoAgentFound 明确报错 |

> **下一步**：闭环已通但 AgentCard 是内存硬编码。02 迭代做 **AgentCard 注册中心 + 能力发现** 的工程化（版本/心跳/撤销）。
