# Sprint 3 详细实现：RAG 知识库 + 工具调用

> 目标：上传文档建知识库 + AI 调工具（工单/通知）
> 时间：1.5 周 · 前置：Sprint 2 完成

---

## Day 1-3：RAG 管道

### Step 1：引入 RAG 依赖

```xml
<!-- pom.xml 追加 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pdf-document-reader</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

### Step 2：配置向量库

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/agentforge
    username: ${DB_USER:postgres}
    password: ${DB_PASS:postgres}
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
```

### Step 3：文档导入服务

```java
package com.agentforge.rag;

import org.springframework.ai.document.*;
import org.springframework.ai.reader.pdf.*;
import org.springframework.ai.transformer.splitter.*;
import org.springframework.ai.vectorstore.*;
import org.springframework.stereotype.Service;
import org.springframework.core.io.Resource;

import java.util.List;

@Service
public class DocumentIngestService {

    private final VectorStore vectorStore;

    public int ingestPdf(Resource pdf, String tenantId, String filename) {
        // 加载
        var reader = new PagePdfDocumentReader(pdf);
        List<Document> rawDocs = reader.get();

        // 分块
        var splitter = TokenTextSplitter.builder()
                .chunkSize(500).minChunkSizeChars(350)
                .build();
        List<Document> chunks = splitter.apply(rawDocs);

        // 打标签
        chunks.forEach(c -> {
            c.getMetadata().put("tenant_id", tenantId);
            c.getMetadata().put("source", filename);
        });

        // 入库
        vectorStore.add(chunks);
        return chunks.size();
    }

    public List<Document> search(String tenantId, String query, int topK) {
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query).topK(topK)
                .similarityThreshold(0.6)
                .filterExpression("tenant_id == '" + tenantId + "'")
                .build()
        );
    }
}
```

### Step 4：文档管理接口

```java
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @PostMapping("/upload")
    public ApiResponse upload(@RequestParam("file") MultipartFile file,
                              @RequestHeader("X-Tenant-Id") String tenantId) {
        int chunks = ingestService.ingestPdf(
            file.getResource(), tenantId, file.getOriginalFilename());
        return ApiResponse.ok(Map.of("chunks", chunks, "filename", file.getOriginalFilename()));
    }

    @GetMapping("/search")
    public ApiResponse search(@RequestParam String q,
                              @RequestHeader("X-Tenant-Id") String tenantId) {
        var docs = ingestService.search(tenantId, q, 3);
        return ApiResponse.ok(docs.stream().map(d -> Map.of(
            "content", d.getText().substring(0, Math.min(200, d.getText().length())),
            "source", d.getMetadata().get("source")
        )).toList());
    }
}
```

---

## Day 4-7：工具注册表（借鉴 Claude Code）

### Step 5：SafeTool 基类

```java
package com.agentforge.tool;

public abstract class SafeTool {

    protected String error(String msg) { return "⚠️ " + msg; }

    protected String validateNotNull(String value, String name) {
        if (value == null || value.isBlank()) return error(name + "不能为空");
        return null;
    }
}
```

### Step 6：KnowledgeBaseTool

```java
@Component
public class KnowledgeBaseTool extends SafeTool {

    private final DocumentIngestService ragService;
    private final TenantContext tenantCtx;

    @Tool(description = "在企业知识库中搜索相关文档。" +
         "当用户询问公司制度、产品文档、技术问题时使用。" +
         "query 是搜索关键词。")
    public String searchKnowledgeBase(String query) {
        String tenantId = tenantCtx.getTenant();
        var docs = ragService.search(tenantId, query, 3);
        if (docs.isEmpty()) return error("知识库中没有找到相关文档");
        return docs.stream()
            .map(d -> "---\n来源：" + d.getMetadata().get("source") + "\n" + d.getText())
            .collect(Collectors.joining("\n\n"));
    }
}
```

### Step 7：TicketTool

```java
@Component
public class TicketTool extends SafeTool {

    private final TicketMapper ticketMapper;

    @Tool(description = "创建工单。title 是标题，description 是描述，priority 是优先级(高/中/低)")
    public String createTicket(String title, String description, String priority) {
        String err = validateNotNull(title, "标题");
        if (err != null) return err;

        var ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setPriority(priority);
        ticket.setTenantId(TenantContext.getTenant());
        ticket.setStatus("OPEN");
        ticketMapper.insert(ticket);
        return "✅ 已创建工单 #" + ticket.getId() + "（" + priority + "优先级）";
    }

    @Tool(description = "根据工单ID查询工单状态")
    public String getTicketStatus(Long ticketId) {
        var ticket = ticketMapper.findByIdAndTenant(ticketId, TenantContext.getTenant());
        if (ticket == null) return error("工单 #" + ticketId + " 不存在");
        return "工单 #" + ticket.getId() + "：" + ticket.getTitle() +
               "\n状态：" + ticket.getStatus() +
               "\n优先级：" + ticket.getPriority();
    }
}
```

### Step 8：ToolRegistry（统一注册 + 条件加载）

```java
@Component
public class ToolRegistry {

    private final KnowledgeBaseTool kbTool;
    private final TicketTool ticketTool;
    private final NotificationTool notifyTool;

    /**
     * 按租户权限动态返回可用工具
     * （借鉴 Claude Code 的条件工具注册）
     */
    public Object[] getToolsForTenant(String tenantId, Set<String> permissions) {
        List<Object> tools = new ArrayList<>();
        if (permissions.contains("kb")) tools.add(kbTool);
        if (permissions.contains("ticket")) tools.add(ticketTool);
        if (permissions.contains("notify")) tools.add(notifyTool);
        return tools.toArray();
    }
}
```

### Step 9：带工具的聊天接口

```java
@GetMapping("/chat")
public ApiResponse chat(
        @RequestParam String q,
        @RequestParam(defaultValue = "default") String sessionId,
        @RequestHeader("X-Tenant-Id") String tenantId) {

    TenantContext.setTenant(tenantId);
    var tools = toolRegistry.getToolsForTenant(tenantId, Set.of("kb", "ticket", "notify"));

    String reply = chatClient.prompt()
            .user(q)
            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
            .tools(tools)
            .call()
            .content();

    TenantContext.clear();
    return ApiResponse.ok(reply);
}
```

---

## Day 8-10：评估系统

### Step 10：评估集 + 评估器

```java
@Service
public class EvalRunner {

    public EvalResult evaluate(String tenantId) {
        var testSet = EvalTestSet.forTenant(tenantId);
        // 对每条测试跑 RAG → 检查 Recall + Faithfulness
        // ...（参考 阶段2-核心能力/04-评估方法论.md 的 RagEvaluator）
    }
}

@RestController
@RequestMapping("/api/eval")
public class EvalController {
    @PostMapping("/run")
    public ApiResponse run(@RequestHeader("X-Tenant-Id") String tenantId) {
        return ApiResponse.ok(evalRunner.evaluate(tenantId));
    }
}
```

---

## Sprint 3 验收

- [ ] 上传 PDF → 自动分块入库
- [ ] 基于文档问答（多租户隔离）
- [ ] AI 能自主调用工具（知识库/工单）
- [ ] 工具错误返回信息（不崩溃）
- [ ] 工具注册表按权限动态加载
- [ ] 评估集 > 30 条，通过率 > 80%

---

## 下一步

→ [Sprint 4：多租户 + 安全 + 审计](企业项目-Sprint4-多租户安全.md)
