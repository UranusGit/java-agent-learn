# Sprint 7 详细实现：部署 + 管理后台 + 文档

> 目标：Docker 一键部署，管理后台看数据，完整文档，简历级项目
> 时间：1 周 · 前置：Sprint 6 完成

---

## Day 1-2：Docker 化

### Step 1：Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Step 2：Docker Compose

```yaml
version: '3.8'

services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - LLM_API_KEY=${DEEPSEEK_API_KEY}
      - LLM_BASE_URL=https://api.deepseek.com
      - LLM_MODEL=deepseek-chat
      - REDIS_HOST=redis
      - DB_HOST=postgres
      - DB_USER=postgres
      - DB_PASS=postgres
    depends_on:
      - postgres
      - redis

  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: agentforge
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  pgdata:
```

### Step 3：一键启动

```bash
docker compose up -d
# 访问 http://localhost:8080/
```

---

## Day 3-5：管理后台

### Step 4：管理后台页面

创建 `src/main/resources/static/admin.html`：

- 文档管理：上传/删除/查看文档列表
- 会话查看：查看历史对话
- 成本看板：Token 消耗 + 费用趋势
- 评估面板：跑评估集 + 查看结果
- 审计日志：查看操作记录

### Step 5：管理后台 API

```java
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestHeader("X-Tenant-Id") String tenantId) {
        return Map.of(
            "todayChats", chatMapper.countToday(tenantId),
            "totalDocuments", docMapper.countByTenant(tenantId),
            "totalTickets", ticketMapper.countByTenant(tenantId),
            "tokenUsage", billing.getDailyReport(tenantId, LocalDate.now()),
            "cacheHitRate", semanticCache.getHitRate(tenantId),
            "avgLatency", metrics.getAvgLatency(tenantId)
        );
    }

    @GetMapping("/audit")
    public List<AuditLog> audit(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestParam(defaultValue = "7") int days) {
        return auditLogger.query(tenantId, LocalDate.now().minusDays(days), LocalDate.now());
    }
}
```

---

## Day 6-7：文档 + CI/CD

### Step 6：README

创建 `README.md`：

```markdown
# AgentForge - AI 智能客服平台

企业级多租户 AI 客服系统，支持知识库问答、工单处理、多 Agent 协作。

## 快速启动
docker compose up -d

## 技术栈
Spring Boot 3 + Spring AI 2.0 + PgVector + Redis

## 架构
（链接到 architecture.md）

## API 文档
（链接到 api-spec.md）
```

### Step 7：CI/CD Pipeline

```yaml
# .github/workflows/ci.yml
name: CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres: ...
      redis: ...
    steps:
      - uses: actions/checkout@v4
      - name: Unit Tests
        run: mvn test
      - name: Integration Tests
        run: mvn verify
      - name: Eval Gate
        run: |
          # 启动应用，跑评估
          # 通过率 < 80% 则失败
```

---

## Sprint 7 验收

- [ ] `docker compose up` 一键启动
- [ ] 管理后台能查看数据
- [ ] README 完整
- [ ] CI/CD pipeline 有 eval 门禁
- [ ] 有架构文档 + ADR

---

## 🎓 项目毕业

完成 Sprint 7 后，你拥有了一个**完整的企业级 AI 客服平台**。

**简历版描述**：
> 设计并开发了基于 Spring AI 的多租户 AI 客服平台 AgentForge。
> - 多 Agent 协作架构（路由/技术/工单/评审），日均处理 N 条咨询
> - RAG 知识库问答，Recall@5 达 83%，Faithfulness 达 76%
> - 四层成本优化（缓存/路由/裁剪/语义缓存），降低 N% API 成本
> - 全链路可观测 + 审计日志，满足合规要求

→ 继续进阶：[项目二 AI 代码生成平台](../企业项目-代码生成平台/)
→ 回顾：[能力地图](../01-能力地图.md)
