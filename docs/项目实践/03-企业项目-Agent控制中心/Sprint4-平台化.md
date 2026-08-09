# AgentOps Sprint 4 · 生命周期 + 平台化（从最简版开始）

> **目标**：从"一个 Map 记录所有 Agent"开始，一步步长成完整平台管理
> **前置**：Sprint 1-3 全部完成

---

## V1：30 分钟——内存注册表

> **思路**：先不搞状态机、不搞生命周期。最简单的"管理多个 Agent"就是一个 Map。

### Step 1：最简注册表

```java
package com.agentops.platform;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * V1 极简版：内存注册表
 *
 * 问题：重启丢失、不能远程管理、没有状态追踪
 * 但它解决了最核心的问题：知道系统里有哪些 Agent。
 */
@Component
public class SimpleAgentRegistry {

    private final Map<String, AgentInfo> agents = new ConcurrentHashMap<>();

    public void register(String type, String description) {
        agents.put(type, new AgentInfo(type, description, "READY",
            Instant.now()));
    }

    public Collection<AgentInfo> list() {
        return agents.values();
    }

    public AgentInfo get(String type) {
        return agents.get(type);
    }

    public record AgentInfo(
        String type, String description,
        String status, Instant registeredAt
    ) {}
}
```

### Step 2：在启动时注册

```java
@PostConstruct
public void init() {
    registry.register("general", "通用对话 Agent");
    registry.register("code-review", "代码审查 Agent");
    registry.register("ops", "运维 Agent");
}
```

### Step 3：查看

```java
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    @GetMapping
    public Collection<SimpleAgentRegistry.AgentInfo> list() {
        return registry.list();
    }
}
```

```bash
curl http://localhost:8080/api/agents
# [
#   {"type":"general","status":"READY","description":"通用对话 Agent"},
#   {"type":"code-review","status":"READY","description":"代码审查 Agent"}
# ]
```

> ✅ V1 的价值：知道系统里有什么 Agent。
>
> ❌ V1 的问题：重启丢失、没有状态流转、不能远程控制。

---

## V2：2 天——状态机 + 远程控制

> **V1 的问题**：Agent 状态是静态字符串，没有流转规则；不能远程暂停/恢复。
> **V2 的目标**：状态机约束状态流转 + API 控制生命周期。

### Step 2.1：状态机

```java
package com.agentops.platform;

import java.util.*;

/**
 * V2：Agent 状态机
 *
 * V1 状态是任意字符串，V2 用枚举 + 合法流转规则约束。
 */
public enum AgentState {
    CREATED,
    INITIALIZING,
    READY,
    EXECUTING,
    WAITING_TOOL,
    COOLING_DOWN,
    ERROR,
    TERMINATED;

    private static final Map<AgentState, Set<AgentState>> TRANSITIONS = Map.of(
        CREATED, Set.of(INITIALIZING),
        INITIALIZING, Set.of(READY, ERROR),
        READY, Set.of(EXECUTING, TERMINATED),
        EXECUTING, Set.of(WAITING_TOOL, READY, ERROR),
        WAITING_TOOL, Set.of(EXECUTING, READY, ERROR),
        COOLING_DOWN, Set.of(READY),
        ERROR, Set.of(INITIALIZING, TERMINATED)
    );

    public boolean canTransitionTo(AgentState target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

### Step 2.2：生命周期管理器

```java
package com.agentops.platform;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * V2：带状态机的 Agent 管理
 */
@Component
public class AgentLifecycleManager {

    private final Map<String, AgentInstance> instances = new ConcurrentHashMap<>();

    /**
     * 注册新 Agent
     */
    public AgentInstance create(String agentType, String sessionId) {
        String instanceId = agentType + "-" + UUID.randomUUID().toString().substring(0, 8);
        AgentInstance agent = new AgentInstance(
            instanceId, agentType, sessionId,
            AgentState.CREATED, Instant.now(), null
        );
        instances.put(instanceId, agent);
        return agent;
    }

    /**
     * 状态流转（带合法性检查）
     */
    public AgentInstance transition(String instanceId, AgentState target) {
        AgentInstance agent = instances.get(instanceId);
        if (agent == null) throw new IllegalArgumentException("Agent 不存在");

        if (!agent.state().canTransitionTo(target)) {
            throw new IllegalStateException(
                "非法状态转换：" + agent.state() + " → " + target);
        }

        AgentInstance updated = new AgentInstance(
            agent.instanceId(), agent.agentType(), agent.sessionId(),
            target, agent.createdAt(), Instant.now()
        );
        instances.put(instanceId, updated);
        return updated;
    }

    /**
     * 暂停
     */
    public AgentInstance pause(String instanceId) {
        AgentInstance agent = instances.get(instanceId);
        // 从 READY → COOLING_DOWN
        return transition(instanceId, AgentState.COOLING_DOWN);
    }

    /**
     * 恢复
     */
    public AgentInstance resume(String instanceId) {
        // COOLING_DOWN → READY
        return transition(instanceId, AgentState.READY);
    }

    /**
     * 终止
     */
    public AgentInstance terminate(String instanceId) {
        AgentInstance agent = instances.get(instanceId);
        // 任何状态 → TERMINATED（合法的都允许终止）
        instances.remove(instanceId);
        return agent;
    }

    public Collection<AgentInstance> listActive() {
        return instances.values().stream()
            .filter(a -> a.state() != AgentState.TERMINATED)
            .toList();
    }

    public record AgentInstance(
        String instanceId, String agentType, String sessionId,
        AgentState state, Instant createdAt, Instant updatedAt
    ) {}
}
```

### Step 2.3：管理 API

```java
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentLifecycleManager lifecycle;

    @GetMapping("/active")
    public Collection<AgentInstance> active() {
        return lifecycle.listActive();
    }

    @PostMapping("/{instanceId}/pause")
    public AgentInstance pause(@PathVariable String instanceId) {
        return lifecycle.pause(instanceId);
    }

    @PostMapping("/{instanceId}/resume")
    public AgentInstance resume(@PathVariable String instanceId) {
        return lifecycle.resume(instanceId);
    }

    @DeleteMapping("/{instanceId}")
    public void terminate(@PathVariable String instanceId) {
        lifecycle.terminate(instanceId);
    }
}
```

> ✅ V2 的价值：状态机约束、远程暂停/恢复/终止。
>
> ❌ V2 的问题：重启后实例全部丢失、没有健康检查、没有事件通知。

---

## V3：2 天——健康检查 + 事件驱动 + 部署

> **V2 的问题**：Agent 挂了不知道、重启全丢。
> **V3 的目标**：定时健康检查 + 事件总线 + Docker 部署。

### Step 3.1：健康检查（Spring Actuator）

```java
package com.agentops.platform;

import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

/**
 * V3 新增：Agent 健康检查
 *
 * 集成 Spring Boot Actuator，K8s 探针可读取。
 */
@Component
public class AgentHealthIndicator implements HealthIndicator {

    private final AgentLifecycleManager lifecycle;

    @Override
    public Health health() {
        var active = lifecycle.listActive();
        long errorCount = active.stream()
            .filter(a -> a.state() == AgentState.ERROR).count();

        if (errorCount > active.size() / 2) {
            return Health.down()
                .withDetail("activeAgents", active.size())
                .withDetail("errorAgents", errorCount)
                .build();
        }

        return Health.up()
            .withDetail("activeAgents", active.size())
            .withDetail("errorAgents", errorCount)
            .build();
    }
}
```

### Step 3.2：事件总线

```java
package com.agentops.platform;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * V3 新增：事件驱动
 *
 * Agent 状态变化时发布事件，其他模块可以监听。
 */
@Component
public class AgentEventBus {

    private final ApplicationEventPublisher publisher;

    public void publish(AgentEvent event) {
        publisher.publishEvent(event);
    }

    public record AgentEvent(
        String instanceId, String agentType,
        AgentState from, AgentState to, Instant timestamp
    ) {}
}

@Component
class AgentEventListener {

    @EventListener
    public void onAgentEvent(AgentEventBus.AgentEvent event) {
        System.out.println("[EVENT] Agent " + event.instanceId()
            + " " + event.from() + " → " + event.to());

        // ERROR → 发告警
        if (event.to() == AgentState.ERROR) {
            alertService.send("Agent " + event.agentType() + " 进入错误状态");
        }
    }
}
```

### Step 3.3：Docker Compose

```yaml
version: '3.8'
services:
  app:
    build: .
    ports: ["8082:8080"]
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/agentops
      - SPRING_DATA_REDIS_HOST=redis
    depends_on: [postgres, redis]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3

  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: agentops
      POSTGRES_USER: agentops
      POSTGRES_PASSWORD: agentops
    ports: ["5434:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]

  redis:
    image: redis:7-alpine
    ports: ["6380:6379"]

volumes:
  pgdata:
```

> ✅ V3 的价值：健康检查、事件驱动、Docker 部署。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 注册表 | V2 状态机 | V3 平台 |
|------|----------|---------|--------|
| **状态管理** | 静态字符串 | 枚举+合法流转 | + 健康检查 |
| **远程控制** | 无 | 暂停/恢复/终止 | + 告警 |
| **事件通知** | 无 | 无 | 事件总线 |
| **部署** | 无 | 无 | Docker Compose |

---

## 项目总结 & 简历描述

```
Agent 控制中心（AgentOps）

采用 V1→V2→V3 演进式开发，构建企业级 Agent 管控平台：
- 三层历史持久化（sessions→LLM calls→tool calls），全链路 Trace 追溯
- 工具调用可视化（时间线 + Mermaid 序列图 + 统计面板）
- 配置中心（按租户定制 + 版本管理 + 一键回滚 + 灰度发布）
- Agent 生命周期管理（状态机 + 健康检查 + 事件驱动）
```

---

## 验收检查

- [ ] V1：注册表能列出所有 Agent
- [ ] V2：状态机能约束流转、API 能控制暂停/恢复
- [ ] V3：健康检查、事件驱动、Docker 部署

→ 返回 [项目实践总览](../00-项目实践总览.md)
