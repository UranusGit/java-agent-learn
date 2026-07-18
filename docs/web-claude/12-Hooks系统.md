# 12 - Hooks 系统：事件扩展机制

> 本章目标：实现 Anthropic 官方文档描述的 Hooks 系统。
> 完成后：租户管理员可配置 hook 接入审计 / 审批 / 通知等外部系统。
>
> **关联章节**：
> - Hooks 的 defer 模式与 [20 章审批中心](./20-审批与审核流.md) §1.6 联动；
> - Hook 触发也是事件，进 [17 章活动流](./17-全链路可观测前端.md)；
> - Hook 调用失败的重试：[18 章](./18-错误恢复与重试.md)；
> - AskUser 工具调用前的 PreToolUse hook：[19 章](./19-AskUser与澄清式交互.md) §12。

---

## 本章五步地图

| 步 | 节 | 你要带走什么 |
|----|----|---------|
| ① 痛点 | §1 | 05 章权限是硬编码——企业要按事件接入审计 / 通知 / 审批 |
| ② 最小实现 | §2–§7 | hooks 表 + 8 个事件 + HookExecutor + HookContext + Permission 接入 + Defer 模式 |
| ③ 验证 | §8 | 配一个 PreToolUse hook，所有 Bash 调用先打 webhook |
| ④ 对照 | §9 | 与"硬编码 PermissionMiddleware"的扩展性差异 |
| ⑤ 避坑 | §10 | hook 阻塞主流程 / 链式失败 / async 丢失注入 |

---

## 1. 痛点：05 章的 PermissionMiddleware 太"硬"

05 章的 PermissionMiddleware 是写死在 Java 代码里的——加一个"写 `.env` 时发钉钉通知"的规则要发版。这对企业场景不够用：

- 安全团队要所有 Bash 命令送 SIEM
- 法务要所有 Write 到 `contracts/` 的操作审批
- 产品要在某些租户接入自定义风控
- 运维要在 Agent 启动时拉最新配置

> Anthropic 在 Claude Code 里设计了 **Hooks 系统**：定义 ~30 个事件 + 5 种 hook 类型 + 4 种决策（allow/deny/ask/defer）+ 三种节奏（per turn / per tool / per session）。**租户管理员可以配 hook 接入任意外部系统，不用改代码**。
>
> 本章是 v1 简化版（command / http / prompt 三类 + 8 事件 + 同步路径 + defer）。完整 30 事件见 Anthropic 原文。

## 2. 设计要点（来自调研笔记 §3.1）

- 5 种 hook 类型：command / http / mcp_tool / prompt / agent；
- ~30 个事件；
- 3 种节奏（per turn / per tool / per session）；
- 决策控制：allow / deny / ask / defer（优先级 deny > defer > ask > allow）；
- async hook：不阻塞，下一轮注入。

v1 实现：command / http / prompt 三种类型 + 核心 8 个事件 + 同步路径 + defer 模式。

---

## 3. 表结构

新增 `V5__hooks.sql`：

```sql
-- 本代码仅作学习材料参考
CREATE TABLE hooks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    scope VARCHAR(32) NOT NULL,    -- platform / tenant / project / session
    event VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,     -- command / http / prompt
    config JSONB NOT NULL,
    is_async BOOLEAN NOT NULL DEFAULT FALSE,
    priority INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_hooks_event ON hooks(tenant_id, event, enabled);
```

---

## 4. 事件清单（v1）

```java
// 本代码仅作学习材料参考
public enum HookEvent {
    // Session
    SessionStart,
    SessionEnd,
    SessionCompact,
    // User
    UserPromptSubmit,
    // Tool
    PreToolUse,
    PostToolUse,
    ToolError,
    // Task
    TaskSessionStart,
    TaskSessionEnd
}
```

---

## 5. HookExecutor

新建 `src/main/java/org/demo02/webclaude/hook/HookExecutor.java`：

```java
// 本代码仅作学习材料参考
package org.demo02.webclaude.hook;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Component
public class HookExecutor {

    private final WebClient http;
    private final HookRepository repo;

    public HookExecutor(WebClient.Builder builder, HookRepository repo) {
        this.http = builder.build();
        this.repo = repo;
    }

    public CompletableFuture<HookDecision> fire(HookEvent event, HookContext ctx, boolean blocking) {
        List<HookEntity> hooks = repo.findEnabled(ctx.tenantId(), event.name());

        if (hooks.isEmpty()) return CompletableFuture.completedFuture(HookDecision.allow());

        // 按 priority 排序（数字小 = 优先级高）
        hooks.sort(Comparator.comparingInt(HookEntity::getPriority));

        if (blocking) {
            // 同步：依次执行，deny > defer > ask > allow
            return fireSequential(hooks, event, ctx);
        } else {
            // 异步：并行，不收集决策
            hooks.forEach(h -> fireOne(h, event, ctx));
            return CompletableFuture.completedFuture(HookDecision.allow());
        }
    }

    private CompletableFuture<HookDecision> fireSequential(List<HookEntity> hooks, HookEvent event, HookContext ctx) {
        CompletableFuture<HookDecision> acc = CompletableFuture.completedFuture(HookDecision.allow());
        for (HookEntity h : hooks) {
            acc = acc.thenCompose(prev -> {
                if (prev.action() == Action.DENY) return CompletableFuture.completedFuture(prev);
                return fireOne(h, event, ctx).thenApply(next -> merge(prev, next));
            });
        }
        return acc;
    }

    private HookDecision merge(HookDecision prev, HookDecision next) {
        // 优先级 deny > defer > ask > allow
        if (prev.action() == Action.DENY || next.action() == Action.DENY)
            return new HookDecision(Action.DENY, prev.reason() + "; " + next.reason());
        if (prev.action() == Action.DEFER || next.action() == Action.DEFER)
            return new HookDecision(Action.DEFER, next.reason());
        if (prev.action() == Action.ASK || next.action() == Action.ASK)
            return new HookDecision(Action.ASK, next.reason());
        return HookDecision.allow();
    }

    private CompletableFuture<HookDecision> fireOne(HookEntity h, HookEvent event, HookContext ctx) {
        return switch (h.getType()) {
            case "http" -> fireHttp(h, event, ctx);
            case "command" -> fireCommand(h, event, ctx);
            case "prompt" -> firePrompt(h, event, ctx);
            default -> CompletableFuture.completedFuture(HookDecision.allow());
        };
    }

    private CompletableFuture<HookDecision> fireHttp(HookEntity h, HookEvent event, HookContext ctx) {
        Map<String, Object> body = Map.of(
            "event", event.name(),
            "tenant_id", ctx.tenantId(),
            "session_id", ctx.sessionId(),
            "tool_name", ctx.toolName(),
            "tool_input", ctx.toolInput()
        );

        return http.post()
            .uri(h.getConfig().path("url").asText())
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(Duration.ofSeconds(5))
            .toFuture()
            .handleAsync((resp, ex) -> {
                if (ex != null) return new HookDecision(Action.DEFER, "hook error: " + ex.getMessage());
                String action = (String) resp.get("action");
                String reason = (String) resp.getOrDefault("reason", "");
                return new HookDecision(Action.valueOf(action.toUpperCase()), reason);
            });
    }

    private CompletableFuture<HookDecision> fireCommand(HookEntity h, HookEvent event, HookContext ctx) {
        // 在沙箱内执行 shell hook
        // 省略
        return CompletableFuture.completedFuture(HookDecision.allow());
    }

    private CompletableFuture<HookDecision> firePrompt(HookEntity h, HookEvent event, HookContext ctx) {
        // 注入 prompt 到下一轮 system message
        ctx.addInjectedPrompt(h.getConfig().path("prompt").asText());
        return CompletableFuture.completedFuture(HookDecision.allow());
    }

    public enum Action { ALLOW, DENY, ASK, DEFER }
    public record HookDecision(Action action, String reason) {
        public static HookDecision allow() { return new HookDecision(Action.ALLOW, ""); }
    }
}
```

---

## 6. HookContext

```java
// 本代码仅作学习材料参考
public class HookContext {
    private UUID tenantId;
    private UUID sessionId;
    private String toolName;
    private Map<String, Object> toolInput;
    private final List<String> injectedPrompts = new ArrayList<>();
    // getters/setters
    public void addInjectedPrompt(String p) { injectedPrompts.add(p); }
}
```

---

## 7. 接入 PermissionMiddleware

`PreToolUse` 事件与权限评估结合：

```java
// 本代码仅作学习材料参考
public CompletableFuture<Decision> evaluate(String toolName, Map<String, Object> input,
                                             String mode, HookContext ctx) {
    // 1. 先跑 hook
    return hookExecutor.fire(HookEvent.PreToolUse, ctx, true)
        .thenCompose(hookDecision -> {
            if (hookDecision.action() == HookExecutor.Action.DENY) {
                return CompletableFuture.completedFuture(new Decision(PermissionRule.Action.DENY, "hook denied: " + hookDecision.reason()));
            }
            if (hookDecision.action() == HookExecutor.Action.DEFER) {
                // 本轮放行，下一轮处理（不阻塞长任务）
                return CompletableFuture.completedFuture(new Decision(PermissionRule.Action.ALLOW, "deferred"));
            }
            if (hookDecision.action() == HookExecutor.Action.ASK) {
                return CompletableFuture.completedFuture(new Decision(PermissionRule.Action.ASK, "hook asked"));
            }
            // 2. hook allow → 继续原本权限评估
            return evaluateRules(toolName, input, mode);
        });
}
```

---

## 8. Defer 模式（非交互长任务）

当用户离线 / 长任务自动跑时：
- hook 返回 ASK → 转成 DEFER（写"待审批"队列）；
- 下一轮 prompt 注入"上次 defer 了 X，请决定"；
- 用户上线时审批 UI 显示待审批列表。

```java
// 本代码仅作学习材料参考
@Component
public class DeferredDecisionQueue {

    private final StringRedisTemplate redis;

    public void push(UUID sessionId, String toolName, Map<String, Object> input) {
        String key = "defer:" + sessionId;
        redis.opsForList().rightPush(key, om.writeValueAsString(Map.of(
            "tool", toolName, "input", input, "at", Instant.now().toString()
        )));
    }

    public List<String> pullAll(UUID sessionId) {
        return redis.opsForList().range("defer:" + sessionId, 0, -1);
    }
}
```

---

## 9. 配置示例

### 7.1 HTTP Hook：审计所有 Bash 命令

```json
{
  "scope": "tenant",
  "event": "PreToolUse",
  "type": "http",
  "config": {
    "url": "https://audit.corp.com/api/claude/record"
  },
  "is_async": false,
  "priority": 10
}
```

返回 `{"action": "allow"}` 即可。

### 7.2 高危命令审批 Hook

```json
{
  "scope": "tenant",
  "event": "PreToolUse",
  "type": "http",
  "config": {
    "url": "https://approval.corp.com/api/check"
  },
  "priority": 5
}
```

审批服务返回：
- `{"action": "allow"}` —— 放行；
- `{"action": "deny", "reason": "policy violation"}` —— 拒绝；
- `{"action": "ask"}` —— 等用户在线审批。

### 7.3 Prompt Hook：动态 system prompt

```json
{
  "scope": "session",
  "event": "SessionStart",
  "type": "prompt",
  "config": {
    "prompt": "本次任务必须遵循公司安全规范，禁止使用外部云服务。"
  }
}
```

---

## 10. 验证：测试 Hooks

### 8.1 准备 mock 服务

```bash
npx http-echo-server --port 9999
```

### 8.2 配置 hook 指向 mock

POST `/api/hooks`：

```json
{
  "scope": "session",
  "event": "PreToolUse",
  "type": "http",
  "config": {"url": "http://localhost:9999"},
  "priority": 1
}
```

### 8.3 验证

让 Agent 调用任意工具 → mock 服务收到请求 → 返回 `{"action": "allow"}` → 工具正常执行。

**检查点 12-1**：hook 配置生效，工具调用前能触发外部服务。

---

## 11. 对照：与"硬编码 PermissionMiddleware"的扩展性差异

| 维度 | 05 章硬编码 | 12 章 Hooks 系统 |
|------|------------|-----------------|
| 加新规则 | 发版 | ✅ 数据库配置 |
| 接入外部系统 | ❌ | ✅ http / command / mcp_tool |
| 事件粒度 | 工具调用 | ✅ ~30 个事件 |
| 决策类型 | allow/deny/ask | ✅ + defer |
| 租户自定义 | ❌ | ✅ |
| 节奏 | per tool | ✅ per turn / per tool / per session |
| 异步 | ❌ | ✅ async + defer |

## 12. 避坑：Hooks 系统常踩的雷

| 雷 | 现象 | 规避 |
|----|------|------|
| hook 阻塞主流程 | http hook 3s → Agent 卡 3s | 全部 async；同步路径加 timeout |
| 一个 hook 失败整链挂 | 非关键 hook 报错也 deny | 区分 critical / non-critical，后者 swallow |
| async hook 丢上下文 | 没有 tenantId / sessionId | HookContext 必须显式传 |
| 链式 hook 死循环 | hook A 触发 B，B 触发 A | 每事件禁止递归触发 |
| hook 自身被 prompt injection | 用户文本骗 hook 放行 | hook 决策不基于模型输出，基于规则 |
| defer 后忘记注入下一轮 | 用户配置了 defer 但没接 webhook | defer 必须 fallback 超时降级 |
| hook 数量爆炸 | 一个事件 50 个 hook 串行 | 按事件并行执行 + 早退 |
| hook 配置可被普通用户改 | 用户给自己开 yolo | 仅租户管理员可改 |
| hook 记录用户敏感数据 | 日志泄漏 prompt 内容 | hook input 默认脱敏，按需开 |
| hook 重试造成副作用倍增 | http hook 重试导致扣款两次 | 幂等 key + 至多一次重试 |

## 13. 安全注意

- HTTP hook 必须有超时（5s）+ 熔断（连续失败降级到 async）；
- Command hook 必须在沙箱内执行（不能影响主进程）；
- Hook 配置必须按 scope 隔离（session 级 hook 不能影响其他 session）；
- Hook 不能访问用户的 secrets / tokens（除了显式注入的）。

---

## 14. 本章产出

```
后端：
  ✅ Hook 表
  ✅ HookExecutor（http / command / prompt）
  ✅ PreToolUse 接入 PermissionMiddleware
  ✅ Defer 队列
  ✅ Async hook 支持

前端：
  ✅ Hook 配置 UI（省略，标准 CRUD）
```

## 15. 下一步

进入 [99-附录-部署与排错](./99-附录-部署与排错.md)，把整套系统打包成 docker-compose 一键启动。
