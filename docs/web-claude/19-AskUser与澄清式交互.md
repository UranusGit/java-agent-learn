# 19 - AskUser 与澄清式交互：让 Agent 主动提问

> 单向执行的 agent 不可能成功完成复杂任务。真实场景中 agent 必须主动向用户澄清需求。
> 本章设计完整的"对话式收敛"机制：5 种 question kind、挂起-恢复协议、超时降级、教 agent 何时问。
>
> **Web 项目专项**：
> - 移动端审批 UI 全屏化：[21 章 §7.5](./21-Web前端工程化.md)；
> - 跨 tab 同步（在 tab B 答，tab A 也要消失）：[22 章 §4.5](./22-跨标签页与实时协作.md)；
> - 离线时 question 排队：[22 章 §2.5](./22-跨标签页与实时协作.md)；
> - a11y（屏幕阅读器播报新 question）：[21 章 §13](./21-Web前端工程化.md)；
> - 键盘快捷键（Tab 选答案、Enter 提交）：[21 章 §8](./21-Web前端工程化.md)。

---

## 0. 问题本质

### 0.1 单向 vs 收敛

**错误（单向）**：
```
用户：做个 todo app
agent：[闷头做了 5 分钟，方向错了]
agent：完成了！
用户：这不是我要的
```

**正确（收敛）**：
```
用户：做个 todo app
agent：我需要确认几点：
       ① 技术栈：React/Vue？
       ② 存储：localStorage/IndexedDB/后端？
       ③ 用户系统：登录/单机？
用户：React + 后端 + 登录
agent：明白了。开始。
```

### 0.2 设计目标

| 目标 | 实现 |
|------|------|
| Agent 能问问题 | AskUser 工具 |
| 5 种 question kind | single / multi / form / confirm / free-text |
| 用户能答 | 前端 QuestionCard |
| 挂起-恢复 | DB 持久化 + WS 协议 |
| 超时降级 | defaultDecision 或 cancel |
| 改主意 | answer history |
| 部分回答 | required vs optional 字段 |

---

## 1. AskUser 工具

### 1.1 设计决策

为什么是**工具**而不是 ContentBlock 类型？

- 工具调用有**审计**（统一在 tool_call 事件）；
- 工具调用过**权限中间件**（租户可禁 AskUser）；
- 工具的 schema 强制类型化（不会乱传）；
- 与 Claude Code 源码一致。

### 1.2 工具实现

新建 `src/main/java/org/demo02/webclaude/tool/builtin/AskUserTool.java`：

```java
// 本代码仅作学习材料参考
package org.demo02.webclaude.tool.builtin;

import org.demo02.webclaude.agent.AbortHandle;
import org.demo02.webclaude.human.PendingQuestion;
import org.demo02.webclaude.human.QuestionBus;
import org.demo02.webclaude.tool.Tool;
import org.demo02.webclaude.tool.ToolContext;
import org.demo02.webclaude.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Component
public class AskUserTool implements Tool {

    private final QuestionBus bus;

    @Override public String name() { return "AskUser"; }
    @Override public Kind kind() { return Kind.NETWORK; }  // 等用户也算 IO

    @Override
    public String description() {
        return """
            向用户提问以澄清需求。当用户需求不明确时**必须**主动调用，
            不要凭直觉假设。
            
            使用场景：
              - 技术栈未指定；
              - 业务规则有歧义；
              - 范围不明确；
              - 集成点不明；
              - 关键决策（持久化 / 认证 / 限流）。
            
            5 种 kind：
              - single：单选（必须配 options）
              - multi：多选（必须配 options）
              - form：表单（必须配 fields）
              - confirm：是/否
              - free-text：自由输入
            
            每次最多问 5 个字段，超过先做并说明假设。
            """;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "prompt", Map.of("type", "string",
                    "description", "问题的完整描述，让用户能理解上下文"),
                "kind", Map.of("type", "string", "enum",
                    List.of("single", "multi", "form", "confirm", "free-text")),
                "options", Map.of("type", "array",
                    "description", "single/multi 必填",
                    "items", Map.of("type", "object",
                        "properties", Map.of(
                            "value", Map.of("type", "string"),
                            "label", Map.of("type", "string"),
                            "description", Map.of("type", "string")
                        ),
                        "required", List.of("value", "label"))),
                "fields", Map.of("type", "array",
                    "description", "form 必填",
                    "items", Map.of("type", "object",
                        "properties", Map.of(
                            "name", Map.of("type", "string"),
                            "label", Map.of("type", "string"),
                            "type", Map.of("type", "string",
                                "enum", List.of("text", "number", "boolean", "date", "select", "file")),
                            "options", Map.of("type", "array", "items", Map.of("type", "string")),
                            "required", Map.of("type", "boolean"),
                            "default", Map.of("type", "string"),
                            "placeholder", Map.of("type", "string")
                        ),
                        "required", List.of("name", "label", "type"))),
                "reasoning", Map.of("type", "string",
                    "description", "为什么问这个，让用户理解"),
                "defaultDecision", Map.of("type", "string",
                    "description", "超时时的默认答案"),
                "timeoutSec", Map.of("type", "integer", "default", 300),
                "metadata", Map.of("type", "object",
                    "description", "可选的扩展字段")
            ),
            "required", List.of("prompt", "kind", "reasoning")
        );
    }

    @Override
    public CompletableFuture<ToolResult> apply(Map<String, Object> input, ToolContext ctx) {
        String prompt = (String) input.get("prompt");
        String kind = (String) input.get("kind");
        String reasoning = (String) input.get("reasoning");
        int timeoutSec = (int) input.getOrDefault("timeoutSec", 300);
        String defaultDecision = (String) input.get("defaultDecision");

        // 构造 pending question
        PendingQuestion q = PendingQuestion.builder()
            .id(UUID.randomUUID())
            .sessionId(ctx.sessionId())
            .taskId(ctx.taskId())
            .toolCallId(/* 当前 tool_use id */)
            .prompt(prompt)
            .kind(kind)
            .reasoning(reasoning)
            .options((List<Map<String, Object>>) input.get("options"))
            .fields((List<Map<String, Object>>) input.get("fields"))
            .defaultDecision(defaultDecision)
            .timeoutSec(timeoutSec)
            .askedAt(Instant.now())
            .status("pending")
            .build();

        // 持久化（关键！防 WS 断了丢）
        bus.persist(q);

        // 推送 decision_requested 事件（17 章）
        bus.emitRequest(q);

        // 挂起，等用户答
        CompletableFuture<Answer> future = bus.register(q.id());

        // 超时降级
        if (timeoutSec > 0) {
            future = future.orTimeout(timeoutSec, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    if (defaultDecision != null) {
                        return Answer.defaulted(q.id(), defaultDecision);
                    }
                    return Answer.timeout(q.id());
                });
        }

        return future.thenApply(answer -> {
            bus.markAnswered(q.id(), answer);
            return ToolResult.text("用户回答：" + answer.toJson());
        });
    }
}
```

---

## 2. QuestionBus：挂起-恢复的中枢

```java
// 本代码仅作学习材料参考
@Component
public class QuestionBus {

    private final PendingQuestionRepository repo;
    private final AgentEventBus eventBus;
    private final Map<UUID, CompletableFuture<Answer>> pending = new ConcurrentHashMap<>();

    public void persist(PendingQuestion q) {
        repo.save(toEntity(q));
    }

    public CompletableFuture<Answer> register(UUID questionId) {
        CompletableFuture<Answer> f = new CompletableFuture<>();
        pending.put(questionId, f);
        return f;
    }

    public void emitRequest(PendingQuestion q) {
        eventBus.emit(q.sessionId(), "decision", "info", Map.of(
            "phase", "requested",
            "decisionId", q.id().toString(),
            "kind", "clarification",
            "payload", Map.of(
                "prompt", q.prompt(),
                "questionKind", q.kind(),
                "options", q.options(),
                "fields", q.fields(),
                "reasoning", q.reasoning(),
                "timeoutSec", q.timeoutSec(),
                "defaultDecision", q.defaultDecision()
            )
        )).subscribe();
    }

    public void submitAnswer(UUID questionId, Answer answer) {
        CompletableFuture<Answer> f = pending.remove(questionId);
        if (f == null) {
            // 进程重启后的恢复路径
            f = recoverFuture(questionId);
        }
        if (f != null) f.complete(answer);

        // 持久化答案 + emit event
        repo.markAnswered(questionId, answer);
        eventBus.emit(/* session_id */, "decision", "info", Map.of(
            "phase", "made",
            "decisionId", questionId.toString(),
            "answer", answer.toMap()
        )).subscribe();
    }

    public List<PendingQuestion> loadPending(UUID sessionId) {
        return repo.findBySessionIdAndStatus(sessionId, "pending");
    }
}
```

---

## 3. 数据模型

新增 `V10__pending_questions.sql`：

```sql
-- 本代码仅作学习材料参考
CREATE TABLE pending_questions (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    task_id UUID,
    tool_call_id VARCHAR(64),
    prompt TEXT NOT NULL,
    question_kind VARCHAR(32) NOT NULL,
    reasoning TEXT,
    options JSONB,
    fields JSONB,
    default_decision VARCHAR(255),
    timeout_sec INT DEFAULT 300,
    asked_at TIMESTAMP NOT NULL DEFAULT NOW(),
    timeout_at TIMESTAMP,
    answered_at TIMESTAMP,
    status VARCHAR(32) DEFAULT 'pending',
    answer JSONB,
    INDEX idx_session_status (session_id, status)
);
```

---

## 4. Agent Loop 的挂起-恢复

### 4.1 关键设计：question = 特殊 end_turn

agent 调 AskUser 时：
1. 工具调用进入"挂起"状态（Flux 不 complete）；
2. 等用户回答后，把答案作为 tool_result 注入；
3. Agent Loop 继续下一轮（像普通工具返回一样）。

```java
// 本代码仅作学习材料参考
public Flux<State> run(UUID sessionId, ...) {
    return Flux.create(sink -> {
        runLoopWithAsk(sessionId, ..., sink);
    });
}

private void runLoopWithAsk(UUID sessionId, ..., FluxSink<State> sink) {
    callModel(...).subscribe(response -> {
        List<ToolUse> toolUses = extract(response);
        if (toolUses.isEmpty()) {
            sink.next(state.withTransition("end_turn"));
            sink.complete();
            return;
        }

        // 串行执行工具（v1）
        executeNextTool(toolUses, 0, state, sink);
    });
}

private void executeNextTool(List<ToolUse> uses, int idx, State state, FluxSink<State> sink) {
    if (idx >= uses.size()) {
        // 所有工具完成 → 进入下一轮模型调用
        runLoopWithAsk(state, sink);
        return;
    }

    ToolUse tu = uses.get(idx);

    if (tu.name().equals("AskUser")) {
        // 特殊：挂起
        sink.next(state.withTransition("waiting_for_user"));
        CompletableFuture<ToolResult> f = askUserTool.apply(tu.input(), ctx);
        f.thenAccept(result -> {
            // 把结果作为 tool_result 注入，继续
            State next = state.append(Message.toolResult(tu.id(), result.toContentBlock()));
            sink.next(next);
            executeNextTool(uses, idx + 1, next, sink);
        });
    } else {
        // 普通工具
        ToolResult r = tool.apply(tu.input(), ctx).join();
        State next = state.append(Message.toolResult(tu.id(), r.toContentBlock()));
        sink.next(next);
        executeNextTool(uses, idx + 1, next, sink);
    }
}
```

### 4.2 进程重启后的恢复

如果服务在 question 挂起时崩溃：

```java
// 本代码仅作学习材料参考
@PostConstruct
public void recoverPendingQuestions() {
    for (PendingQuestion q : repo.findAllPending()) {
        if (q.timeoutAt().isBefore(Instant.now())) {
            // 已超时 → apply defaultDecision 或 cancel
            Answer a = q.defaultDecision() != null
                ? Answer.defaulted(q.id(), q.defaultDecision())
                : Answer.timeout(q.id());
            completeWithAnswer(q, a);
        } else {
            // 还有时间 → 重新注册 future，等用户答
            CompletableFuture<Answer> f = register(q.id());
            // 调度超时检查
            scheduler.schedule(() -> {
                if (!f.isDone()) {
                    Answer a = q.defaultDecision() != null
                        ? Answer.defaulted(q.id(), q.defaultDecision())
                        : Answer.timeout(q.id());
                    f.complete(a);
                }
            }, Duration.between(Instant.now(), q.timeoutAt()).toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
```

> 注意：恢复后**不会自动重启动 Agent Loop**（Flux 已断）。需要让用户在前端"继续会话"，触发新 turn，新 turn 会读到 tool_result（answer）作为上下文，继续。

---

## 5. Answer 模型

```java
// 本代码仅作学习材料参考
public record Answer(
    UUID questionId,
    String status,          // answered / defaulted / timeout / cancelled
    Map<String, Object> values,  // field name → value
    Instant at
) {
    public static Answer of(UUID id, Map<String, Object> values) {
        return new Answer(id, "answered", values, Instant.now());
    }
    public static Answer defaulted(UUID id, String defaultDecision) {
        return new Answer(id, "defaulted", Map.of("__default__", defaultDecision), Instant.now());
    }
    public static Answer timeout(UUID id) {
        return new Answer(id, "timeout", Map.of(), Instant.now());
    }
    public static Answer cancelled(UUID id) {
        return new Answer(id, "cancelled", Map.of(), Instant.now());
    }

    public String toJson() {
        // 序列化给 agent
    }
}
```

---

## 6. 前端：QuestionCard

### 6.1 REST 接口

```java
// 本代码仅作学习材料参考
@RestController
@RequestMapping("/api/sessions/{sid}/questions")
public class QuestionController {

    @GetMapping("/pending")
    public List<PendingQuestion> pending(@PathVariable UUID sid) {
        return bus.loadPending(sid);
    }

    @PostMapping("/{qid}/answer")
    public void answer(@PathVariable UUID sid, @PathVariable UUID qid,
                        @RequestBody AnswerRequest req) {
        bus.submitAnswer(qid, Answer.of(qid, req.values()));
    }

    @PostMapping("/{qid}/cancel")
    public void cancel(@PathVariable UUID sid, @PathVariable UUID qid) {
        bus.submitAnswer(qid, Answer.cancelled(qid));
    }
}
```

### 6.2 QuestionCard 组件

```tsx
// 本代码仅作学习材料参考
import { useState } from 'react';

export function QuestionCard({ q }: { q: PendingQuestion }) {
    const [values, setValues] = useState<Record<string, any>>({});
    const [submitted, setSubmitted] = useState(false);

    const submit = () => {
        fetch(`/api/sessions/${q.sessionId}/questions/${q.id}/answer`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ values }),
        });
        setSubmitted(true);
    };

    if (submitted) return <div style={{ opacity: 0.5 }}>已提交</div>;

    return (
        <div style={{ background: '#fffbe6', padding: 16, borderRadius: 8, margin: '8px 0' }}>
            <div style={{ fontSize: 12, color: '#888' }}>🤔 需要你确认</div>
            <div style={{ fontSize: 14, color: '#666', fontStyle: 'italic' }}>
                {q.reasoning}
            </div>
            <div style={{ fontWeight: 'bold', margin: '8px 0' }}>
                {q.prompt}
            </div>

            {q.kind === 'single' && <SingleChoice options={q.options} value={values.value}
                onChange={(v) => setValues({ value: v })} />}
            {q.kind === 'multi' && <MultiChoice options={q.options} value={values.values || []}
                onChange={(v) => setValues({ values: v })} />}
            {q.kind === 'form' && <FormFields fields={q.fields} value={values}
                onChange={setValues} />}
            {q.kind === 'confirm' && <ConfirmButtons value={values.confirmed}
                onChange={(v) => setValues({ confirmed: v })} />}
            {q.kind === 'free-text' && <textarea value={values.text || ''}
                onChange={(e) => setValues({ text: e.target.value })}
                rows={3} style={{ width: '100%' }} />}

            <div style={{ marginTop: 12, display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 12, color: '#888' }}>
                    {q.timeoutSec && `超时 ${q.timeoutSec / 60} 分钟${q.defaultDecision ? ` → 默认 ${q.defaultDecision}` : ' → 取消'}`}
                </span>
                <div>
                    <button onClick={() => {
                        fetch(`/api/sessions/${q.sessionId}/questions/${q.id}/cancel`, { method: 'POST' });
                        setSubmitted(true);
                    }}>取消</button>
                    <button onClick={submit}
                        disabled={!hasRequired(q, values)}
                        style={{ marginLeft: 8 }}>
                        提交
                    </button>
                </div>
            </div>
        </div>
    );
}

function hasRequired(q: PendingQuestion, values: Record<string, any>): boolean {
    if (q.kind !== 'form') return true;
    return q.fields.filter((f: any) => f.required).every((f: any) => values[f.name] != null);
}
```

### 6.3 SingleChoice / MultiChoice / FormFields

略，都是受控组件，按 schema 渲染。

### 6.4 渲染位置

QuestionCard 直接渲染在活动流（17 章）的 decision 事件位置：

```
[user_input]    帮我做 todo app
[thought]       需求不清晰，需要澄清
[decision requested]
  ┌─ QuestionCard ────────────────────┐
  │ 请选择技术栈                        │
  │ ○ React  ○ Vue  ○ 原生 JS          │
  │ [取消] [提交]                       │
  └───────────────────────────────────┘
[decision made] React
[thought]       开始实现...
```

---

## 7. 教 Agent 何时问

### 7.1 System Prompt 指令

在 14 章的 systemPromptAssembler Layer 4 加：

```
当用户的请求存在以下情况时，**必须**先用 AskUser 工具澄清：

1. 技术栈未指定（前端框架、后端语言、数据库...）；
2. 业务规则有歧义（"用户"指最终用户还是管理员？）；
3. 范围不明确（只前端 / 全栈 / 含测试？）；
4. 集成点不明（独立部署 / 嵌入现有系统？）；
5. 关键决策（持久化方案、认证方式、限流策略）；
6. 安全相关（涉及密钥、生产数据、不可逆操作）；
7. 估计需要超过 30 分钟的工作量（先确认方案再做）。

不要凭直觉假设。每次最多问 5 个字段，超过就基于已有信息先做并说明假设。

如果用户明确说"你决定"或"用默认值"，不要再问。
```

### 7.2 Few-shot 示例

system prompt 末尾：

```
示例：
用户：帮我做个 todo app
错误做法：直接开始写代码
正确做法：先调用 AskUser 问技术栈、存储、用户系统

用户：用 React 做个 todo
部分正确：技术栈已定，但仍需问存储、用户系统

用户：用 React + localStorage 做个 todo，单机使用，不用登录
正确做法：信息已完整，直接开始
```

### 7.3 引导用户给完整信息

每次 task 完成后，feedback 收集"agent 问的问题是否合理"，用于优化 prompt。

---

## 8. 超时降级

### 8.1 默认决策

agent 调 AskUser 时可指定 `defaultDecision`：

```json
{
  "prompt": "用 Tailwind 还是 CSS Module？",
  "kind": "single",
  "options": [
    {"value": "tailwind", "label": "Tailwind CSS"},
    {"value": "module", "label": "CSS Module"}
  ],
  "defaultDecision": "tailwind",
  "timeoutSec": 300
}
```

5 分钟没答 → 自动选 tailwind，agent 继续。

### 8.2 无默认决策

agent 没设 defaultDecision：

- 5 分钟没答 → cancel；
- agent 看到 timeout → 自己决定（换方案 / 求助 / 用合理默认）。

### 8.3 异步任务（长程任务）

长程任务无人值守时，**所有 AskUser 必须有 defaultDecision**，否则任务会卡：

```java
// 本代码仅作学习材料参考
if (ctx.isTaskMode() && defaultDecision == null) {
    return ToolResult.error("长程任务必须配置 defaultDecision");
}
```

---

## 9. 改主意

用户提交答案后想改：

```java
// 本代码仅作学习材料参考
@PostMapping("/{qid}/revise")
public void revise(@PathVariable UUID qid, @RequestBody AnswerRequest req) {
    // 1. 旧答案标 revised
    repo.markRevised(qid);
    // 2. 推送 revision 事件
    // 3. 注入新 tool_result 给 agent（"用户改主意了，新答案是 X"）
    // 4. agent 看到 revision 后调整
}
```

UI 提示：

```
[decision made] React
[decision revised] Vue ← 5 分钟后改了
[thought] 用户改了答案，调整方案
```

---

## 10. 部分回答

agent 一次问 5 个字段，用户答了 3 个：

```json
POST /questions/{qid}/answer
{
  "values": { "tech_stack": "react", "storage": "backend", "auth": "login" },
  "skip": ["deploy_target", "ci"]
}
```

agent 看到：

```
用户回答：tech_stack=react, storage=backend, auth=login
用户跳过：deploy_target, ci
```

agent 可以：用合理默认补全跳过的、或者再问一次。

---

## 11. 多 question 并发

agent 一次调多个 AskUser（比如 5 个独立问题）：

```java
// 本代码仅作学习材料参考
// 当前 Agent Loop 是串行执行，多个 AskUser 会一个一个等
// v2 可以并行
```

但**用户体验差**（5 个弹窗同时跳出来）。建议：
- system prompt 约束"一次只调一个 AskUser"；
- 或者把多个字段合并成一个 form（kind=form）。

---

## 12. 审批权限

某些角色不应被 agent 问（避免信息泄漏）：

```
permission rule:
  AskUser(prompt: *password*) DENY
  AskUser(prompt: *credit card*) DENY
  AskUser(prompt: *api key*) DENY
```

---

## 13. 审计

所有 question + answer 进 DB（pending_questions 表），可查：
- 谁在什么时候问了什么；
- 谁答了什么；
- 是否超时；
- 是否事后改了。

合规要求至少保留 90 天。

---

## 14. 与已有章节的关系

| 章节 | 关系 |
|------|------|
| 04-Agent-Loop | 挂起-恢复机制 |
| 05-工具系统 | AskUser 是一个 Tool |
| 12-Hooks | PreToolUse hook 可拦截 AskUser |
| 14-上下文工程 | system prompt 注入"何时问"指令 |
| 17-全链路可观测 | decision 事件 |
| 18-错误恢复 | 超时降级 / 进程重启恢复 |
| 20-审批流 | 复用同一 QuestionBus |

---

## 15. 本章产出

```
后端：
  ✅ AskUserTool
  ✅ QuestionBus（挂起-恢复 + 持久化）
  ✅ pending_questions 表
  ✅ 超时降级 + 重启恢复
  ✅ 改主意 + 部分回答
  ✅ 审计

前端：
  ✅ QuestionCard（5 种 kind）
  ✅ 接入活动流（decision 事件）
  ✅ REST answer/revise/cancel 接口
```

---

## 16. v2 路线

- 多 question 并行；
- 选项动态生成（来自代码扫描 / 历史偏好 / 外部 API）；
- 多人协作决策（leader + reviewer 都要答）；
- 学习用户偏好（"你上次选了 Postgres，这次也用吗？"）。

---

## 17. Web 项目专项升级（21-22 章预告）

| 升级 | 章节 | 解决问题 |
|------|------|---------|
| QuestionCard 移动端全屏 | 21 §7.5 | 手机上按钮太小 |
| 跨 tab 同步 answer | 22 §4.5 | tab A 答，tab B 不知道 |
| 离线时 question 排队 | 22 §2.5 | 断网期间发的答案不丢 |
| a11y：`aria-live=assertive` | 21 §13.3 | 屏幕阅读器即时播报新 question |
| 键盘选择 / 提交 | 21 §8 | 1-9 选选项、Enter 提交、Esc 取消 |
| 表单 zod schema | 21 §1 | form kind 强校验 |

---

## 18. 下一步

进入 [20-审批与审核流](./20-审批与审核流.md)，复用 QuestionBus 实现工具审批 / Diff Review / 任务阶段确认。
