# 06-解释 API 与查询服务

> **定位**：把解释能力**开放为服务**——**① 统一解释 API（时间线/归因/置信/反事实四查询）② 权限控制（谁能看哪级解释）③ 解释快照（历史结论的解释不随存储变化）**。前置阅读：[05-分来源置信度与校准](05-分来源置信度与校准.md)、[23-审计](../../教程/32-工具执行可观测与审计.md)。
>
> **铁律 0**：API 层自研「概念代码」（WebFlux 响应式）；数据来自 02-05 迭代产物。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ①四个解释端点（timeline/attributions/confidence/counterfactual）②按受众+权限路由（复用 02 三级视图）③解释快照（生成时固化，不随后续存储变更漂移） |
| **影响了哪些模块** | 新增 ExplainApiController；解释产物 → 快照存储 |
| **架构如何演进** | 从"平台内部能力"演进为"可被业务/客服/监管系统调用的服务" |
| **上一版痛点** | 解释能力只有平台内部能看；客服/合规系统无法集成 |

**本迭代验收**：①四端点可用（含受众路由）②无权限受众拿不到取证级 ③历史解释查询结果稳定（快照）。

### 一.1 本节核对（四问与本迭代验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问口径齐全 | 新增需求（四端点/受众权限路由/快照）/影响模块（ExplainApiController + 快照存储）/架构演进（平台内部能力→对外服务）/上一版痛点四行均有 |
| 2 | 本迭代验收可度量 | ①四端点可用 ②取证级限权 ③快照稳定——可作 PASS 判据，落点对应 §二 端点到 §三 快照 |

## 二、四端点设计

```java
// 概念代码：解释 API（WebFlux）
@RestController
public class ExplainApiController {
    @GetMapping("/explain/{runId}/timeline")
    Flux<ExplainStep> timeline(@PathVariable String runId,
                               @RequestParam Audience aud) { ... }      // 02 分层时间线

    @GetMapping("/explain/{runId}/attributions")
    Flux<ValidatedAttribution> attributions(@PathVariable String runId) { ... } // 03/04 归因+校验

    @GetMapping("/explain/{runId}/confidence")
    Flux<ConfidenceView> confidence(@PathVariable String runId) { ... }   // 05 置信

    @GetMapping("/explain/{runId}/counterfactual")
    Mono<CounterfactualView> counterfactual(@PathVariable String runId) { ... } // 07 反事实
}
```

### 二.1 本节测试与验证（四端点设计）

**前置条件**：02-05 迭代产物（时间线/归因/校验/置信）可用；`Audience` 权限路由已实现；WebFlux 应用（WebFlux 铁律：Reactor 响应式，EventLoop 上不 block）。

**材料——代码内含的旋钮**：四端点 `/explain/{runId}/timeline|attributions|confidence|counterfactual`；参数 `@RequestParam Audience aud`；返回 `Flux`/`Mono`（响应式）。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `GET /explain/{runId}/timeline?aud=USER` | 返回 `Flux<ExplainStep>` 分层时间线（概要级） |
| 2 | `GET /explain/{runId}/attributions` | 返回 `Flux<ValidatedAttribution>`（已校验归因） |
| 3 | `GET /explain/{runId}/confidence` | 返回 `Flux<ConfidenceView>`（分来源置信） |
| 4 | `GET /explain/{runId}/counterfactual` | 返回 `Mono<CounterfactualView>`（反事实，07） |
| 5 | 用户身份请求取证级（`aud=REGULATOR`） | 返回 403（无权限受众拿不到取证级） |
| 6 | 压力/并发抽查 | 响应式组件返回不阻塞 EventLoop（`Flux`/`Mono` 惰性执行，无 block） |

**失败排查**：①端点 404→路由或 `@RestController` 未生效；②无权限者拿到取证级→受众权限校验未在 controller 前执行或 `aud` 未走权限网关；③响应阻塞卡顿→在某处对响应式结果 `.block()` 或在 EventLoop 上做阻塞调用（违反 WebFlux 铁律）；④端点返回空→底层 `runId` 对应产物（时间线/归因/置信）未生成。

## 三、解释快照（合规关键）

```mermaid
graph LR
    A["执行完成"] --> B["生成本次解释快照<br/>(时间线+归因+置信 固化)"]
    B --> C["存储(只读)"]
    C --> D["后续查询: 永远返回当时快照"]
    E["证据源后续变更/清理"] -.->|不影响| D
```

**为什么快照**：证据/文档会更新、记忆会衰减（25-06）——但**对历史决策的解释必须还原"当时依据"**（监管追溯的是决策时点的证据状态，呼应 [25-历史合规](../../教程/58-历史记录持久化与合规.md)、[13-事件溯源](../../项目/13-事件溯源Agent运行时平台/00-需求分析与架构设计.md) 的"日志即真相"思想）。

### 三.1 本节核对（解释快照——合规关键）

- [ ] 快照流程（执行完成 → 生成本次快照固化 → 只读存储 → 后续查询返回当时快照）能对照 Mermaid 复述
- [ ] 能说清"为什么快照"——证据源/记忆会更新衰减，但对历史决策的解释必须还原决策时点依据

## 四、权限路由

复用 02 受众分级 + [26-多租户](../../教程/59-多租户隔离与资源治理.md) 权限：用户只看自己会话概要；运营看本租户标准级；监管调取走 08 披露审批（取证级不裸开放）。

### 四.1 本节核对（权限路由）

- [ ] 四类权限（用户看本会话概要 / 运营看租户标准级 / 监管走 08 披露审批 / 取证级不裸开放）能对照正文复述，并能说清"取证级不裸开放"的含义
- [ ] 权限路由与 02 受众分级、26 多租户基准衔接一致，无"权限与受众分级脱节"

## 五、验收

| 测试 | 期望 |
|------|------|
| 四端点 | 返回对应解释产物 |
| 用户身份查取证级 | 403 |
| 证据源更新后查旧解释 | 返回快照不变 |

### 五.1 本节核对（验收表）

- [ ] 验收表三行（四端点 / 用户查取证级 403 / 证据源更新后快照不变）与 §二.1 步骤 1-4/5 及 §三 快照一一对应，无验收项落空
- [ ] "证据源更新后查旧解释返回快照不变"有明确断言（§三 快照只读固化），非空许愿

> **下一步**：解释可被消费了。07 迭代做**反事实边界**——回答"差多少会翻转"这一最难也最有价值的解释。
