# AgentOps Sprint 3 · 配置中心与灰度发布（从最简版开始）

> **目标**：从"改 Prompt 要重新编译"开始，一步步长成配置中心 + 灰度发布
> **前置**：Sprint 1 历史持久化、Sprint 2 可视化

---

## V1：30 分钟——YAML 热刷新

> **思路**：先不建数据库表、不做灰度。最简单的"热更新"就是用 Spring 的 @RefreshScope + 一个 YAML 文件。

### Step 1：配置文件

```yaml
# agent-config.yml —— 可以改这个文件后刷新
agent:
  system-prompt: "你是一个友好的助手。"
  model: deepseek-chat
  temperature: 0.7
  max-tokens: 2000
```

### Step 2：配置 Bean

```java
package com.agentops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * V1 极简版：YAML 配置 + @RefreshScope
 *
 * 问题：只能全局生效、不能按租户定制、没有版本管理
 * 但它比"改代码→编译→部署"好太多了——改 YAML 刷新就生效。
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent")
public class AgentConfigProperties {

    private String systemPrompt = "你是一个友好的助手。";
    private String model = "deepseek-chat";
    private double temperature = 0.7;
    private int maxTokens = 2000;

    // getters/setters ...
}
```

### Step 3：手动触发刷新

```java
@RestController
public class RefreshController {

    private final ContextRefresher refresher;

    @PostMapping("/admin/refresh")
    public Map<String, Object> refresh() {
        Set<String> keys = refresher.refresh();
        return Map.of("refreshed", keys);
    }
}
```

```bash
# 改 YAML
vim agent-config.yml
# 把 system-prompt 改成 "你是一个专业的技术顾问。"

# 刷新
curl -X POST http://localhost:8080/admin/refresh
# {"refreshed":["agent.system-prompt"]}

# 下一个请求就用新 Prompt 了
curl -X POST http://localhost:8080/api/chat -d '{"message":"你好"}'
```

> ✅ V1 的价值：改 YAML 不用重启，热刷新生效。
>
> ❌ V1 的问题：所有人共用一套配置、没有历史记录、改错了没法回滚。

---

## V2：2 天——数据库配置中心 + 版本管理

> **V1 的问题**：YAML 是全局的，不能按租户定制；改了没历史，没法回滚。
> **V2 的目标**：配置存数据库，有版本历史，能回滚。

### Step 2.1：配置表

```sql
CREATE TABLE agent_configs (
    id              BIGSERIAL PRIMARY KEY,
    config_key      VARCHAR(128) NOT NULL,   -- e.g., "system_prompt"
    config_value    TEXT NOT NULL,
    tenant_id       VARCHAR(64),             -- null = 全局默认
    version         INT NOT NULL DEFAULT 1,
    author          VARCHAR(64),
    change_log      TEXT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(config_key, tenant_id, version)
);
```

### Step 2.2：配置服务

```java
package com.agentops.config;

import org.springframework.stereotype.Service;
import java.util.*;

/**
 * V2：数据库配置中心
 *
 * V1 是全局 YAML，V2 支持按租户定制 + 版本历史 + 回滚。
 */
@Service
public class ConfigCenter {

    private final JdbcTemplate jdbc;

    /**
     * 获取配置（租户级覆盖全局）
     * V1 只有一套配置，V2 是 global → tenant 两级覆盖
     */
    public String get(String key, String tenantId) {
        // 先查租户级
        var tenantConfig = queryLatest(key, tenantId);
        if (tenantConfig != null) return tenantConfig;

        // 回退到全局
        return queryLatest(key, null);
    }

    /**
     * 更新配置（新版本）
     */
    public int update(String key, String value, String tenantId,
                      String author, String changeLog) {
        int nextVersion = getNextVersion(key, tenantId);
        jdbc.update("""
            INSERT INTO agent_configs (config_key, config_value, tenant_id, version, author, change_log)
            VALUES (?, ?, ?, ?, ?, ?)
            """, key, value, tenantId, nextVersion, author, changeLog);
        return nextVersion;
    }

    /**
     * V2 新增：回滚到指定版本
     */
    public void rollback(String key, String tenantId, int targetVersion) {
        var target = jdbc.queryForMap("""
            SELECT config_value FROM agent_configs
            WHERE config_key = ? AND tenant_id IS NOT DISTINCT FROM ? AND version = ?
            """, key, tenantId, targetVersion);

        update(key, target.get("config_value").toString(), tenantId,
            "system", "Rollback to v" + targetVersion);
    }

    /**
     * V2 新增：版本历史
     */
    public List<Map<String, Object>> history(String key, String tenantId) {
        return jdbc.queryForList("""
            SELECT version, config_value, author, change_log, created_at
            FROM agent_configs
            WHERE config_key = ? AND tenant_id IS NOT DISTINCT FROM ?
            ORDER BY version DESC
            """, key, tenantId);
    }

    private String queryLatest(String key, String tenantId) {
        var list = jdbc.queryForList("""
            SELECT config_value FROM agent_configs
            WHERE config_key = ? AND tenant_id IS NOT DISTINCT FROM ?
            ORDER BY version DESC LIMIT 1
            """, key, tenantId);
        return list.isEmpty() ? null : list.get(0).get("config_value").toString();
    }

    private int getNextVersion(String key, String tenantId) {
        Integer max = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) FROM agent_configs
            WHERE config_key = ? AND tenant_id IS NOT DISTINCT FROM ?
            """, Integer.class, key, tenantId);
        return max + 1;
    }
}
```

### Step 2.3：配置管理 API

```java
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigCenter configCenter;

    @GetMapping("/{key}")
    public String get(@PathVariable String key,
                      @RequestParam(required = false) String tenantId) {
        return configCenter.get(key, tenantId);
    }

    @PostMapping("/{key}")
    public Map<String, Object> update(@PathVariable String key,
                                       @RequestBody UpdateConfigRequest req) {
        int version = configCenter.update(key, req.value(),
            req.tenantId(), req.author(), req.changeLog());
        return Map.of("key", key, "newVersion", version);
    }

    @GetMapping("/{key}/history")
    public List<Map<String, Object>> history(@PathVariable String key,
            @RequestParam(required = false) String tenantId) {
        return configCenter.history(key, tenantId);
    }

    @PostMapping("/{key}/rollback/{version}")
    public String rollback(@PathVariable String key,
                           @RequestParam(required = false) String tenantId,
                           @PathVariable int version) {
        configCenter.rollback(key, tenantId, version);
        return "已回滚到 v" + version;
    }
}
```

```bash
# 更新全局 Prompt
curl -X POST http://localhost:8080/api/config/system_prompt \
  -d '{"value":"你是专业顾问","author":"alice","changeLog":"调整语气"}'
# {"key":"system_prompt","newVersion":2}

# 为租户 A 定制
curl -X POST http://localhost:8080/api/config/system_prompt \
  -d '{"value":"你是租户A的专属助手","tenantId":"tenant-a","author":"alice","changeLog":"定制"}'

# 回滚
curl -X POST http://localhost:8080/api/config/system_prompt/rollback/1
```

> ✅ V2 的价值：按租户定制、版本历史、回滚。
>
> ❌ V2 的问题：更新配置后所有用户立刻受影响——没有灰度过程，改错了全量翻车。

---

## V3：2 天——灰度发布 + 自动健康评估

> **V2 的问题**：配置更新立刻全量生效，没有灰度过程。
> **V3 的目标**：5% 流量先用新配置，自动评估效果，好则扩大、差则回滚。

### Step 3.1：灰度发布管理器

```java
package com.agentops.config;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3：灰度发布
 *
 * V2 更新即全量生效，V3 支持：
 * 1. 按百分比逐步放量（5% → 25% → 50% → 100%）
 * 2. 自动评估新版本效果（成功率/满意度）
 * 3. 效果差自动回滚
 */
@Component
public class CanaryReleaseManager {

    private final ConfigCenter configCenter;
    private final Map<String, CanaryExperiment> experiments = new ConcurrentHashMap<>();

    /**
     * 创建灰度实验
     */
    public CanaryExperiment create(String key, String newValue,
                                    String tenantId, double initialPercent) {
        CanaryExperiment exp = new CanaryExperiment(
            UUID.randomUUID().toString(),
            key, tenantId,
            configCenter.get(key, tenantId),  // 旧值
            newValue,                          // 新值
            initialPercent,
            CanaryStatus.RUNNING,
            Instant.now()
        );
        experiments.put(exp.id(), exp);
        return exp;
    }

    /**
     * 决定一个请求用旧值还是新值
     */
    public CanaryDecision decide(String key, String tenantId, String sessionId) {
        CanaryExperiment exp = findRunning(key, tenantId);
        if (exp == null) {
            return CanaryDecision.useOld(null); // 无实验，用旧值
        }

        // 基于 sessionId 哈希分流（同一用户始终在同一组）
        int hash = Math.abs(sessionId.hashCode()) % 100;
        if (hash < exp.trafficPercent()) {
            return CanaryDecision.useNew(exp);
        } else {
            return CanaryDecision.useOld(exp);
        }
    }

    /**
     * 扩大灰度
     */
    public void promote(String experimentId, double newPercent) {
        CanaryExperiment exp = experiments.get(experimentId);
        experiments.put(experimentId, exp.withPercent(newPercent));

        if (newPercent >= 100) {
            // 全量发布——把新值写入 ConfigCenter
            configCenter.update(exp.key(), exp.newValue(), exp.tenantId(),
                "canary", "Canary full rollout");
            experiments.remove(experimentId);
        }
    }

    /**
     * 自动健康评估
     */
    @Scheduled(cron = "0 */5 * * * *") // 每 5 分钟
    public void autoAssess() {
        for (var exp : experiments.values()) {
            if (exp.status() != CanaryStatus.RUNNING) continue;

            double oldSuccessRate = measureSuccessRate(exp.key(), exp.tenantId(), false);
            double newSuccessRate = measureSuccessRate(exp.key(), exp.tenantId(), true);

            // 新版本成功率比旧版本差 10% 以上 → 自动回滚
            if (newSuccessRate < oldSuccessRate - 0.1) {
                System.out.println("⚠️ 灰度实验 " + exp.id()
                    + " 自动回滚：新版本成功率 " + newSuccessRate
                    + " < 旧版本 " + oldSuccessRate);
                experiments.remove(exp.id());
            }
        }
    }

    private double measureSuccessRate(String key, String tenantId, boolean isCanary) {
        // 从 Sprint 1 的历史记录中统计成功率
        // ...
        return 0.95; // 模拟值
    }

    private CanaryExperiment findRunning(String key, String tenantId) {
        return experiments.values().stream()
            .filter(e -> e.key().equals(key)
                && Objects.equals(e.tenantId(), tenantId)
                && e.status() == CanaryStatus.RUNNING)
            .findFirst().orElse(null);
    }

    public record CanaryExperiment(
        String id, String key, String tenantId,
        String oldValue, String newValue,
        double trafficPercent, CanaryStatus status, Instant startedAt
    ) {
        CanaryExperiment withPercent(double p) {
            return new CanaryExperiment(id, key, tenantId, oldValue, newValue,
                p, status, startedAt);
        }
    }

    public record CanaryDecision(boolean useNew, CanaryExperiment experiment) {
        static CanaryDecision useNew(CanaryExperiment e) { return new CanaryDecision(true, e); }
        static CanaryDecision useOld(CanaryExperiment e) { return new CanaryDecision(false, e); }
    }

    public enum CanaryStatus { RUNNING, PROMOTED, ROLLED_BACK }
}
```

### Step 3.2：灰度 Controller

```java
@RestController
@RequestMapping("/api/canary")
public class CanaryController {

    private final CanaryReleaseManager canary;

    @PostMapping("/create")
    public CanaryExperiment create(@RequestBody CreateCanaryRequest req) {
        return canary.create(req.key(), req.newValue(),
            req.tenantId(), req.initialPercent());
    }

    @PostMapping("/{experimentId}/promote")
    public void promote(@PathVariable String experimentId,
                        @RequestParam double percent) {
        canary.promote(experimentId, percent);
    }

    @GetMapping("/active")
    public Collection<CanaryExperiment> active() {
        return canary.getActiveExperiments();
    }
}
```

### Step 3.3：灰度流程示例

```bash
# 1. 创建灰度：5% 流量用新 Prompt
curl -X POST http://localhost:8080/api/canary/create \
  -d '{"key":"system_prompt","newValue":"你是专业顾问","initialPercent":5}'

# 2. 观察 5 分钟，检查自动评估结果

# 3. 效果好 → 扩大到 25%
curl -X POST http://localhost:8080/api/canary/{id}/promote?percent=25

# 4. 继续扩大
curl -X POST http://localhost:8080/api/canary/{id}/promote?percent=50
curl -X POST http://localhost:8080/api/canary/{id}/promote?percent=100
# percent=100 时自动写入 ConfigCenter，完成全量发布
```

> ✅ V3 的价值：渐进灰度、自动健康评估、自动回滚。

---

## V1 → V2 → V3 演进总结

| 维度 | V1 YAML | V2 数据库 | V3 灰度 |
|------|---------|---------|--------|
| **配置存储** | YAML 文件 | 数据库 | 数据库 |
| **租户定制** | 不支持 | 两级覆盖 | 两级覆盖 |
| **版本管理** | 无 | 完整历史 | 完整历史 |
| **回滚** | 改回 YAML | 一键回滚 | 自动回滚 |
| **灰度** | 无 | 无 | 5%→25%→50%→100% |
| **健康评估** | 无 | 无 | 自动评估 |

---

## 验收检查

- [ ] V1：改 YAML 不用重启
- [ ] V2：数据库配置有版本历史、能回滚、能按租户定制
- [ ] V3：灰度发布能逐步放量、自动评估和回滚

---

## 下一步

→ [Sprint 4：平台化](Sprint4-平台化.md)
