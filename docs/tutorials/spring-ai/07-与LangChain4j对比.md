# Spring AI 07 - 与 LangChain4j 深度对比（阶段 2 收尾）

> 你已经用两套框架各做过一遍相同的事。现在是时候做技术决策了。
> 本节是阶段 2 的总结，写完这篇笔记就可以进入阶段 3。

---

## 1. 核心设计哲学对比

### 1.1 一句话总结

| 框架 | 哲学 |
|------|------|
| **LangChain4j** | "LangChain 的 Java 移植"，目标是把 LangChain Python 的概念完整带到 Java |
| **Spring AI** | "把 AI 装进 Spring"，目标是让 Spring 工程师用熟悉的方式做 AI |

### 1.2 设计根源差异

```
LangChain4j 来源          Spring AI 来源
      ↓                       ↓
LangChain Python         Spring Boot / Spring Cloud
（社区生态）              （Spring 官方）
      ↓                       ↓
独立运行                深度集成 Spring
（可单 JVM 跑）          （必须 Spring 容器）
```

---

## 2. 核心抽象对照表

| 能力 | LangChain4j | Spring AI |
|------|-------------|-----------|
| 聊天模型 | `ChatLanguageModel` | `ChatModel` |
| 高层对话 | `AiServices`（接口） | `ChatClient`（Builder） |
| 记忆 | `ChatMemory` | `MessageChatMemoryAdvisor` |
| 拦截机制 | 无（手动装饰） | **`Advisor` 链** |
| Tool | `@Tool`（dev.langchain4j） | `@Tool`（spring.ai.tool） |
| Tool 参数 | `@P` | `@ToolParam` |
| Tool 注入业务 | 手动 new | **Spring Bean** |
| RAG | `ContentRetriever` + `RetrievalAugmentor` | `RetrievalAugmentationAdvisor`（1.0.0+） |
| 向量库 | `EmbeddingStore` | `VectorStore` |
| 文档加载 | `DocumentLoader` + 各 parser | Tika 一站式 |
| 结构化输出 | 接口返回类型 | `.entity(Class)` |
| Prompt 模板 | `@UserMessage` + `@V` | `PromptTemplate` |
| 流式 | `TokenStream` + 回调 | **`Flux<String>`** |
| 多模型 | 多个 model 实例 | 多个 ChatClient Bean |

---

## 3. 关键差异详解

### 3.1 AiServices vs ChatClient

```java
// LangChain4j：接口驱动
interface Assistant {
    @SystemMessage("...")
    String chat(@MemoryId String id, @UserMessage String msg);
}

Assistant agent = AiServices.builder(Assistant.class)
        .chatModel(model)
        .chatMemoryProvider(id -> ...)
        .tools(tools)
        .build();

// Spring AI：Builder 驱动
String answer = chatClient.prompt()
        .system("...")
        .user(msg)
        .advisors(spec -> spec.param(CONVERSATION_ID, id))
        .tools(tools)
        .call()
        .content();
```

**对比**：

| 维度 | AiServices | ChatClient |
|------|-----------|------------|
| 类型安全 | ✅ 接口签名 | ⚠️ 字符串 |
| IDE 提示 | ✅ 强 | ⚠️ 弱 |
| 灵活度 | 中（编译期固定） | 高（运行期拼接） |
| 学习成本 | 低（像 Mapper） | 中（要懂 Builder） |
| 多 Advisor 组合 | 弱 | 强 |

### 3.2 Advisor vs 手动装配

LangChain4j 的痛点：

```java
// 想加日志，要重新写 builder
Assistant withLog = AiServices.builder(Assistant.class)
        .chatModel(new LoggingModel(originalModel))  // 装饰器
        .build();
```

Spring AI 的优势：

```java
// 加日志只需加 Advisor，全局生效
ChatClient client = builder
        .defaultAdvisors(new LoggingAdvisor(), memoryAdvisor, ragAdvisor)
        .build();
```

**类比**：
- LangChain4j 像 Java IO 的 `BufferedReader(new FileReader(new File(...)))` —— 装饰器层层包
- Spring AI 像 Servlet Filter Chain —— 横切关注点

### 3.3 Tool 与 Spring 生态的融合

```java
// LangChain4j：Tool 是独立对象
EmployeeTools tools = new EmployeeTools();
tools.setEmployeeService(new EmployeeService(dataSource));  // 手动注入

// Spring AI：Tool 就是 Spring Bean
@Component
@RequiredArgsConstructor
public class EmployeeTools {
    private final EmployeeService service;  // 自动注入
}
```

Spring AI Tool 内部可以用：
- `@Transactional`
- `@Cacheable`
- Spring Data Repository
- 任何 Spring Bean

---

## 4. 各场景的推荐选择

### 4.1 必选 LangChain4j

| 场景 | 原因 |
|------|------|
| 非 Spring 项目（CLI / 库 / Quarkus） | 不引入 Spring 容器 |
| 学习 AI 概念 | API 设计更"教科书"，对应 Python LangChain |
| 极致控制每个细节 | Builder 手动装配更细 |
| 想用 LangChain Python 的成熟工具 | 概念完全对应 |

### 4.2 必选 Spring AI

| 场景 | 原因 |
|------|------|
| 已有 Spring Boot 项目 | 自然集成，零迁移成本 |
| 团队是 Spring 工程师 | 学习曲线最低 |
| 需要复杂 Advisor 组合（多租户 + RAG + 限流 + 审计） | Advisor 链是杀手锏 |
| 需要 Spring Cloud / Security / Data | 深度集成 |
| 生产级企业应用 | Spring 生态成熟 |

### 4.3 两者皆可

| 场景 | 备注 |
|------|------|
| 简单 RAG / Agent | 看团队栈 |
| 学习起步 | LangChain4j 更友好 |
| 微服务架构 | 看主语言栈 |

---

## 5. 性能与稳定性对比（实测参考）

| 维度 | LangChain4j | Spring AI |
|------|-------------|-----------|
| 启动速度 | 快（无 Spring 容器） | 慢（Spring 启动） |
| 内存占用 | 低 | 中 |
| 单次调用延迟 | 几乎一致 | 几乎一致 |
| 流式延迟 | 几乎一致 | 几乎一致 |
| 成熟度 | ⭐⭐⭐⭐（更早） | ⭐⭐⭐（较新） |
| 文档质量 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |

**结论**：技术指标差异极小，**选 Spring 生态匹配度**而非性能。

---

## 6. 团队选型决策树

```
你的项目是 Spring Boot 吗？
   ├── 否 → LangChain4j
   └── 是 → 团队对 AI 熟悉度？
            ├── 新手 → Spring AI（学习曲线低）
            └── 熟手 → 看是否需要复杂 Advisor 组合
                       ├── 需要 → Spring AI
                       └── 不需要 → 都行（推荐 Spring AI，统一栈）
```

---

## 7. 两个框架可以共存

### 7.1 同一项目里用两个

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
</dependency>
```

### 7.2 各自擅长的场景

| 用 LangChain4j 做 | 用 Spring AI 做 |
|------------------|----------------|
| 复杂 Agent 编排（LangGraph4j） | 主业务 API |
| 离线批处理脚本 | Web 接口 |
| 库代码（不依赖 Spring） | 生产服务 |

### 7.3 实践建议

**起步阶段**：选一个，**不要混用**，否则心智负担太重。

---

## 8. 你应该写下来的对比文档

建议在 `desc/notes/` 写一篇 500-1000 字的对比笔记，回答：

1. **同样的功能（如多轮对话 + Tool）**，你在两个框架里各写了多少行代码？
2. **哪个让你更"上手即用"**？为什么？
3. **哪个在生产场景让你更有信心**？为什么？
4. **如果让你给团队定一个标准**，你会怎么选？
5. **你看到两个框架各有什么不足**？

---

## 9. 阶段 2 完成检查清单

完成本节后，你应该能：

- [ ] 在 Spring Boot 项目里集成 Spring AI
- [ ] 用 `ChatClient` 实现 LLM 调用
- [ ] 用 `Advisor` 实现拦截（日志、限流、敏感词）
- [ ] 用 `MessageChatMemoryAdvisor` 实现多轮对话
- [ ] 用 `RetrievalAugmentationAdvisor` 实现 RAG（需要先引入 `spring-ai-rag` 依赖）
- [ ] 写自定义 `@Tool` Bean，注入业务 Service
- [ ] 用 `.entity(Class)` 实现结构化输出
- [ ] 用 `Flux<String>` 实现流式输出
- [ ] 配置多模型 Bean，实现智能路由
- [ ] **能讲清 Spring AI 和 LangChain4j 的核心差异**

---

## 10. 阶段 2 总结笔记建议

写到 `desc/notes/W2-SpringAI.md`，结构：

```markdown
# Spring AI 学习笔记

## 一、最大的 aha moment
（写 1-2 个让你恍然大悟的瞬间）

## 二、卡得最久的地方
（写 1-2 个让你卡几小时的点，和解决方案）

## 三、对 Spring AI 的整体评价
（生产力 / 学习曲线 / 稳定性）

## 四、与 LangChain4j 的对比结论
（你的个人选型偏好 + 理由）

## 五、下一步学习重点
（你觉得自己还需要补什么）
```

---

## 11. 下一步

完成阶段 2 后，按 `plan/00-整体路线.md`：

- **阶段 3**：RAG 入门（你已经做过两遍，可以重点做评估和优化）
- **阶段 4**：Agent + Tool 进阶（个人助理 Agent）
- **阶段 5**：生产化（流式、持久化、监控）

或者：

- 直接进阶段 6（综合项目），把 LangChain4j 和 Spring AI 在一个真实项目里融合

---

## 12. 双框架"各取所长"的架构建议

如果以后你做大型企业项目，可以考虑这种架构：

```
┌─────────────────────────────────────┐
│  Spring AI 层（业务编排）             │
│  ─ Controller / Service / Repository │
│  ─ Advisor 链（鉴权、限流、审计）     │
│  ─ Tool 调用（注入业务 Bean）         │
└────────────┬────────────────────────┘
             │
┌────────────▼────────────────────────┐
│  LangChain4j 层（复杂 Agent）         │
│  ─ LangGraph4j 状态机                │
│  ─ 多 Agent 协作                     │
│  ─ 自定义推理策略                    │
└─────────────────────────────────────┘
```

但**起步阶段不要这么搞**，先用一个框架把项目跑通。

---

完成阶段 2，给自己鼓个掌。两个主流 Java AI 框架你都已经摸透了，接下来是真正做出东西的阶段。
