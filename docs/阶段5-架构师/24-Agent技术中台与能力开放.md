# 24 · Agent 技术中台与能力开放

> 阶段：5 架构师 · 难度：⭐⭐⭐⭐⭐ · 预计：2 天
> 前置：[23 Agent 数据中台架构](23-Agent数据中台架构.md)
> 产出：掌握 Agent 技术中台的设计——能力抽象、开放 API、开发者门户、生态建设

---

## 你将学会

- 技术中台的定位：能力沉淀 + 能力开放
- Agent 能力分层模型（基础设施 → 通用能力 → 领域能力）
- 开放 API 设计与开发者门户
- 生态建设：插件市场 + 合作伙伴集成

---

## 技术中台定位

```mermaid
flowchart TB
    subgraph Business["业务应用层"]
        B1["智能客服"]
        B2["代码助手"]
        B3["运维 Agent"]
        B4["销售助手"]
        B5["更多应用..."]
    end

    subgraph Mid["技术中台（能力层）"]
        M1["对话引擎<br/>(LLM 调用/记忆/流式)"]
        M2["检索引擎<br/>(向量/全文/图谱)"]
        M3["工具引擎<br/>(注册/调度/沙箱)"]
        M4["编排引擎<br/>(Workflow/Agent 循环)"]
        M5["安全引擎<br/>(审核/脱敏/审计)"]
        M6["数据引擎<br/>(采集/标注/评估)"]
        M7["计费引擎<br/>(计量/配额/账单)"]
    end

    subgraph Infra["基础设施层"]
        I1["K8s"]
        I2["数据库/缓存"]
        I3["消息队列"]
        I4["监控告警"]
    end

    Business --> Mid --> Infra

    style Mid fill:#2196f3,color:#fff
```

中台的核心原则：**一次建设，多处复用。** 不为每个应用重复造轮子。

---

## 知识讲解

### 1. 能力分层模型

```mermaid
flowchart TB
    subgraph L3["L3 领域能力（应用团队）"]
        L3A["客服话术模板"]
        L3B["代码评审规则"]
        L3C["运维知识库"]
    end

    subgraph L2["L2 通用能力（中台团队）"]
        L2A["对话能力<br/>ChatClient 封装"]
        L2B["RAG 能力<br/>检索+生成"]
        L2C["Agent 能力<br/>循环+工具"]
        L2D["多模态能力<br/>图文语音"]
        L2E["安全能力<br/>审核+脱敏"]
        L2F["评估能力<br/>GoldenSet+指标"]
    end

    subgraph L1["L1 基础能力（基础设施）"]
        L1A["模型代理<br/>(多模型路由)"]
        L1B["向量存储<br/>(统一接口)"]
        L1C["消息总线<br/>(事件驱动)"]
        L1D["可观测<br/>(统一监控)"]
    end

    L3 --> L2 --> L1
```

### 2. 能力开放架构

```mermaid
flowchart LR
    subgraph Portal["开发者门户"]
        P1["API 文档"]
        P2["在线调试"]
        P3["SDK 下载"]
        P4["用量看板"]
        P5["应用管理"]
    end

    subgraph Gateway["开放 API 网关"]
        G1["认证鉴权"]
        G2["限流配额"]
        G3["计量计费"]
        G4["版本管理"]
    end

    subgraph Capabilities["开放能力"]
        C1["对话 API<br/>/v1/chat"]
        C2["检索 API<br/>/v1/search"]
        C3["嵌入 API<br/>/v1/embeddings"]
        C4["工具 API<br/>/v1/tools/execute"]
        C5["Agent API<br/>/v1/agent/run"]
        C6["知识库 API<br/>/v1/kb/ingest"]
        C7["评估 API<br/>/v1/eval"]
    end

    Portal --> Gateway --> Capabilities
```

### 3. 核心 API 设计

```java
package demo.demo05.platform.openapi;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 开放 API — 对话能力
 */
@RestController
@RequestMapping("/v1/chat")
public class ChatOpenApiController {

    /**
     * 同步对话
     */
    @PostMapping("/completions")
    public ChatResponse completions(
            @RequestHeader("Authorization") String apiKey,
            @RequestBody ChatRequest request) {

        // 网关层已做认证和限流
        return chatEngine.execute(request);
    }

    /**
     * 流式对话（SSE）
     */
    @PostMapping(value = "/stream", produces = "text/event-stream")
    public Flux<ChatChunk> stream(
            @RequestHeader("Authorization") String apiKey,
            @RequestBody ChatRequest request) {

        return chatEngine.stream(request);
    }

    /**
     * 带工具的 Agent 对话
     */
    @PostMapping("/agent")
    public AgentResponse agent(
            @RequestHeader("Authorization") String apiKey,
            @RequestBody AgentRequest request) {

        return agentEngine.execute(request);
    }
}

/**
 * 开放 API — 知识库能力
 */
@RestController
@RequestMapping("/v1/kb")
public class KnowledgeOpenApiController {

    /**
     * 文档摄入
     */
    @PostMapping("/ingest")
    public IngestResponse ingest(
            @RequestHeader("Authorization") String apiKey,
            @RequestBody IngestRequest request) {

        int chunkCount = kbService.ingest(request);
        return new IngestResponse("ok", chunkCount);
    }

    /**
     * 语义检索
     */
    @PostMapping("/search")
    public SearchResponse search(
            @RequestHeader("Authorization") String apiKey,
            @RequestBody SearchRequest request) {

        List<SearchResult> results = kbService.search(request);
        return new SearchResponse(results);
    }
}
```

### 4. 开发者门户

```mermaid
flowchart TB
    subgraph Onboarding["入驻流程"]
        O1["注册开发者账号"]
        O2["创建应用 → 获取 API Key"]
        O3["选择需要的能力"]
        O4["设置用量限制"]
        O5["获取 SDK + 文档"]
    end

    subgraph DevEx["开发体验"]
        D1["API 文档（自动生成）"]
        D2["在线 Playground"]
        D3["代码示例"]
        D4["Postman 集合"]
        D5["SDK（Java/Python/JS）"]
    end

    subgraph Operations["运营管理"]
        OP1["用量看板"]
        OP2["费用账单"]
        OP3["告警通知"]
        OP4["工单支持"]
    end

    Onboarding --> DevEx --> Operations
```

### 5. 能力注册与发现

```java
package demo.demo05.platform;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 能力注册中心
 * 每个中台能力注册自己的元数据，供应用方发现和调用
 */
@Component
public class CapabilityRegistry {

    private final Map<String, Capability> capabilities = new ConcurrentHashMap<>();

    /**
     * 注册能力
     */
    public void register(Capability cap) {
        capabilities.put(cap.id(), cap);
    }

    /**
     * 发现能力
     */
    public List<Capability> discover(String category) {
        return capabilities.values().stream()
                .filter(c -> category == null || c.category().equals(category))
                .sorted(Comparator.comparing(Capability::popularity).reversed())
                .toList();
    }

    /**
     * 获取能力的调用方式
     */
    public CapabilitySpec getSpec(String capabilityId) {
        Capability cap = capabilities.get(capabilityId);
        return new CapabilitySpec(
            cap.id(),
            cap.endpoint(),
            cap.apiSchema(),
            cap.sdkExample(),
            cap.pricing()
        );
    }
}

record Capability(
    String id,              // "chat-stream"
    String name,            // "流式对话"
    String category,        // "conversation"
    String description,
    String endpoint,        // "/v1/chat/stream"
    String apiSchema,       // OpenAPI Schema
    String sdkExample,      // SDK 示例代码
    String pricing,         // 计价规则
    int popularity          // 调用次数（排序用）
) {}

record CapabilitySpec(
    String capabilityId,
    String endpoint,
    String apiSchema,
    String sdkExample,
    String pricing
) {}
```

---

## 生态建设路线

```mermaid
flowchart LR
    subgraph P1["阶段1：内部能力开放"]
        P1A["中台能力 API 化"]
        P1B["内部团队使用"]
        P1C["验证能力稳定性"]
    end

    subgraph P2["阶段2：合作伙伴开放"]
        P2A["合作伙伴 API 接入"]
        P2B["联合解决方案"]
        P2C["白标能力"]
    end

    subgraph P3["阶段3：开发者生态"]
        P3A["公开开发者门户"]
        P3B["插件市场"]
        P3C["社区运营"]
    end

    P1 --> P2 --> P3
```

---

## 常见坑

- ❌ **中台变成大泥球** → 中台什么都做，变成一个巨大的依赖。中台应该是薄薄的能力层
- ❌ **能力不收敛** → 每个应用都往中台塞自己的特殊需求。需要严格的抽象和通用化
- ❌ **API 设计不一致** → 每个能力的风格/版本/错误码都不一样。需要 API 设计规范
- ❌ **没有版本管理** → 改了 API 导致所有调用方全挂。需要语义化版本 + 弃用流程
- ❌ **计费不准** → 开放 API 收费但计量不准，客户投诉。需要精确的 Token 计量
- ❌ **文档与代码不同步** → API 文档更新滞后。用 OpenAPI 自动生成文档

---

## 验收检查

- [ ] 中台核心能力（对话/检索/工具/编排）可被独立调用
- [ ] 有统一的开放 API 网关（认证/限流/计量/版本）
- [ ] 有开发者门户（API 文档/在线调试/SDK）
- [ ] 能力有注册和发现机制
- [ ] API 有语义化版本管理
- [ ] 计量计费准确

---

## 下一步

→ 下一篇：[25 Agent 全栈交付与毕业评估](25-Agent全栈交付与毕业评估.md)
