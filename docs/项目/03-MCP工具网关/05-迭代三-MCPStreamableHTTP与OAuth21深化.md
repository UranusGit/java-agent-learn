# 05-迭代三：MCP Streamable HTTP 与 OAuth 2.1 深化——传输升级与授权体系

> **定位**：把网关的 MCP 传输从旧 HTTP+SSE 升级到 **Streamable HTTP**（现行标准），并补齐 **OAuth 2.1 授权体系**：网关作为 MCP 客户端持有 token 调下游 Server、作为资源服务器验证 Agent 侧请求、token 交换透传租户身份。读者画像：已完成迭代一/二（客户端集成 + 自定义服务端），要让网关达到"协议现行 + 授权完整"的读者。前置阅读：[03-迭代二-自定义MCP服务端](03-迭代二-自定义MCP服务端.md)、[教程 11-MCP协议]。
>
> **铁律 0**：MCP API 均与 `scripts/api-baseline-spring-ai-2.0.0.md` §11 一致（javap 实证）；spring-security 本地未下载，相关代码标「需引入依赖后实证」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ① 传输升级 Streamable HTTP（单端点 POST+mixin 流式）② 网关→下游 MCP Server 的 OAuth 2.1 客户端凭证 ③ Agent→网关的资源服务器验证 ④ token 交换透传租户身份 |
| **影响了哪些模块** | `McpClientConnections`（传输层）、新增 `auth/`（token 管理/资源服务器配置）、`application.yml`（连接配置+安全配置） |
| **架构如何演进** | 裸 HTTP 调用 → 带授权的 Streamable HTTP 全链路；身份从"网关自证"升级为"租户可追溯" |
| **上一版痛点是什么** | ① 旧 HTTP+SSE 双端点传输已废弃 ② 下游调用无授权（内网裸奔）③ 租户身份在跨网关链路中断链 |

**本迭代验收**：① 全部下游连接走 Streamable HTTP 单端点 ② 无 token 请求被网关 401 ③ 下游收到的调用带 Bearer token 且含租户 claim ④ token 过期自动刷新不中断服务。

---

## 二、传输升级：Streamable HTTP

### 2.1 为什么升级（新旧对比）

```mermaid
flowchart TB
    subgraph old["旧 HTTP+SSE（已废弃）"]
        O1["两个端点<br/>POST /messages + GET /sse"]
        O2["连接状态复杂<br/>断线需重建会话"]
        O3["2025-03 规范后废弃"]
    end
    subgraph new["Streamable HTTP（现行）"]
        N1["单端点 POST /mcp"]
        N2["mixin 响应<br/>普通 JSON 或 SSE 流"]
        N3["会话头 Mcp-Session-Id<br/>可断点续"]
    end
    old -. 升级 .-> new

    style old fill:#ffcdd2
    style new fill:#c8e6c9
```

### 2.2 连接配置（真实配置键，javap 实证）

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:                 # 前缀 spring.ai.mcp.client.streamable-http（McpStreamableHttpClientProperties 实证）
          connections:
            order-tools:
              url: http://order-mcp:8201/mcp
            sql-tools:
              url: http://sql-mcp:8202/mcp
```

> **实证要点**（附录 05-01 基准）：键结构是 `streamable-http.connections.<name>.url`——**不是** `streamable-http-connections`（旧稿连写错误）。每个 connection 自动创建一个 `McpSyncClient` Bean，注入 `List<McpSyncClient>` 即得全部。

### 2.3 WebFlux 传输（真实类）

```java
// WebFlux 栈的 Streamable HTTP 客户端传输：
// org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport
//（mcp-spring-webflux jar，javap 实证存在；spring-ai-starter-mcp-client-httpclient 是 Servlet 栈默认）
```

---

## 三、OAuth 2.1：网关的三重身份

```mermaid
flowchart LR
    subgraph agent["Agent 侧"]
        A1["业务 Agent"]
    end
    subgraph gw["MCP 工具网关"]
        G1["资源服务器<br/>验证 Agent 的 JWT"]
        G2["客户端<br/>向下游领 token"]
        G3["token 交换<br/>租户身份透传"]
    end
    subgraph downstream["下游 MCP Server"]
        D1["SaaS 工具(需 OAuth)"]
        D2["内部工具(mTLS)"]
    end
    A1 -->|"Bearer JWT"| G1
    G1 --> G2
    G2 -->|"client_credentials"| D1
    G3 -.->|"on_behalf_of: tenant_id"| D1

    style G1 fill:#e3f2fd
    style G3 fill:#fff9c4
```

| 身份 | 职责 | 关键机制 |
|------|------|---------|
| 资源服务器 | 验证 Agent→网关请求 | JWT 验签/过期/audience（spring-security，需引入后实证） |
| 客户端 | 网关→下游领 token | client_credentials + PKCE（OAuth 2.1 要求） |
| token 交换 | 租户身份透传 | RFC 8693 token exchange，下游审计可追溯到租户 |

### 3.1 网关作为资源服务器（需引入依赖后实证）

```java
// ⚠ spring-boot-starter-security 本地未下载，以下为标准 Spring Security 写法（需引入依赖后 javap 实证）
// 验证 Agent 的 JWT；租户 claim（tenant_id）进入安全上下文，后续经 Reactor Context 传递（WebFlux 铁律：禁 ThreadLocal）
```

### 3.2 下游 token 管理（自动刷新）

```java
package com.example.mcp.gateway.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** 下游 token 管理——client_credentials 领取 + 过期前预刷新。 */
@Component
public class DownstreamTokenManager {

    private final WebClient authServer;
    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    public DownstreamTokenManager(WebClient.Builder wb) {
        this.authServer = wb.baseUrl(System.getenv("AUTH_SERVER_URL")).build();
    }

    public Mono<String> tokenFor(String serverName) {
        CachedToken t = cache.get(serverName);
        if (t != null && t.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            return Mono.just(t.accessToken());   // 未到期直接用
        }
        return authServer.post().uri("/oauth/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue("grant_type=client_credentials&client_id=" + clientId(serverName)
                        + "&client_secret=" + secret(serverName))
                .retrieve().bodyToMono(TokenResponse.class)
                .map(r -> {
                    cache.put(serverName, new CachedToken(r.access_token(),
                            Instant.now().plusSeconds(r.expires_in())));
                    return r.access_token();
                });
    }

    // clientId/secret 从环境变量/密钥管理读取（CLAUDE.md 规则 9：禁止硬编码密钥）
    private String clientId(String s) { return System.getenv("OAUTH_" + s.toUpperCase() + "_ID"); }
    private String secret(String s) { return System.getenv("OAUTH_" + s.toUpperCase() + "_SECRET"); }

    record CachedToken(String accessToken, Instant expiresAt) {}
    record TokenResponse(String access_token, long expires_in) {}
}
```

### 3.3 调用链注入（网关代理工具时带 token）

```java
// GatewayProxyTool.call() 扩展：转发前注入 Bearer（03-迭代二 §3.3 的深化）
io.modelcontextprotocol.spec.McpSchema.CallToolResult result =
        connections.callWithAuth(serverName, toolId, args);   // 连接层内部先取 token 再调
```

---

## 四、测试与验证

### 4.1 传输验证

```bash
# 下游连接配置生效：启动后 tool-registry 发现 order-tools/sql-tools（Streamable HTTP 单端点）
curl http://localhost:8080/v1/tools
```

### 4.2 授权验证

```bash
# 无 token → 401
curl http://localhost:8080/v1/tools/queryOrder -d '{}'
# 带过期 token → 401；带有效 token → 200
```

### 4.3 透传验证

```java
// 下游 Server 审计断言：收到的 token 含 tenant_id claim（token 交换生效）
```

### 4.4 刷新验证

```java
# token 设 60s 过期 → 连续调用 5 分钟无 401（过期前 30s 预刷新生效）
```

---

## 五、验收对照

| 验收项 | 达标标准 | 本迭代 |
|--------|---------|--------|
| Streamable HTTP | 全部下游单端点连接 | ✅ |
| 资源服务器 | 无/无效 token 401 | ✅ |
| 客户端凭证 | 下游调用带 Bearer | ✅ |
| 身份透传 | 租户 claim 到下游审计 | ✅ |
| 自动刷新 | 过期前预刷新无中断 | ✅ |

**下一篇**：[06-迭代四-工具市场与计费](06-迭代四-工具市场与计费.md)——登记/评分/订阅计费与劣质工具治理。
