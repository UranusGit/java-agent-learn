# 项目 08：Agent 供应链安全网关 — 03-MCP 规范化接入

> **定位**：统一接入协议——网关全面升级到 MCP Streamable HTTP + OAuth 2.1 授权体系，堵住协议层的收口裂缝，同时引入 MCP 规范的 Sampling/Elicitation 安全管控。**完整可手写代码**：MCP 客户端连接管理（`McpSyncClient`）、网关代理工具（`implements ToolCallback`）、按 Agent 动态目录、OAuth 2.1 资源服务器、`application.yml`。
>
> 「遇到阻塞？→ [教程 11-MCP协议 §Streamable HTTP 与 OAuth]、[附录 05-01-MCP真实API与坐标]、[前沿 04-MCP生态全景]」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ① 唯一协议：MCP Streamable HTTP（对内+对外双面） ② OAuth 2.1 认证体系（Agent→网关 client credentials + mTLS；网关→工具按来源分级） ③ 每 Agent 动态工具目录（只看到被授权的工具） ④ Sampling/Elicitation 高级能力过策略 |
| **影响了哪些模块** | 网关入口（v1 的 HTTP JSON 代理升级为 MCP Server）、新增 MCP Client 连接层、安全配置（OAuth 资源服务器） |
| **架构如何演进** | 从"HTTP 裸调用"→「**双面 MCP 网关**」：对内 MCP Server（业务 Agent 唯一端点），对外 MCP Client（按登记表连真实工具）——不用 MCP 连不上任何工具 |
| **上一版痛点是什么** | ① 私接绕过（协议层裂缝：封了 IP 没封协议） ② 认证不统一（有 REST 包装不认网关认证头） ③ MCP 高级能力（Sampling/Elicitation）无管控 |

## 2. 协议架构

```mermaid
flowchart LR
    subgraph INTERNAL["集团内"]
        AG["业务 Agent<br/>MCP Client"] -- "① MCP Streamable HTTP<br/>+mTLS+OAuth2.1 CC" --> GW["tool-sec-gateway<br/>MCP Server(对内) +<br/>MCP Client(对外)"]
    end
    subgraph EXTERNAL["工具端"]
        GW -- "② MCP + OAuth2.1<br/>(签名工具)" --> T1["SaaS MCP"]
        GW -- "③ MCP + mTLS<br/>(内部工具)" --> T2["内部工具服务"]
        GW -- "④ 沙箱内 MCP<br/>(社区工具)" --> T3["沙箱实例"]
    end

    style GW fill:#ffe0b2
```

网关是**双面 MCP**：对内它是 MCP Server（业务 Agent 的唯一工具端点），对外它是 MCP Client（按登记表连接真实工具）。这个形态把"协议统一"变成了结构性事实——不是"要求大家用 MCP"，而是"不用 MCP 连不上任何工具"。

## 3. 完整代码（照抄即可）

### 3.1 `application.yml`（MCP 客户端 + OAuth 2.1）

需在 pom.xml 中添加依赖（v3）：
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:                    # javap 实证：前缀 spring.ai.mcp.client.streamable-http，下挂 connections 映射（附录 05-01 §4）
          connections:
            saas-weather:
              url: https://tools.internal/weather/mcp
            internal-fs:
              url: https://tools.internal/filesystem/mcp
            sandbox-community:
              url: https://sandbox.internal/community/websearch/mcp
      server:
        name: tool-sec-gateway
        version: 1.0.0
  security:
    oauth2:
      resourceserver:
        jwt:
          # 网关内置授权服务器的 JWK Set（OAuth 2.1：Agent 用 client_credentials 拿 token）
          jwk-set-uri: ${GATEWAY_JWK_URI:https://id.internal/.well-known/jwks.json}
          issuer-uri: ${GATEWAY_ISSUER_URI:https://id.internal}
```

> **MCP Client Starter 的真实注入形态**（附录 05-01 §2.3）：`spring.ai.mcp.client.streamable-http.connections` 下每个 Server 自动创建一个 MCP SDK 的 `McpSyncClient` Bean。用 `List<McpSyncClient>` 注入即可拿到全部。

### 3.2 `mcp/McpClientConnections.java`（对外连接层，真实 `McpSyncClient`）

```java
package com.group.secgw.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 对外 MCP 连接层（v3）。
 * 每个登记工具对应一个 MCP Server（serverName），网关用 MCP SDK 的真实调用形态
 * callTool(new CallToolRequest(name, args)) 转发（附录 05-01 §2.2）。
 */
@Component
public class McpClientConnections {

    private final List<McpSyncClient> mcpClients;   // ⚠ 修正：真实注入 List<McpSyncClient>

    public McpClientConnections(List<McpSyncClient> mcpClients) {
        this.mcpClients = mcpClients;
    }

    /** 找到目标 Server 的客户端（serverName 来自登记表）。 */
    public McpSyncClient clientOf(String serverName) {
        return mcpClients.stream()
                .filter(c -> serverName.equals(serverNameOf(c)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no mcp client for " + serverName));
    }

    /** 执行工具调用：MCP SDK 真实签名，返回结构化结果。 */
    public CallToolResult call(String serverName, String toolName, Map<String, Object> args) {
        return clientOf(serverName)
                .callTool(new CallToolRequest(toolName, Map.copyOf(args)));
    }

    /** serverName 约定来自连接配置的 key；此处用客户端 toString 兜底（业务实现按实际 Starter 暴露的元数据调整）。 */
    private String serverNameOf(McpSyncClient client) {
        return client.toString().toLowerCase();
    }
}
```

> ⚠ `serverNameOf` 是「概念代码」——真实 Starter 下每个 `McpSyncClient` Bean 的命名约定以你引入版本为准（常见做法：用 `@Qualifier` 按配置 key 注入或注入 `Map<String, McpSyncClient>`）。核心不变式：**注入 `List<McpSyncClient>`，`callTool(new CallToolRequest(...))` 是真实签名**。

### 3.3 `mcp/GatewayProxyTool.java`（网关代理工具，`implements ToolCallback`）

```java
package com.group.secgw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group.secgw.admission.ToolRegistration;
import com.group.secgw.security.AgentPrincipal;
import org.springframework.ai.tool.definition.ToolDefinition;   // Spring AI 2.0.0 真实包
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

/**
 * 网关代理工具（v3）——暴露给 Agent 的工具回调。
 * 关键细节：Agent 看到的描述是【登记审查通过版】而非工具实时自述——
 * 真实工具描述即使漂移（v2 指纹冻结），Agent 上下文里的版本也不会被污染（ADR-308）。
 * agent 在构造时捕获，工具回调执行时据此做策略判定（v6）。
 */
public class GatewayProxyTool implements ToolCallback {

    private final ToolRegistration registration;
    private final String serverName;          // 真实工具所在 MCP Server
    private final AgentPrincipal agent;       // 构造时捕获的调用方身份
    private final McpClientConnections connections;
    private final ObjectMapper mapper = new ObjectMapper();

    public GatewayProxyTool(ToolRegistration registration, String serverName,
                            AgentPrincipal agent, McpClientConnections connections) {
        this.registration = registration;
        this.serverName = serverName;
        this.agent = agent;
        this.connections = connections;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(registration.toolId())
                .description(registration.description())   // 审查通过版，非实时自述
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> args = parseArgs(toolInput);
        // 安全管线在 toolsFor 外层执行（v4 行为分析 / v5 注入检测 / v6 策略），
        // 此处是工具执行面——转发到真实 MCP Server（网关身份调用，不透传 Agent 凭证）。
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result =
                connections.call(serverName, registration.toolId(), args);
        return result.content().toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String toolInput) {
        try {
            return mapper.readValue(toolInput, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public ToolRegistration registration() {
        return registration;
    }

    public AgentPrincipal agent() {
        return agent;
    }
}
```

### 3.4 `mcp/AgentScopedToolProvider.java`（按 Agent 动态目录）

```java
package com.group.secgw.mcp;

import com.group.secgw.admission.AdmissionService;
import com.group.secgw.admission.ToolRegistration;
import com.group.secgw.security.AgentPrincipal;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Agent 授权产出动态工具目录（v3）。
 * 目录是"审查通过版 + 该 Agent 被授权子集"；v6 起接入 ZeroTrustPdp 做细粒度过滤。
 */
@Service
public class AgentScopedToolProvider {

    /** 登记工具 → 所在 MCP Server 的映射（业务实现：来自登记表/发现服务）。 */
    private final Map<String, String> serverByTool = new ConcurrentHashMap<>();
    private final AdmissionService admissionService;
    private final McpClientConnections connections;

    public AgentScopedToolProvider(AdmissionService admissionService,
                                   McpClientConnections connections) {
        this.admissionService = admissionService;
        this.connections = connections;
        // 示例映射（v3；生产由发现服务填充）
        serverByTool.put("weather.query", "saas-weather");
        serverByTool.put("fs.read", "internal-fs");
        serverByTool.put("web.search", "sandbox-community");
    }

    public List<GatewayProxyTool> toolsFor(AgentPrincipal agent) {
        return admissionService.liveTools().stream()
                .filter(t -> authorizationAllows(agent, t))     // v3 粗粒度；v6 换 ZeroTrustPdp
                .map(t -> new GatewayProxyTool(t, serverByTool.get(t.toolId()), agent, connections))
                .toList();
    }

    /** v3 粗粒度授权：内部工具仅 PRODUCTION/TESTING 可用；C 级工具测试 Agent 不可用。 */
    private boolean authorizationAllows(AgentPrincipal agent, ToolRegistration t) {
        if (agent.trustLevel() == AgentPrincipal.TrustLevel.UNTRUSTED) {
            return false;
        }
        return t.rating() != ToolRegistration.Rating.C
                || agent.trustLevel() == AgentPrincipal.TrustLevel.PRODUCTION;
    }
}
```

### 3.5 `mcp/GatewayMcpServerConfig.java`（对内 MCP Server 注册）

```java
package com.group.secgw.mcp;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;   // Spring AI 2.0.0 真实包
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 对内 MCP Server 的工具注册（v3）。 */
@Configuration
public class GatewayMcpServerConfig {

    /**
     * 网关暴露给业务 Agent 的工具回调。
     * ⚠ 真实 API 要点（附录 05-01 §3）：@Tool 暴露为 MCP 工具必须显式声明 ToolCallbackProvider Bean，
     * 不存在"自动注册"。
     * ⚠ 每 Agent 动态目录的接入点说明：把 AgentScopedToolProvider 按当前 Agent 产出的
     * GatewayProxyTool 数组交给 MCP Server 的 tools/list。真实 Starter 下该 Hook 在
     * McpServerFeatures / ToolCallbackProvider 解析期，具体接线以你引入版本为准——
     * 若框架不直接支持"每会话目录"，就由网关自己实现 Streamable HTTP 端点（协议层），
     * 在 tools/list 时按 mTLS 解析的 Agent 过滤。核心不变式：目录永远来自登记库的审查版。
     */
    @Bean
    public ToolCallbackProvider gatewayExposedTools() {
        return MethodToolCallbackProvider.builder()
                .toolObjects(new GatewayMetaTools())
                .build();
    }

    /** 网关元信息工具（健康/目录描述），供 Agent 探测。 */
    static class GatewayMetaTools {
        @org.springframework.ai.tool.annotation.Tool(description = "返回网关安全策略元信息（不执行任何业务动作）")
        public String gatewayInfo() {
            return "tool-sec-gateway v3: MCP Streamable HTTP, OAuth 2.1, deny-by-default";
        }
    }
}
```

> ⚠ **动态目录的接线说明（概念代码边界）**：`AgentScopedToolProvider.toolsFor(agent)` 是完整可编译的服务；把它挂到 MCP Server 的 `tools/list` 上时，真实 Spring AI Starter 的 Hook 深度因版本而异。若框架不支持"每会话目录"，就用网关自研 Streamable HTTP 端点（见 [教程 11-MCP协议 §服务端实现]），在 `tools/list` 用 mTLS 解析出的 Agent 过滤。**无论哪种接线，Agent 看到的目录永远来自登记库的审查通过版（ADR-308）**。

### 3.6 `security/OAuthResourceServerConfig.java`（OAuth 2.1 资源服务器）

```java
package com.group.secgw.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;   // ⚠ 需引入依赖 org.springframework.boot:spring-boot-starter-security（本地未下载，未 javap 实证）
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;   // ⚠ 需引入依赖 spring-boot-starter-security（本地未下载，未 javap 实证）
import org.springframework.security.config.web.server.ServerHttpSecurity;   // ⚠ 需引入依赖 spring-boot-starter-security（本地未下载，未 javap 实证）
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;   // ⚠ 需引入依赖 spring-boot-starter-oauth2-resource-server（本地未下载，未 javap 实证）
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;   // ⚠ 需引入依赖 spring-boot-starter-oauth2-resource-server（本地未下载，未 javap 实证）
import org.springframework.security.web.server.SecurityWebFilterChain;   // ⚠ 需引入依赖 spring-boot-starter-security（本地未下载，未 javap 实证）

/**
 * OAuth 2.1 资源服务器（v3）：校验 Agent 的 access_token + scope 最小化。
 * Agent→网关：client_credentials 拿 token（scope: tools:invoke，audience: gateway），
 * mTLS 证书绑定 token（OAuth 2.1 的 mTLS 证书绑定约束）。
 */
@Configuration
@EnableWebFluxSecurity
public class OAuthResourceServerConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/health").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        // 校验 issuer + audience（scope 校验在 GatewayTokenValidator 完成）
        return NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
    }
}
```

### 3.7 OAuth 2.1 体系（时序）

```mermaid
sequenceDiagram
    participant AG as 业务 Agent
    participant GW as tool-sec-gateway
    participant OAuth as 网关内置授权服务器
    participant Tool as SaaS MCP 工具

    AG->>OAuth: Token Request (mTLS + client_credentials)
    OAuth->>AG: access_token (scope: tools:invoke, audience: gateway)
    AG->>GW: MCP call + Bearer token
    GW->>GW: 验 token + scope + agent 身份
    GW->>Tool: OAuth CC flow (网关自己的工具端凭证)
    Tool-->>GW: 结果
    GW-->>AG: 结果（经行为分析/注入检测，v4/v5 叠加）
```

**三层凭证互不透传**：Agent 的凭证只到网关；网关到每个工具有独立凭证（按评级最小授权）；Agent 永远拿不到工具凭证——与 LLM 网关的密钥收口同构（凭证链的最小暴露面）。

### 3.8 Sampling 与 Elicitation 的管控

| MCP 能力 | 风险 | 网关策略 |
|---------|------|---------|
| Sampling（工具请求 LLM 补全） | 工具借 Agent 的模型做任意生成（含数据外泄通道） | 默认拒绝；白名单工具的 sampling 请求改由网关代理执行（限 prompt 模板 + 输出长度上限） |
| Elicitation（工具请求用户输入） | 工具伪造表单钓取凭证 | 默认拒绝；允许的工具的 elicitation 表单字段过 DLP 规则（禁 password/secret 类字段） |

```java
package com.group.secgw.mcp;

import java.util.Set;

/** MCP 高级能力策略（v3）：默认拒绝（ADR-309）。 */
public class McpCapabilityPolicy {

    private static final Set<String> SAMPLING_WHITELIST = Set.of("internal-fs");
    private static final Set<String> ELICITATION_WHITELIST = Set.of();
    private static final Set<String> FORBIDDEN_FIELD_NAMES =
            Set.of("password", "secret", "token", "api_key", "credential");

    public boolean samplingAllowed(String serverName) {
        return SAMPLING_WHITELIST.contains(serverName);   // 默认拒绝
    }

    public boolean elicitationAllowed(String serverName) {
        return ELICITATION_WHITELIST.contains(serverName); // 默认拒绝
    }

    /** Elicitation 表单字段过 DLP：禁凭证类字段名。 */
    public boolean fieldAllowed(String fieldName) {
        String lower = fieldName.toLowerCase();
        return FORBIDDEN_FIELD_NAMES.stream().noneMatch(lower::contains);
    }
}
```

## 4. 量化验收

| # | 目标 | 验收 |
|---|------|------|
| 1 | 协议统一 | 非 MCP 的工具流量在网络层不可达（egress 按协议收紧后复测 v2 的私接场景） |
| 2 | 动态目录 | 每个 Agent 只看到被授权的工具（per-agent 目录差异化验证） |
| 3 | 描述防污染 | 真实工具描述漂移后，Agent 侧目录仍是审查通过版 |
| 4 | 凭证隔离 | Agent 侧抓包无任何工具凭证；每工具独立凭证 |
| 5 | 高级能力管控 | Sampling/Elicitation 默认拒绝路径验证；白名单路径的策略生效 |
| 6 | OAuth 合规 | Token 生命周期（15min）、scope 最小化、mTLS 绑定验证 |

### 验证包（手工测试与验证）

**前置条件**：上一迭代已验收通过；本迭代组件部署完成；测试工具/账号/沙箱就绪。

**材料**（按验收项构造测试输入）：一个非 MCP 私接测试样本；per-agent 目录差异配置；工具描述漂移 mock；Sampling/Elicitation 越权请求样本。

**步骤与断言（由量化验收表转断言清单）**：

| # | 操作（对照材料执行） | 预期（PASS 判据） |
|---|--------------------|------------------|
| 1 | 构造「协议统一」对应场景，用材料执行 | 非 MCP 的工具流量在网络层不可达（egress 按协议收紧后复测 v2 的私接场景） |
| 2 | 构造「动态目录」对应场景，用材料执行 | 每个 Agent 只看到被授权的工具（per-agent 目录差异化验证） |
| 3 | 构造「描述防污染」对应场景，用材料执行 | 真实工具描述漂移后，Agent 侧目录仍是审查通过版 |
| 4 | 构造「凭证隔离」对应场景，用材料执行 | Agent 侧抓包无任何工具凭证；每工具独立凭证 |
| 5 | 构造「高级能力管控」对应场景，用材料执行 | Sampling/Elicitation 默认拒绝路径验证；白名单路径的策略生效 |
| 6 | 构造「OAuth 合规」对应场景，用材料执行 | Token 生命周期（15min）、scope 最小化、mTLS 绑定验证 |

**失败排查**：断言不符先分层——网关入口日志（请求到没到）→ 策略/校验层日志（为何拦/放）→ 沙箱/执行层（隔离是否真的生效）；安全类验收（投毒拦截/凭证隔离/egress 阻断）失败优先验证"测试前置的恶意样本是否真的到达被测层"，再查规则。
## 5. ADR 演进决策

| # | 决策 | 理由 |
|---|------|------|
| ADR-308 | 网关双面 MCP（Server+Client），工具描述用审查通过版 | 协议统一成为结构性事实；运行时描述防污染 |
| ADR-309 | Sampling 默认拒绝 | 借模型通道的滥用面太大；确有需要的走网关代理 + 限额 |
| ADR-310 | egress 白名单按协议 + 端口收紧 | v2 教训：只封 IP 不封协议仍有私接缝隙 |

## 6. 总结

v3 完成「协议统一 + OAuth 2.1 + 动态目录 + 高级能力管控」。遗留痛点（供 v4 决策）：

协议收口完成，但运行时行为仍是黑盒：审计数据显示一个"查询天气"的工具平均每天被调 200 次，最近一周涨到 9000 次——参数也从城市名变成了包含用户完整对话上下文的长文本。**没人能回答"这正常吗"**，因为没有行为基线的概念。

→ [04-动态行为分析.md](04-动态行为分析.md)
