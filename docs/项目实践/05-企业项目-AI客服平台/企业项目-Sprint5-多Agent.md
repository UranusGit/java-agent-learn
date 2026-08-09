# Sprint 5 详细实现：多 Agent 编排 + Workflow

> 目标：路由 Agent → 工人 Agent → 评审 Agent，五大 Workflow 全用上
> 时间：1.5 周 · 前置：Sprint 4 完成

---

## Day 1-3：路由 Agent

### Step 1：SharedContext（多 Agent 共享状态）

```java
package com.agentforge.agent;

public class SharedContext {
    private String sessionId;
    private String tenantId;
    private String userQuery;
    private String intent;           // 路由 Agent 写入
    private String draftReply;       // 工人 Agent 写入
    private String reviewFeedback;   // 评审 Agent 写入
    private boolean approved;
    private int revisionCount;
    // getters/setters...
}
```

### Step 2：RouterAgent（Routing 模式）

```java
@Service
public class RouterAgent {

    public enum Intent { TECH_SUPPORT, TICKET, FAQ, UNKNOWN }

    public Intent classify(String userQuery) {
        String result = chatClient.prompt()
                .system("""
                    你是意图分类器。判断用户意图：
                    - TECH_SUPPORT：技术问题/产品使用/知识库查询
                    - TICKET：工单创建/查询/更新/投诉
                    - FAQ：常见问题/公司信息
                    - UNKNOWN：无法分类
                    只输出类别名。
                    """)
                .user(userQuery)
                .call().content().trim().toUpperCase();
        try { return Intent.valueOf(result); }
        catch (Exception e) { return Intent.UNKNOWN; }
    }
}
```

### Step 3：AgentOrchestrator（编排器）

```java
@Service
public class AgentOrchestrator {

    public String handle(String userQuery, String tenantId, String sessionId) {
        // 1. 路由
        var intent = routerAgent.classify(userQuery);

        // 2. 分发到对应 Agent
        String draft = switch (intent) {
            case TECH_SUPPORT -> techSupportAgent.handle(userQuery, tenantId);
            case TICKET -> ticketAgent.handle(userQuery, tenantId);
            case FAQ -> faqAgent.handle(userQuery, tenantId);
            case UNKNOWN -> "抱歉，我无法理解您的问题。请尝试描述您的技术问题或工单需求。";
        };

        // 3. 评审（Evaluator-Optimizer 模式）
        var review = reviewAgent.review(draft, userQuery);
        if (!review.approved() && reviewAgent.getRevisionCount() < 3) {
            // 不通过 → 改进
            draft = chatClient.prompt()
                    .system("根据审校意见改进回复：" + review.feedback())
                    .user("原回复：" + draft)
                    .call().content();
        }

        return draft;
    }
}
```

---

## Day 4-5：工人 Agent

### Step 4：TechSupportAgent（RAG + Parallelization）

```java
@Service
public class TechSupportAgent {

    public String handle(String query, String tenantId) {
        TenantContext.setTenant(tenantId);
        var tools = toolRegistry.getToolsForTenant(tenantId, Set.of("kb"));

        return chatClient.prompt()
                .system("你是技术支持 Agent。用知识库工具查找信息并回答。")
                .user(query)
                .tools(tools)
                .call().content();
    }
}
```

### Step 5：TicketAgent（Chaining + 工具调用）

```java
@Service
public class TicketAgent {

    public String handle(String query, String tenantId) {
        TenantContext.setTenant(tenantId);
        var tools = toolRegistry.getToolsForTenant(tenantId, Set.of("ticket", "notify"));

        return chatClient.prompt()
                .system("""
                    你是工单处理 Agent。可以创建/查询工单、发送通知。
                    创建工单时从用户消息中提取标题、描述、优先级。
                    """)
                .user(query)
                .tools(tools)
                .call().content();
    }
}
```

### Step 6：ReviewAgent（Evaluator 模式）

```java
@Service
public class ReviewAgent {

    public ReviewResult review(String draft, String originalQuery) {
        String result = chatClient.prompt()
                .system("""
                    你是回复质量评审。评估回复是否：
                    1. 准确回答了用户问题
                    2. 没有编造信息
                    3. 语气专业
                    输出 JSON：{"approved":true/false,"feedback":"意见"}
                    """)
                .user("用户问题：" + originalQuery + "\n回复内容：" + draft)
                .call().content();
        return parseResult(result);
    }
}
```

---

## Day 6-7：防失控保护

### Step 7：BudgetGuardAdvisor + LoopDetectionAdvisor

```java
@Component
public class BudgetGuardAdvisor implements BaseAdvisor {
    private final AtomicLong tokens = new AtomicLong();
    private static final long MAX = 50_000;

    @Override
    public ChatClientResponse after(ChatClientResponse response) {
        long total = tokens.addAndGet(
            response.chatResponse().getMetadata().getUsage().getTotalTokens());
        if (total > MAX)
            throw new BudgetExceededException("预算超限");
        return response;
    }
    @Override public int getOrder() { return 5; }
}

@Component
public class LoopDetectionAdvisor implements BaseAdvisor {
    private final Deque<String> actions = new LinkedList<>();
    private static final int WINDOW = 5;

    @Override
    public ChatClientRequest before(ChatClientRequest request) {
        String sig = extractSignature(request);
        actions.addLast(sig);
        if (actions.size() > WINDOW) actions.removeFirst();
        if (actions.size() == WINDOW && new HashSet<>(actions).size() == 1)
            throw new LoopDetectedException("死循环检测");
        return request;
    }
    @Override public int getOrder() { return 3; }
}
```

---

## Day 8-10：流式 + 组装

### Step 8：流式多 Agent

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatStream(@RequestParam String q,
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestParam(defaultValue = "default") String sessionId) {

    TenantContext.set(tenantId, sessionId);
    return Flux.create(sink -> {
        try {
            String reply = orchestrator.handle(q, tenantId, sessionId);
            // 逐段输出
            for (String chunk : reply.split("(?<=\\n)")) {
                sink.next(chunk);
            }
            sink.complete();
        } catch (Exception e) {
            sink.next("\n⚠️ " + e.getMessage());
            sink.complete();
        } finally {
            TenantContext.clear();
        }
    });
}
```

---

## Sprint 5 验收

- [ ] 路由 Agent 正确分类意图
- [ ] 不同意图路由到不同工人 Agent
- [ ] 评审 Agent 能打回低质量回复
- [ ] Agent 有三重保护（maxTurns + 预算 + 死循环）
- [ ] 多 Agent 协作通过 SharedContext 传递状态
- [ ] 流式输出正常

---

## 下一步

→ [Sprint 6：生产化](企业项目-Sprint6-生产化.md)
