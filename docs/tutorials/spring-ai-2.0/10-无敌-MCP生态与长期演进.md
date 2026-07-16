# L10 无敌 - MCP 生态、A2A、长期演进

> 从"用 MCP"到"发 MCP"，从"单 Agent"到"Agent 互联网"，从"今天能跑"到"长期不腐烂"。
>
> 前置：[`./09-无敌-编排引擎与多Agent.md`](./09-无敌-编排引擎与多Agent.md)
> 预计：持续

---

## 1. 从消费 MCP 到发布 MCP

L4 学过怎么消费别人写的 MCP Server。L10 学怎么**自己发布** MCP Server 给整个企业 / 整个生态用。

### 1.1 企业内部 MCP 发布流程

```
┌────────────────────────────────────────────────────┐
│ 1. 识别"哪些能力值得封装"                          │
│    - 高频复用（>3 个 AI 应用会用）                 │
│    - 稳定接口（不频繁变化）                         │
│    - 跨语言需求（Java / Python / Node 都要用）     │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│ 2. 选协议                                           │
│    - stdio（本地进程，性能最好）                    │
│    - SSE / HTTP（远程访问，可部署）                 │
│    - WebSocket（双向，适合流式）                    │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│ 3. 实现 + 测试                                      │
│    - @McpTool / @McpResource / @McpPrompt          │
│    - 写 MCP Inspector 测试                          │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│ 4. 部署 + 注册                                      │
│    - Docker 部署到 K8s                              │
│    - 注册到企业 MCP Registry                        │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│ 5. 版本管理 + 监控                                  │
│    - Semantic versioning                            │
│    - 调用监控（QPS / 延迟 / 失败率）                │
└────────────────────────────────────────────────────┘
```

### 1.2 企业 MCP Registry（注册中心）

类似 NPM Registry，但服务于 MCP Server：

| 能力 | 实现 |
|------|------|
| 注册 | `POST /api/servers` 上报元数据 |
| 发现 | `GET /api/servers?q=employee` 搜索 |
| 版本 | 同名 Server 多版本共存 |
| 鉴权 | OAuth2 / API Key |
| 计费 | 按调用次数 / token 数 |

**自研建议**：参考 [Smithery](https://smithery.ai/) 和 [mcp.so](https://mcp.so)。

### 1.3 MCP Server 高质量标准

- [ ] Schema 严格（JSON Schema 校验 input）
- [ ] 错误信息友好（不抛异常，返回结构化错误）
- [ ] 幂等性（同一参数调用多次结果一致）
- [ ] 超时控制（不让 Client 一直等）
- [ ] 限流（防滥用）
- [ ] 日志（所有调用记录）
- [ ] 文档（README + 调用示例）

---

## 2. A2A（Agent-to-Agent）协议

### 2.1 背景

Google 2025-04 推出的 A2A 协议。MCP 是"Agent ↔ Tool"，A2A 是"Agent ↔ Agent"。

### 2.2 跟 MCP 的区别

| 维度 | MCP | A2A |
|------|-----|-----|
| 通信双方 | Agent ↔ Tool / Resource | Agent ↔ Agent |
| 抽象层 | 函数级 | 任务级 |
| 状态 | 无状态调用 | 长期协作状态 |
| 协议 | JSON-RPC over stdio/HTTP | HTTP + JSON-LD |

### 2.3 跟 Spring AI 的关系

Spring AI 团队已表态支持 A2A，但**当前（2026-07）还没有 GA 集成**。预计 2.1 / 2.2 版本会加入。

关注：[spring-projects/spring-a2a](https://github.com/spring-projects/spring-a2a)（社区讨论中）。

### 2.4 当前怎么实现 Agent 互联

在 A2A GA 之前，用 MCP 资源 + 自定义协议做过渡：

```java
@McpResource(
    uri = "agent://code-reviewer/inbox",
    description = "接收外部 Agent 发来的代码评审请求"
)
public String receiveReviewRequest(String payload) {
    RequestQueue.offer(payload);
    return "queued";
}

@McpTool(description = "拉取评审结果")
public String pollReviewResult(String requestId) {
    return resultStore.get(requestId);
}
```

外部 Agent 通过 MCP 把任务塞进来，定期 poll 结果。

---

## 3. 长期演进的四个维度

### 3.1 技术栈演进

| 维度 | 当前（2026-07） | 1 年后预期 |
|------|----------------|-----------|
| Spring Boot | 4.0 | 4.1 / 4.2 |
| Spring AI | 2.0.0 GA | 2.1 / 2.2 |
| 模型协议 | MCP 1.0 | MCP 1.x + A2A 1.0 |
| 编排引擎 | LangGraph4j 1.0 | 1.x 成熟 |
| 评估 | 手工 / ad-hoc | Spring AI Eval（待发布） |

### 3.2 模型演进

- 多模态（图片 / 视频 / 音频）会成为标配
- 长上下文（1M+ tokens）会让 RAG 重设计
- Agent 模型（专门优化的 function calling 模型）

### 3.3 业务演进

| 阶段 | 重点 |
|------|------|
| 短期 | 把现有功能 AI 化（chat / RAG） |
| 中期 | 重设计业务流程（Agent native） |
| 长期 | 跨企业 Agent 协作（供应链 / 法务 / 财务） |

### 3.4 团队演进

| 阶段 | 团队规模 |
|------|---------|
| 试水 | 1 个工程师 |
| POC | 2-3 人小组 |
| 生产化 | 5-10 人 + SRE |
| 平台化 | 10+ 人，AI 平台团队 |

---

## 4. 反模式清单（积累 + 持续更新）

### 4.1 架构反模式

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 一个超级 Prompt 解决所有问题 | 准确率低 / token 浪费 | 五大 Workflow 模式拆解 |
| Agent 自主决定一切 | 不可控 / 不可预测 | 能 Workflow 就别 Agent |
| LLM 直接执行 SQL / 命令 | 安全灾难 | 通过 Tool 抽象 |
| 每个业务点重新装配 Advisor | 代码重复 | 模板化 + 配置化 |

### 4.2 实现反模式

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 不用 Prompt Cache | 成本 3-5x | 固定前缀 + 用户输入末尾 |
| 不限制 maxTurns | 失控 / 烧钱 | 三重保护 |
| Tool 失败直接抛异常 | LLM 无法自我修复 | 返回 ToolResult.error |
| 所有逻辑塞 ChatClient | 难测试 / 难维护 | 业务逻辑在 Service |

### 4.3 运维反模式

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 不接 OTel | 黑盒 | GenAI Semantic Conventions |
| 没有审计 | 合规失败 | event-sourced 落库 |
| 不限流 | 被刷爆 | Resilience4j RateLimiter |
| 上线前不跑评估 | 质量不可控 | 黄金集回归测试 |

### 4.4 团队反模式

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| AI 应用是"另类" | 团队排斥 | 标准化 Spring AI |
| Prompt 在代码里硬编码 | 难维护 | 模板文件 + 版本控制 |
| 没有 AI 评估流程 | 上线心慌 | 建立"黄金集"持续回归 |
| 过度依赖一个模型 | 厂商锁定 | 多模型抽象（ChatModel 接口） |

---

## 5. 评估体系

### 5.1 为什么必须建评估

> "改了 prompt 不知道是变好还是变坏" = 最大的工程风险。

### 5.2 黄金集（Golden Set）

收集 100-500 个真实业务 case：
```json
{
  "input": "查一下我的订单状态",
  "expected_tools": ["queryOrder"],
  "expected_keywords": ["订单号", "状态"],
  "forbidden_keywords": ["别的用户", "管理员密码"],
  "max_latency_ms": 5000
}
```

每次 prompt / 模型 / Advisor 改动，跑一次评估：

```java
@Service
public class EvaluationService {

    public EvaluationReport runGoldenSet() {
        List<TestCase> cases = loadGoldenSet();
        EvaluationReport report = new EvaluationReport();

        for (TestCase tc : cases) {
            long start = System.currentTimeMillis();
            String result = chatClient.prompt().user(tc.input()).call().content();
            long duration = System.currentTimeMillis() - start;

            double score = score(result, tc);
            report.add(tc, score, duration);
        }

        return report;
    }

    private double score(String result, TestCase tc) {
        double s = 1.0;
        for (String kw : tc.expectedKeywords()) {
            if (!result.contains(kw)) s -= 0.2;
        }
        for (String kw : tc.forbiddenKeywords()) {
            if (result.contains(kw)) s -= 0.5;
        }
        return Math.max(0, s);
    }
}
```

### 5.3 评估指标

| 指标 | 阈值 |
|------|------|
| 通过率 | > 90% |
| 平均分 | > 0.85 |
| p95 延迟 | < 5s |
| 工具调用准确率 | > 95% |
| 成本 / case | < $0.01 |

### 5.4 在 CI 里跑评估

```yaml
# .github/workflows/eval.yml
name: AI Evaluation
on: [pull_request]

jobs:
  eval:
    steps:
      - uses: actions/checkout@v4
      - name: Run golden set
        run: ./mvnw test -Dtest=GoldenSetEvaluation
      - name: Check regression
        run: |
          SCORE=$(jq '.averageScore' target/eval-report.json)
          if (( $(echo "$SCORE < 0.85" | bc -l) )); then
            echo "Quality regression: $SCORE"
            exit 1
          fi
```

---

## 6. Prompt 工程的长期管理

### 6.1 Prompt 作为代码

```
prompts/
├── v1/
│   ├── code-review-bug.st
│   └── code-review-security.st
├── v2/
│   ├── code-review-bug.st   # 改进版
│   └── code-review-security.st
├── v3-prod/
│   └── ...
└── experiments/
    └── try-cot-prompt.st
```

### 6.2 版本切换

```yaml
app:
  prompts:
    version: v2
```

```java
@Configuration
public class PromptConfig {

    @Value("${app.prompts.version}")
    private String version;

    public PromptTemplate load(String name) {
        return new PromptTemplate(
            new ClassPathResource("prompts/" + version + "/" + name + ".st")
        );
    }
}
```

### 6.3 A/B 测试

```java
@GetMapping("/review")
public String review(@RequestParam String code, @RequestParam(required = false) String variant) {
    String v = variant != null ? variant : defaultVersion;
    PromptTemplate template = promptConfig.load("v" + v + "/code-review-bug");
    // ...
}
```

---

## 7. 持续学习清单

### 7.1 必须关注

- [Spring AI 官方博客](https://spring.io/blog)
- [Anthropic Engineering Blog](https://www.anthropic.com/engineering)
- [Anthropic GitHub - Claude Code 源码](https://github.com/anthropics/claude-code)（学习 Agent 设计最佳实践）
- [Model Context Protocol spec](https://modelcontextprotocol.io/)

### 7.2 定期评估

- 每月：跑黄金集，看是否需要更新 prompt
- 每季：评估新模型（GPT / Claude / DeepSeek / Qwen 新版本）
- 每年：复盘架构，看哪些可以简化

### 7.3 团队知识沉淀

- 内部 Wiki 记录所有 prompt 版本
- 失败案例库（什么 case 让 LLM 翻车）
- 工具库（哪些 Tool 设计有用，哪些没用）

---

## 8. 一个"完美项目"的形态

完成 L1-L10 后，你的项目应该长这样：

```
┌──────────────────────────────────────────────────────────────────┐
│ 业务层（Controller）                                              │
│  - /chat（流式）                                                  │
│  - /review（代码评审）                                            │
│  - /agent（多 Agent 协作）                                        │
└────────────────────────────────┬─────────────────────────────────┘
                                 │
┌────────────────────────────────▼─────────────────────────────────┐
│ Advisor 链（org.demo02.toolkit.*）                                │
│  - AuditAdvisor（审计）                                           │
│  - TenantAwareAdvisor（多租户）                                   │
│  - TokenBudgetGuard / MaxTurnsGuard / LoopDetector（三重保护）    │
│  - MessageChatMemoryAdvisor（session-backed）                     │
│  - ToolCallingAdvisor                                             │
│  - StructuredOutputValidationAdvisor                              │
│  - SimpleLoggerAdvisor                                            │
└────────────────────────────────┬─────────────────────────────────┘
                                 │
┌────────────────────────────────▼─────────────────────────────────┐
│ Workflow 模式（org.demo02.toolkit.workflow）                      │
│  - PromptChainingAdvisor                                          │
│  - ParallelizationAdvisor                                         │
│  - RoutingAdvisor                                                 │
│  - OrchestratorWorkersAdvisor                                     │
│  - EvaluatorOptimizerAdvisor                                      │
└────────────────────────────────┬─────────────────────────────────┘
                                 │
┌────────────────────────────────▼─────────────────────────────────┐
│ 工具生态（org.demo02.toolkit + MCP）                              │
│  - 本地 @Tool Bean                                                │
│  - 远程 MCP Client → filesystem (Node)                            │
│  - 远程 MCP Client → employee-mcp (Java)                          │
│  - 自研 MCP Server（暴露给其他 Agent）                            │
└────────────────────────────────┬─────────────────────────────────┘
                                 │
┌────────────────────────────────▼─────────────────────────────────┐
│ 可观测 / 可靠性                                                   │
│  - OTel GenAI Semantic Conventions + Prometheus + Grafana         │
│  - Resilience4j 三层防护                                          │
│  - event-sourced 审计 + 脱敏                                      │
└──────────────────────────────────────────────────────────────────┘
```

---

## 9. 理解检查

1. 什么样的能力值得封装为 MCP Server 发布？
2. MCP 和 A2A 的本质区别是什么？
3. 黄金集评估的核心价值是什么？
4. 为什么 Prompt 要版本化管理？
5. 团队反模式中最危险的是哪一条？为什么？

---

## 10. 持续任务清单

这不是"完成就结束"的任务，是持续运营：

- [ ] 建立企业 MCP Registry（即使只有 1 个 Server）
- [ ] 建立黄金集（哪怕只有 50 个 case）
- [ ] CI 集成评估流程
- [ ] Prompt 版本化目录
- [ ] 失败案例库
- [ ] 每月评估新模型
- [ ] 每季评估新工具 / 新模式
- [ ] 跟踪 A2A / Spring AI 2.1 进展

---

## 11. 全程完成自检

完成 L1-L10 后，回头看 [`./00-目录索引.md`](./00-目录索引.md) 的验收总清单：

- [ ] 不查阅资料就能把 1.0 项目升级到 2.0
- [ ] 不用任何 while 循环写出一个 Agent 应用
- [ ] 五大 Workflow 模式每个都能在 1 小时内搭出 demo
- [ ] 写出可发布的 MCP Server
- [ ] 配出 OTel + Prometheus + Prompt Cache 的生产可观测栈
- [ ] 设计多租户 + 审计 + 三重保护的 Agent 系统
- [ ] 判断"何时该上编排引擎"

---

## 12. 后续方向

完成全程后，可以考虑：

| 方向 | 内容 |
|------|------|
| **专精评估工程** | 学习 [Promptfoo](https://www.promptfoo.dev/) / [DeepEval](https://docs.confident-ai.com/) |
| **专精多模态** | 图片 / 视频 / 音频理解 |
| **专精本地模型** | Ollama / vLLM 部署 / 微调 |
| **专精 RAG 进阶** | Hybrid Search / Reranking / GraphRAG |
| **跨企业 A2A** | 跟进 A2A GA，设计供应链 Agent 协作 |

---

恭喜完成整个学习路线。回到 [`./00-目录索引.md`](./00-目录索引.md) 复盘，或进入 [`./11-复现手册-流式与工具调用.md`](./11-复现手册-流式与工具调用.md) 实操。
