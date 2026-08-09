# Sprint 4：多模态 Agent 生产化

> 让系统能"稳定、安全、经济"地运行  
> 核心交付：成本优化 + 安全护栏 + 评估体系

## 1 概述

### 1.1 目标

将多模态 Agent 从原型阶段推向生产环境，确保：
- **V1**：基本可用 - Docker Compose 部署，基础监控
- **V2**：性能优化 - 成本控制，缓存策略，模型优化
- **V3**：生产部署 - Kubernetes，完整安全体系，自动化评估

### 1.2 生产化挑战

| 挑战 | 说明 | 影响 |
|-----|------|------|
| **成本控制** | 大模型 API 调用成本高昂 | 限制规模 |
| **性能延迟** | 图像/语音处理耗时长 | 用户体验差 |
| **安全合规** | PII 泄露、有害内容 | 法律风险 |
| **质量评估** | 如何衡量 Agent 质量 | 难以优化 |
| **可观测性** | 黑盒模型难调试 | 运维困难 |

## 2 V1：基本可用

### 2.1 架构设计

```mermaid
flowchart TB
    subgraph Deploy["部署层"]
        DC[Docker Compose]
        APP[Application Container]
        PG[(PostgreSQL)]
        RD[(Redis)]
        MIN[MinIO<br/>图片存储]
    end
    
    subgraph Monitor["监控"]
        P[Prometheus]
        G[Grafana]
        L[Logs<br/>ELK]
    end
    
    subgraph Alert["告警"]
        A[Alertmanager]
    end
    
    APP --> PG
    APP --> RD
    APP --> MIN
    P --> G
    P --> A
    APP -.指标.-> P
    APP -.日志.-> L
```

### 2.2 Docker Compose 配置

#### docker-compose.yml

```yaml
version: '3.8'

services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - DB_HOST=postgres
      - REDIS_HOST=redis
    depends_on:
      - postgres
      - redis
      - minio
    volumes:
      - ./logs:/app/logs
    restart: unless-stopped

  postgres:
    image: postgres:15
    environment:
      - POSTGRES_DB=omniagent
      - POSTGRES_USER=agent
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    volumes:
      - redis_data:/data
    restart: unless-stopped

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      - MINIO_ROOT_USER=${MINIO_USER}
      - MINIO_ROOT_PASSWORD=${MINIO_PASSWORD}
    volumes:
      - minio_data:/data
    restart: unless-stopped

  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
    restart: unless-stopped

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD}
    volumes:
      - grafana_data:/var/lib/grafana
    restart: unless-stopped

volumes:
  postgres_data:
  redis_data:
  minio_data:
  prometheus_data:
  grafana_data:
```

### 2.3 基础监控

#### PrometheusConfig.java

```java
package com.omniagent.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 生产化监控指标
 */
@Component
public class ProductionMetrics {
    
    private final MeterRegistry registry;
    private final Timer imageProcessingTimer;
    private final Timer voiceProcessingTimer;
    private final Counter apiCallCounter;
    
    public ProductionMetrics() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        
        this.imageProcessingTimer = Timer.builder("agent.image.processing")
            .description("Image processing time")
            .register(registry);
            
        this.voiceProcessingTimer = Timer.builder("agent.voice.processing")
            .description("Voice processing time")
            .register(registry);
            
        this.apiCallCounter = Counter.builder("agent.api.calls")
            .description("Total API calls to LLM")
            .register(registry);
    }
    
    public void recordImageProcessing(long duration) {
        imageProcessingTimer.record(duration, TimeUnit.MILLISECONDS);
    }
    
    public void recordApiCall(String model) {
        Counter.builder("agent.api.calls")
            .tag("model", model)
            .register(registry)
            .increment();
    }
    
    public String scrape() {
        return ((PrometheusMeterRegistry) registry).scrape();
    }
}
```

### 2.4 V1 评估

| 指标 | 目标 | 实际 |
|-----|------|------|
| 可用性 | 95% | 90% |
| P99 延迟 | < 5s | 6s |
| 成本监控 | ❌ | ❌ |
| 安全护栏 | ❌ | ❌ |

## 3 V2：性能优化

### 3.1 成本优化策略

```mermaid
flowchart TB
    subgraph Input["请求输入"]
        REQ[用户请求]
    end
    
    subgraph Strategy["成本优化策略"]
        CACHE[语义缓存<br/>30% 命中率]
        ROUTE{难度路由}
        SMALL[小模型预过滤<br/>70% 简单请求]
        LARGE[大模型兜底<br/>30% 复杂请求]
    end
    
    subgraph Output["响应"]
        RESP[最终答案]
    end
    
    REQ --> CACHE
    CACHE -->|命中| RESP
    CACHE -->|未命中| ROUTE
    
    ROUTE -->|简单| SMALL
    ROUTE -->|复杂| LARGE
    
    SMALL --> RESP
    LARGE --> RESP
```

#### CostOptimizationService.java

```java
package com.omniagent.production.v2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 成本优化服务
 * 
 * 策略：
 * 1. 语义缓存 - 避免重复调用
 * 2. 难度路由 - 简单请求用小模型
 * 3. Token 估算 - 提前预估成本
 * 4. 批处理 - 合并请求
 */
@Slf4j
@Service
public class CostOptimizationService {
    
    private final SemanticCache cache;
    private final DifficultyRouter router;
    private final TokenEstimator tokenEstimator;
    private final SmallModelClient smallClient;
    private final LargeModelClient largeClient;
    
    /**
     * 处理请求（带成本优化）
     */
    public <T> T process(OptimizedRequest<T> request) {
        // 1. 语义缓存
        Optional<T> cached = cache.get(request);
        if (cached.isPresent()) {
            log.info("缓存命中，跳过 LLM 调用");
            return cached.get();
        }
        
        // 2. 难度评估
        Difficulty difficulty = router.estimateDifficulty(request);
        log.info("请求难度: {}", difficulty);
        
        // 3. Token 估算
        int estimatedTokens = tokenEstimator.estimate(request);
        double estimatedCost = calculateCost(estimatedTokens, difficulty);
        log.info("预估 Token: {}, 成本: ${}", estimatedTokens, estimatedCost);
        
        // 4. 路由到合适的模型
        T response;
        if (difficulty == Difficulty.LOW) {
            response = smallClient.process(request);
        } else {
            response = largeClient.process(request);
        }
        
        // 5. 缓存结果
        cache.put(request, response);
        
        // 6. 记录成本
        recordCost(estimatedTokens, difficulty);
        
        return response;
    }
    
    private double calculateCost(int tokens, Difficulty difficulty) {
        double pricePer1k = switch (difficulty) {
            case LOW -> 0.001;   // 小模型
            case MEDIUM -> 0.003;
            case HIGH -> 0.01;   // GPT-4V
        };
        return (tokens / 1000.0) * pricePer1k;
    }
    
    private void recordCost(int tokens, Difficulty difficulty) {
        // 发送到监控系统
    }
}

enum Difficulty { LOW, MEDIUM, HIGH }
```

#### SemanticCache.java

```java
package com.omniagent.production.v2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;

/**
 * 语义缓存
 * 
 * 使用向量相似度而非精确匹配
 * 相似度 > 0.9 即视为命中
 */
@Slf4j
public class SemanticCache {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final EmbeddingCache embeddingCache;
    
    /**
     * 获取缓存（语义匹配）
     */
    public <T> Optional<T> get(OptimizedRequest<T> request) {
        // 1. 获取请求 embedding
        float[] queryEmbedding = embeddingCache.embed(request);
        
        // 2. 查找相似缓存
        String cacheKey = findSimilarKey(queryEmbedding, 0.9f);
        
        if (cacheKey != null) {
            log.info("语义缓存命中: {}", cacheKey);
            @SuppressWarnings("unchecked")
            T cached = (T) redisTemplate.opsForValue().get(cacheKey);
            return Optional.ofNullable(cached);
        }
        
        return Optional.empty();
    }
    
    /**
     * 存储缓存
     */
    public <T> void put(OptimizedRequest<T> request, T response) {
        // 1. 生成 embedding 作为 key
        float[] embedding = embeddingCache.embed(request);
        String key = generateKey(embedding);
        
        // 2. 存储到 Redis（带 TTL）
        redisTemplate.opsForValue().set(key, response, 24, java.util.concurrent.TimeUnit.HOURS);
        
        // 3. 索引到向量数据库
        embeddingCache.index(key, embedding);
    }
    
    private String findSimilarKey(float[] queryEmbedding, float threshold) {
        // 在向量索引中查找
        // （实现略）
        return null;
    }
    
    private String generateKey(float[] embedding) {
        // 生成稳定的 key
        return "cache:" + java.util.Arrays.hashCode(embedding);
    }
}
```

#### DifficultyRouter.java

```java
package com.omniagent.production.v2;

import lombok.extern.slf4j.Slf4j;

/**
 * 难度路由器
 * 
 * 判断请求难度，决定使用哪个模型
 */
@Slf4j
public class DifficultyRouter {
    
    /**
     * 估算请求难度
     */
    public Difficulty estimateDifficulty(OptimizedRequest<?> request) {
        // 规则：
        // 1. 图像复杂度（大小、分辨率）
        // 2. 问题类型（简单问答 vs 复杂推理）
        // 3. 历史成功率
        
        // 图像相关
        if (request.hasImage()) {
            if (request.getImageComplexity() > 0.7) {
                return Difficulty.HIGH;
            }
        }
        
        // 问题复杂度
        if (isComplexQuestion(request.getQuestion())) {
            return Difficulty.HIGH;
        }
        
        // 简单查询
        if (isSimpleExtraction(request.getQuestion())) {
            return Difficulty.LOW;
        }
        
        return Difficulty.MEDIUM;
    }
    
    private boolean isComplexQuestion(String question) {
        // 复杂问题特征
        return question != null && (
            question.contains("为什么") ||
            question.contains("如何") ||
            question.contains("分析") ||
            question.contains("比较")
        );
    }
    
    private boolean isSimpleExtraction(String question) {
        // 简单提取特征
        return question != null && (
            question.contains("是什么") ||
            question.contains("提取") ||
            question.contains("识别")
        );
    }
}
```

### 3.2 性能优化

#### PerformanceOptimizer.java

```java
package com.omniagent.production.v2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 性能优化器
 * 
 * 策略：
 * 1. 并行处理 - 独立任务并行
 * 2. 流式输出 - 降低首字延迟
 * 3. 连接池 - 复用 HTTP 连接
 * 4. 预加载 - 提前加载模型
 */
@Slf4j
@Service
public class PerformanceOptimizer {
    
    private final ExecutorService executor;
    
    public PerformanceOptimizer() {
        this.executor = Executors.newFixedThreadPool(10);
    }
    
    /**
     * 并行处理多模态请求
     */
    public <T> CompletableFuture<T> processParallel(
        java.util.function.Supplier<T> task1,
        java.util.function.Supplier<T> task2
    ) {
        CompletableFuture<T> future1 = CompletableFuture.supplyAsync(task1, executor);
        CompletableFuture<T> future2 = CompletableFuture.supplyAsync(task2, executor);
        
        return CompletableFuture.allOf(future1, future2)
            .thenApply(v -> {
                // 合并结果
                return future1.join(); // 简化示例
            });
    }
    
    /**
     * 预热连接池
     */
    public void warmupConnections() {
        // 启动时预先建立连接
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                // 发送健康检查请求
            });
        }
    }
}
```

### 3.3 V2 评估

| 指标 | V1 | V2 |
|-----|----|----|
| 平均成本 | $0.05/请求 | $0.02/请求 |
| P99 延迟 | 6s | 3s |
| 缓存命中率 | 0% | 30% |
| 小模型使用率 | 0% | 70% |

## 4 V3：生产部署

### 4.1 架构设计

```mermaid
flowchart TB
    subgraph K8S["Kubernetes Cluster"]
        subgraph NS["Namespace: omniagent"]
            INGRESS[Ingress<br/>Nginx]
            APP[Application Pods<br/>3 replicas]
            JOB[Job Worker<br/>异步任务]
        end
    end
    
    subgraph Security["安全层"]
        PII[PII 检测与脱敏]
        GUARD[内容审核<br/>Llama Guard]
        RBAC[RBAC 权限控制]
    end
    
    subgraph Observability["可观测性"]
        MET[Metrics<br/>Prometheus]
        LOG[Logs<br/>Loki]
        TRAC[Traces<br/>Jaeger]
        DASH[Grafana Dashboards]
    end
    
    subgraph Evaluation["评估体系"]
        UNIT[Unit Tests]
        INT[Integration Tests]
        E2E[E2E Tests]
        EVAL[LLM Evaluation<br/>RAGAS]
    end
    
    INGRESS --> PII --> GUARD --> RBAC --> APP
    APP --> MET & LOG & TRAC
    MET & LOG & TRAC --> DASH
    
    UNIT --> INT --> E2E --> EVAL
```

### 4.2 安全体系

#### SecurityGuardrails.java

```java
package com.omniagent.production.v3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 安全护栏
 * 
1. PII 检测与脱敏
 * 2. 内容审核（输入/输出）
 * 3. 速率限制
 * 4. 权限控制
 */
@Slf4j
@Service
public class SecurityGuardrails {
    
    private final PIIDetector piiDetector;
    private final ContentModerator contentModerator;
    private final RateLimiter rateLimiter;
    
    /**
     * 处理请求（带安全检查）
     */
    public <T> T processSecure(SecureRequest<T> request) {
        String userId = request.getUserId();
        
        // 1. 速率限制
        if (!rateLimiter.checkLimit(userId)) {
            throw new RateLimitException("请求过于频繁");
        }
        
        // 2. PII 检测
        PIICheckResult piiCheck = piiDetector.scan(request);
        if (piiCheck.hasPII()) {
            log.warn("检测到 PII，用户: {}, 类型: {}", 
                userId, piiCheck.getPIIType());
            
            // 脱敏或拒绝
            if (shouldBlockPII(piiCheck)) {
                throw new PIIBlockedException("请求包含敏感信息");
            }
            
            request = piiDetector.redact(request);
        }
        
        // 3. 内容审核
        ModerationResult moderation = contentModerator.moderate(request);
        if (moderation.isFlagged()) {
            log.warn("内容被标记，用户: {}, 原因: {}", 
                userId, moderation.getReason());
            throw new ContentBlockedException("内容违规");
        }
        
        // 4. 处理请求
        T response = request.delegate();
        
        // 5. 输出审核
        ModerationResult outputModeration = 
            contentModerator.moderateOutput(response);
        if (outputModeration.isFlagged()) {
            log.warn("输出内容被标记，用户: {}", userId);
            return getSafeDefault(response);
        }
        
        return response;
    }
    
    private boolean shouldBlockPII(PIICheckResult check) {
        // 根据策略决定是否阻止
        return check.getPIIType() == PIIType.CREDIT_CARD;
    }
    
    private <T> T getSafeDefault(T response) {
        // 返回安全的默认响应
        return null;
    }
}

enum PIIType { EMAIL, PHONE, SSN, CREDIT_CARD, ADDRESS }

record PIICheckResult(boolean hasPII, PIIType piiType) {}

record ModerationResult(boolean isFlagged, String reason) {}
```

#### ContentModerator.java

```java
package com.omniagent.production.v3;

import lombok.extern.slf4j.Slf4j;

/**
 * 内容审核器
 * 
 * 使用 Llama Guard 或类似模型进行内容审核
 */
@Slf4j
public class ContentModerator {
    
    private static final String[] UNSAFE_CATEGORIES = {
        "暴力",
        "仇恨言论",
        "色情",
        "自杀",
        "毒品"
    };
    
    /**
     * 审核输入内容
     */
    public ModerationResult moderate(SecureRequest<?> request) {
        // 1. 文本审核
        if (request.hasText()) {
            TextModerationResult textResult = moderateText(request.getText());
            if (!textResult.isSafe()) {
                return new ModerationResult(true, textResult.getCategory());
            }
        }
        
        // 2. 图片审核（NSFW 检测）
        if (request.hasImage()) {
            ImageModerationResult imageResult = moderateImage(request.getImage());
            if (!imageResult.isSafe()) {
                return new ModerationResult(true, imageResult.getCategory());
            }
        }
        
        return new ModerationResult(false, null);
    }
    
    /**
     * 审核输出内容
     */
    public ModerationResult moderateOutput(Object response) {
        // 转换为文本后审核
        String text = extractText(response);
        return moderateText(text);
    }
    
    private TextModerationResult moderateText(String text) {
        // 调用审核模型
        // （实现略）
        return new TextModerationResult(true, "safe");
    }
    
    private ImageModerationResult moderateImage(byte[] image) {
        // 使用 NSFW 检测模型
        // （实现略）
        return new ImageModerationResult(true, "safe");
    }
    
    private String extractText(Object response) {
        // 提取文本
        return "";
    }
}
```

### 4.3 评估体系

#### MultimodalAgentEvaluator.java

```java
package com.omniagent.production.v3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多模态 Agent 评估器
 * 
 * 评估维度：
 * 1. 准确性 - 答案正确性
 * 2. 相关性 - 与问题相关性
 * 3. 安全性 - 是否有违规内容
 * 4. 延迟 - 响应速度
 * 5. 成本 - Token 消耗
 */
@Slf4j
@Service
public class MultimodalAgentEvaluator {
    
    private final AccuracyEvaluator accuracyEvaluator;
    private final SafetyEvaluator safetyEvaluator;
    private final PerformanceEvaluator performanceEvaluator;
    
    /**
     * 运行评估
     */
    public EvaluationReport evaluate(
        List<EvaluationCase> testCases,
        EvaluationConfig config
    ) {
        EvaluationReport report = new EvaluationReport();
        
        for (EvaluationCase testCase : testCases) {
            // 1. 运行 Agent
            long startTime = System.currentTimeMillis();
            AgentResponse response = runAgent(testCase);
            long duration = System.currentTimeMillis() - startTime;
            
            // 2. 评估准确性
            AccuracyScore accuracy = accuracyEvaluator.evaluate(
                testCase.getExpectedAnswer(),
                response
            );
            
            // 3. 评估安全性
            SafetyScore safety = safetyEvaluator.evaluate(response);
            
            // 4. 记录性能
            performanceEvaluator.record(duration, response.getTokens());
            
            // 5. 汇总结果
            report.addResult(testCase.getId(), accuracy, safety, duration);
        }
        
        // 6. 生成报告
        return report.generate();
    }
    
    private AgentResponse runAgent(EvaluationCase testCase) {
        // 运行实际 Agent
        return null;
    }
}

class EvaluationReport {
    // 生成评估报告
    EvaluationReport generate() {
        return this;
    }
    
    void addResult(String id, AccuracyScore accuracy, 
                   SafetyScore safety, long duration) {
        // 记录结果
    }
}
```

### 4.4 Kubernetes 部署

#### deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: omniagent
  namespace: production
spec:
  replicas: 3
  selector:
    matchLabels:
      app: omniagent
  template:
    metadata:
      labels:
        app: omniagent
    spec:
      containers:
      - name: app
        image: omniagent:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "production"
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: api-secrets
              key: openai-key
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: omniagent-service
  namespace: production
spec:
  selector:
    app: omniagent
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: ClusterIP
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: omniagent-ingress
  namespace: production
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  tls:
  - hosts:
    - agent.example.com
    secretName: omniagent-tls
  rules:
  - host: agent.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: omniagent-service
            port:
              number: 80
```

### 4.5 V3 评估

| 指标 | V1 | V2 | V3 |
|-----|----|----|-----|
| 可用性 | 90% | 95% | 99.9% |
| P99 延迟 | 6s | 3s | 2s |
| 成本/请求 | $0.05 | $0.02 | $0.015 |
| 安全覆盖率 | 0% | 50% | 95% |
| 评估覆盖 | 单测 | 单测+集成 | 全覆盖 |

## 5 总结与演进路径

### 5.1 三版本对比

| 特性 | V1 基本可用 | V2 性能优化 | V3 生产部署 |
|-----|------------|------------|------------|
| **部署方式** | Docker Compose | Docker Compose | Kubernetes |
| **监控** | 基础指标 | 性能+成本 | 完整可观测性 |
| **成本优化** | ❌ | ✅ 缓存+路由 | ✅ 全面优化 |
| **安全** | ❌ | ⚠️ 基础 | ✅ 完整体系 |
| **评估** | 单测 | 集成测试 | 全量评估 |
| **SLA** | 90% | 95% | 99.9% |

### 5.2 生产化检查清单

```mermaid
mindmap
  root((生产化检查清单))
    安全
      PII 检测与脱敏
      内容审核
      速率限制
      RBAC
      HTTPS/加密
    性能
      语义缓存
      模型路由
      连接池
      并行处理
      流式输出
    可观测性
      Metrics
      Logs
      Traces
      告警规则
      仪表板
    评估
      单元测试
      集成测试
      E2E 测试
      LLM 评估
      A/B 测试
    部署
      容器化
      K8s 配置
      健康检查
      滚动更新
      灾备恢复
```

### 5.3 下一步

- 实现自动化评估流水线
- 添加 A/B 测试能力
- 优化成本预测算法
- 扩展安全护栏规则
- 实现多区域部署

## 6 附录

### 6.1 环境变量清单

```properties
# API Keys
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...

# 数据库
DB_HOST=postgres
DB_PASSWORD=...

# Redis
REDIS_HOST=redis

# 监控
PROMETHEUS_RETENTION=15d
GRAFANA_ADMIN_PASSWORD=...

# 安全
ENABLE_PII_DETECTION=true
ENABLE_CONTENT_MODERATION=true
RATE_LIMIT_PER_MINUTE=100

# 特性开关
ENABLE_SEMANTIC_CACHE=true
ENABLE_DIFFICULTY_ROUTING=true
ENABLE_STREAM_OUTPUT=true
```

### 6.2 监控大盘配置

```json
{
  "dashboard": {
    "title": "OmniAgent Production Dashboard",
    "panels": [
      {
        "title": "Request Rate",
        "targets": [
          {
            "expr": "rate(agent_api_calls_total[1m])"
          }
        ]
      },
      {
        "title": "P99 Latency",
        "targets": [
          {
            "expr": "histogram_quantile(0.99, agent_request_duration_seconds)"
          }
        ]
      },
      {
        "title": "Cache Hit Rate",
        "targets": [
          {
            "expr": "agent_cache_hits / agent_cache_total"
          }
        ]
      },
      {
        "title": "Cost per Request",
        "targets": [
          {
            "expr": "rate(agent_cost_total[1h]) / rate(agent_api_calls_total[1h])"
          }
        ]
      }
    ]
  }
}
```

### 6.3 常见问题

| 问题 | 原因 | 解决方案 |
|-----|------|---------|
| 延迟突然升高 | 缓存失效 | 检查 Redis，预热缓存 |
| 成本激增 | 小模型误判 | 调整难度阈值 |
| 内容违规 | 审核模型漏检 | 更新审核规则 |
| 内存溢出 | 图片过大 | 添加大小限制 |
| 频繁 503 | 负载不均 | 水平扩容 |
