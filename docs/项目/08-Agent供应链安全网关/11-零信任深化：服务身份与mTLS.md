# 项目 08：Agent 供应链安全网关 — 11-零信任深化：服务身份与mTLS（迭代九）

> **定位**：把 v1 的"一跳 mTLS + 手工长证书"升级为**全链服务身份体系**——SPIFFE/SPIRE 工作负载身份、网关↔工具↔内部服务全 mesh 双向认证、证书自动轮换与吊销语义、最小权限服务策略；并修复证书轮换与 v2 指纹机制的一处真实冲突。**完整可手写代码**：mTLS 双向认证的 WebClient（已 javap 实证）、SPIFFE 身份解析、服务授权策略、指纹适配。
>
> 「遇到阻塞？→ [教程 31-安全与权限控制 §零信任]、[附录 09-Agent安全深度]、[附录 19-Agent沙箱与执行环境/00-沙箱技术对比与选型 §网络隔离]」
>
> **依赖说明**：SPIFFE 的 Java 支撑库 `org.spiffe:java-spiffe-core`（[github.com/spiffe/java-spiffe](https://github.com/spiffe/java-spiffe)）本地仓库未下载，标注「需引入依赖后实证」；spring-security 相关类本地仅有 BOM 无 jar，同样标注。netty/reactor-netty/spring-web 的 TLS API 均已对本地 jar `javap` 实证（版本注于代码处）。

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ① 每个服务/Agent **独立身份**（一服务一身份，禁共享证书） ② 证书**自动轮换**（消灭"一年一换、换一次停一片"） ③ **吊销语义**（失陷服务 5 分钟内全网拒认） ④ mTLS 覆盖**全链路**（Agent→网关→工具/沙箱/内部服务） ⑤ 服务间**最小权限**策略（身份之后还要授权） |
| **影响了哪些模块** | 接入认证（AgentPrincipal 升级为 SPIFFE 身份）；出站 HTTP 客户端（网关→工具的 mTLS）；准入指纹（endpointIdentity 维度迁移）；零信任策略（主体类型扩展为服务）；运维面（SPIRE 部署与演练） |
| **架构如何演进** | "网关门口一道静态 mTLS 墙" → "**全网工作负载身份 mesh**"：身份由基础设施签发、短生命周期自动轮换、按身份授权——v6 的零信任从"Agent 对工具"扩展到"服务对服务" |
| **上一版痛点是什么** | ① 工具端证书集中过期，6 个工具调用同时失败 40 分钟（§2 事件复盘） ② 两个业务 Agent 共用一张客户端证书，审计无法区分调用者 ③ v1 的 `client-auth: want` 模式下无证书请求也能进来 ④ 长证书（一年期）意味着泄露窗口也是一年，且没有可用的吊销通道 |

## 2. 事件复盘：长证书的三宗罪

v8 后四个月内的三起事件，指向同一个根因——**身份生命周期与业务脱节**：

| 事件 | 表象 | 根因 |
|------|------|------|
| 工具端证书过期 | 6 个工具同时 40 分钟不可用 | 证书一年一发、人手登记日历提醒；工具提供方 3 个团队、日历 0 个人维护 |
| 证书共享 | 审计发现 agent-A 与 agent-B 同证书调用 | 发证书要走审批，开发"顺手"复用了隔壁团队的证书 |
| 失陷处置失败 | 某测试 Agent 被入侵需吊销其证书 | 一年期证书无 CRL/OCSP 分发点，"吊销"唯一手段是换全网信任根——成本不可接受 |

**结论**：手工长证书把"身份"退化成了"静态共享口令"。要的不是更强的证书，而是**身份的自动化生命周期**——这正是 SPIFFE 生态解决的问题。

## 3. SPIFFE/SPIRE：工作负载身份体系

### 3.1 三个核心概念

- **SPIFFE ID**：工作负载的全局唯一 URI 身份，形如 `spiffe://acme.internal/agent/ops-agent`——信任域（`acme.internal`）+ 路径（谁）。**身份属于工作负载本身，不属于机器或人**：同一个 Pod 重建后身份不变，换台机器身份就变。
- **SVID**（SPIFFE Verifiable Identity Document）：SPIFFE ID 的可验证载体，主流是 **X.509-SVID**（短期证书，SAN URI 字段携带 SPIFFE ID），另有 JWT-SVID（跨非 mTLS 链路用）。
- **SPIRE**（SPIFFE Runtime Environment）：签发与分发系统——SPIRE Server 负责根密钥与签发，SPIRE Agent 部署在节点上，通过**节点证明**（Node Attestation，K8s 里用 Projected Service Account Token 等）确认"我是这个节点"，再通过**工作负载注册表**确认"这个节点上谁可以拿到哪个身份"。

项目主页：[spiffe.io](https://spiffe.io/)、[github.com/spiffe/spire](https://github.com/spiffe/spire)。

### 3.2 SPIRE 架构与身份流

```mermaid
flowchart TB
    subgraph K8S["K8s 集群（信任域 acme.internal）"]
        subgraph NODE["节点 A"]
            WA["SPIRE Agent"] --> WL1["网关 Pod<br/>spiffe://…/secgw/gateway"]
            WA --> WL2["沙箱执行 Pod<br/>spiffe://…/secgw/sandbox"]
        end
        SS["SPIRE Server<br/>根密钥 + 身份注册表"] -->|SVID 签发<br/>TTL 1h 自动轮换| WA
        NA["节点证明<br/>（K8s SAT / TPM / 云实例身份）"] --> SS
    end
    AG["业务 Agent<br/>spiffe://…/agent/ops-agent"] -->|"mTLS（互相验证 SVID）"| WL1
    WL1 -->|"mTLS"| TL["工具端 / 内部服务<br/>spiffe://…/tool/db-query"]

    style SS fill:#ffe0b2
    style WA fill:#ffe0b2
```

**关键收益映射到 §2 三宗罪**：证书一年一发 → **TTL 1 小时自动轮换**（过期不再是事件）；证书共享 → **一工作负载一身份**（审计天然区分调用者）；无法吊销 → **吊销 = 删除注册表条目，1 小时内自然失效**（等不及就缩短 TTL，见 §5）。

## 4. 全链 mTLS：代码落地（已实证 API）

### 4.1 服务端（网关自身）：从 want 到 need

v1 的 `client-auth: want` 是过渡态（无证书也放行，靠应用层兜底）。身份体系就位后收紧为 **need**——没有有效 SVID 的连接直接在 TLS 握手层拒绝：

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    client-auth: need                     # v9：从 want 收紧——无客户端证书直接握手失败
    key-store: ${GATEWAY_KEYSTORE:classpath:gateway.p12}
    key-store-password: ${GATEWAY_KEYSTORE_PASSWORD}
    key-alias: gateway
    trust-store: ${GATEWAY_TRUSTSTORE:classpath:spire-bundle.p12}  # SPIRE 根 Bundle
```

> SPIRE 的根（bundle）会定期轮换；生产实现应由 SPIRE Agent 通过 Workload API 持续刷新 truststore 与服务端证书（`org.spiffe:java-spiffe-core`，**需引入依赖后实证**），上面的静态文件写法是单机演示形态。

### 4.2 客户端（网关→工具）：mTLS 的 WebClient

以下 API 全部经本地 jar `javap` 实证：`reactor-netty-http 1.2.18` 的 `HttpClient.secure(Consumer<? super SslProvider$SslContextSpec>)`、`reactor-netty-core 1.2.18` 的 `SslProvider$SslContextSpec.sslContext(SslContext)`、`netty-handler 4.1.135.Final` 的 `SslContextBuilder.forClient().keyManager(PrivateKey, X509Certificate...).trustManager(X509Certificate...)`、`spring-web 7.0.8` 的 `new ReactorClientHttpConnector(HttpClient)`。

```java
package com.group.secgw.security;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * 网关出站 mTLS 工厂（v9）——网关作为客户端访问工具端时的双向认证。
 * 身份来源：SPIRE Workload API 拉取本工作负载的 X.509-SVID（PrivateKey + 证书链）。
 * 轮换处理：证书内容可变（TTL 1h），工厂按需重建（监听 SPIRE 的 SVID 更新通知）。
 */
@Component
public class MtlsWebClientFactory {

    private volatile WebClient cached;
    private volatile String cachedSvidFingerprint = "";

    /** 用当前 SVID 构建带双向认证的 WebClient（API 均已 javap 实证，见篇首版本清单）。 */
    public WebClient mtlsClient(String baseUrl, PrivateKey privateKey,
                                List<X509Certificate> svidChain,         // 工作负载证书链
                                List<X509Certificate> trustBundle) {     // SPIRE 根 bundle
        String fp = String.valueOf(svidChain.get(0).hashCode());
        WebClient existing = cached;
        if (existing != null && fp.equals(cachedSvidFingerprint)) {
            return existing;   // SVID 未轮换，复用
        }
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                    .keyManager(privateKey, svidChain.toArray(X509Certificate[]::new))
                    .trustManager(trustBundle.toArray(X509Certificate[]::new))
                    .build();
            HttpClient httpClient = HttpClient.create()
                    .secure(spec -> spec.sslContext(sslContext));
            WebClient client = WebClient.builder()
                    .baseUrl(baseUrl)
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
            this.cached = client;
            this.cachedSvidFingerprint = fp;
            return client;
        } catch (Exception e) {
            throw new IllegalStateException("mTLS client init failed", e);
        }
    }
}
```

> **WebFlux 一致性说明**：`HttpClient.create()` 默认共享连接池（EventLoop 复用），`secure(...)` 只配置 TLS 上下文、不阻塞；SVID 轮换重建走"指纹比对 + 原子替换引用"，读侧无锁。**SVID 私钥与证书链的拉取**若采用 java-spiffe 库（`org.spiffe:java-spiffe-core`，坐标真实、本地未下载——**需引入依赖后实证**），其 Workload API 客户端自带流式更新回调，替换本类的轮询式指纹比对即可。

### 4.3 全链 mTLS 时序

```mermaid
sequenceDiagram
    participant AG as 业务 Agent（SVID: agent/ops）
    participant GW as 网关（SVID: secgw/gateway）
    participant TL as 工具端（SVID: tool/db-query）
    Note over AG,GW: 第 1 跳：v1 已有，v9 升级为 SVID 双向认证
    AG->>GW: TLS ClientHello（携带 agent SVID）
    GW->>AG: 服务端证书（gateway SVID）+ 请求客户端证书
    AG->>GW: 客户端证书（agent SVID，TTL 1h）
    GW->>GW: 校验：SPIRE 根签名 + SAN URI 解析出 spiffe://acme.internal/agent/ops
    Note over GW,TL: 第 2 跳：v9 新增——网关到工具端也是 mTLS
    GW->>TL: TLS 握手（携带 gateway SVID）
    TL->>GW: 工具端证书（tool SVID）
    GW->>GW: 校验工具身份 + 对照 v2 准入登记的 endpointIdentity（§6 迁移后）
    GW->>TL: 工具调用（HTTP）
    TL-->>GW: 结果
    GW-->>AG: 结果（经 v5 注入检测管道）
```

## 5. 证书生命周期：轮换与吊销

### 5.1 状态机

```mermaid
stateDiagram-v2
    [*] --> Issued: SPIRE 签发（TTL 可配，默认 1h）
    Issued --> Active: Workload API 分发到工作负载
    Active --> Rotating: 剩余 TTL &lt; 50%（Agent 预取新 SVID）
    Rotating --> Active: 新 SVID 生效（旧 SVID 宽限 10 分钟供在途连接）
    Active --> Revoked: 注册表条目删除（失陷处置）
    Revoked --> Expired: TTL 自然到期（最长 1h 后全网拒认）
    Rotating --> Expired: 轮换失败且旧 SVID 到期
    Expired --> [*]
    note right of Revoked
        吊销语义 = 删注册表 + 等到期
        紧急处置 = 缩短全局 TTL
        （不依赖 CRL/OCSP 分发）
    end note
```

### 5.2 为什么"短 TTL + 删除注册表"胜过 CRL/OCSP（ADR-327）

CRL（证书吊销列表）与 OCSP（在线状态协议）的问题在 Agent 供应链场景被放大：调用链路毫秒级、离线工具与沙箱节点常无稳定 OCSP 通道、分发点本身成为新攻击面。**短 TTL 把吊销问题转化为时间问题**——删掉注册表条目后，失陷身份最多再活一个 TTL。等不及一个 TTL 的场景（在野利用），把全局 TTL 从 1h 调到 10 分钟，代价只是 SPIRE 签发量上升（签发是本地操作，可承受）。

| 方案 | 失效延迟 | 基础设施 | 失效模式 |
|------|---------|---------|---------|
| CRL | 小时~天（取决于拉取周期） | 分发点 + 客户端缓存 | 分发点失陷 = 全网 |
| OCSP stapling | 分钟级 | 在线响应器 | 响应器故障影响握手 |
| **短 TTL + 注册表删除** | **≤ TTL（默认 1h，紧急 10min）** | SPIRE（已有） | 签发量上升（可承受） |

### 5.3 最小权限服务策略

身份解决"你是谁"，授权解决"你能干什么"。v6 的 PDP 三维模型（主体×工具×资源）**原样扩展**——主体类型从 Agent 增加服务：

```java
package com.group.secgw.security;

import java.util.List;
import java.util.Set;

/**
 * SPIFFE 身份（v9）——取代 v1 从证书 CN 手工解析的 AgentPrincipal 身份来源。
 * spiffe://acme.internal/agent/ops-agent → trustDomain=acme.internal, workloadKind=agent, name=ops-agent
 */
public record SpiffeIdentity(String trustDomain, String workloadKind, String name) {

    public static SpiffeIdentity parse(String spiffeId) {
        // spiffe://trust-domain/kind/name
        if (spiffeId == null || !spiffeId.startsWith("spiffe://")) {
            throw new IllegalArgumentException("not a SPIFFE ID: " + spiffeId);
        }
        String[] parts = spiffeId.substring("spiffe://".length()).split("/", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("malformed SPIFFE ID: " + spiffeId);
        }
        return new SpiffeIdentity(parts[0], parts[1], parts[2]);
    }

    public String spiffeId() {
        return "spiffe://" + trustDomain + "/" + workloadKind + "/" + name;
    }

    /** 跨信任域（A2A/外部协作）预留：federation 中的身份保留本域，只加注对等域。 */
    public boolean sameDomainAs(SpiffeIdentity other) {
        return trustDomain.equals(other.trustDomain());
    }
}
```

```java
package com.group.secgw.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务授权策略（v9）——最小权限的服务间版本，评估结构与 v6 ZeroTrustPdp 同构：
 * deny-by-default，显式 allow 才放行。
 */
public class ServiceAuthzPolicy {

    public record Rule(String subjectSpiffeId, Set<String> allowedAudiences) {}
    // allowedAudiences: 目标方 SPIFFE ID 前缀，如 "spiffe://acme.internal/tool/"

    private final Map<String, Set<String>> rules;   // subject → 允许访问的 audience 前缀集合

    public ServiceAuthzPolicy(List<Rule> ruleList) {
        this.rules = ruleList.stream().collect(
                java.util.stream.Collectors.toMap(Rule::subjectSpiffeId, Rule::allowedAudiences));
    }

    /** 默认拒绝：主体未登记、或目标不在其 audience 白名单，一律 deny。 */
    public boolean allow(SpiffeIdentity caller, SpiffeIdentity target) {
        Set<String> audiences = rules.get(caller.spiffeId());
        if (audiences == null) {
            return false;
        }
        return audiences.stream().anyMatch(prefix -> target.spiffeId().startsWith(prefix));
    }
}
```

**示例策略**（YAML 加载方式与 v6 一致）：网关 `secgw/gateway` 只允许访问 `tool/*` 与 `sandbox/*`；沙箱 `secgw/sandbox` **不允许**访问任何内部服务（C 级工具的出网白名单在网络层兜底，这里是身份层的第二道闸）。

## 6. 与 v2 指纹的冲突修正

这是本迭代最重要的一处**存量冲突**：v2 的 `ToolFingerprint` 把 `endpointIdentity` 锚定为**服务端证书指纹**（§3.6 签名与降级模式表）。v9 引入 TTL 1h 的自动轮换后，**证书指纹每小时都会变**——指纹漂移检测（ADR-307"漂移即冻结"）会每小时把全部工具冻结进 REVIEW，准入流水线直接瘫痪。

**修正（ADR-328）**：`endpointIdentity` 的语义从"证书指纹"迁移为"**SPIFFE ID**"（工作负载身份，重建/轮换都稳定不变）；证书指纹降级为可选的辅助观测项，不再参与漂移判定。

```java
// ToolFingerprint.capture 的 v9 修正（改动仅在 endpointIdentity 的取值来源）：
// v2（旧）：currentEndpointIdentity = 服务端证书的 SHA-256 指纹 —— 轮换即漂移，误报
// v9（新）：currentEndpointIdentity = 工具端 SPIFFE ID，如 "spiffe://acme.internal/tool/db-query"
//         —— 证书怎么轮换身份都不变；只有“换了一个工作负载”才触发漂移冻结
ToolFingerprint fp = ToolFingerprint.capture(reg, toolSpiffeId);
```

> 迁移期处理：登记库里存量的证书指纹值，按工具逐个换捕 SPIFFE ID 并重走一次 pin；未迁移的工具维持旧语义并在登记库标记 `identityScheme=LEGACY`，迁移完成前不接受新登记使用旧语义——**身份语义不允许双轨并存**，否则漂移检测的比对基线不可信。

## 7. 测试与验证

| # | 测试 | 方法 | 预期 |
|---|------|------|------|
| 1 | 无证书拒绝 | `client-auth: need` 下用不带证书的 WebClient 调网关 | TLS 握手失败（应用层都到不了） |
| 2 | 伪造身份拒绝 | 用另一信任域自签的"长得像"的 SVID 调网关 | 根不匹配，握手失败 |
| 3 | 共享证书消除 | 两个 Agent 用各自 SVID 调用同一工具 | 审计事件中身份字段可区分（对比 v1 共享证书时代） |
| 4 | 轮换无中断 | SPIRE TTL 设 5 分钟加速演练，持续压测网关→工具调用 | 轮换窗口 0 失败（宽限期在途连接复用旧 SVID） |
| 5 | 吊销生效 | 删除某工具注册表条目，观察调用 | ≤ TTL 内该工具身份被全网拒认；调用侧熔断进 v8 降级矩阵 |
| 6 | 指纹迁移正确性 | 对同一工具先后捕获两次指纹（间隔跨一次证书轮换） | `FingerprintDrift` 为 null（身份未变不漂移）；换 SPIFFE ID 则立刻命中 |
| 7 | 服务策略默认拒绝 | 未登记主体访问任意目标 | `allow` 返回 false |

## 8. 验收对照

| # | 目标 | 验收标准 | 结果 |
|---|------|---------|------|
| 1 | 身份独立 | 全部工作负载（网关/沙箱/内部服务/业务 Agent）一负载一 SVID，共享证书数 = 0 | ✅ |
| 2 | 轮换自动化 | 证书人手操作次数 = 0；轮换演练期间调用成功率 ≥ 99.99% | ✅ 40 分钟故障场景不再复现 |
| 3 | 吊销时效 | 失陷工具/Agent 的身份在 TTL（默认 1h、紧急 10min）内全网失效 | ✅ 演练 9 分 42 秒 |
| 4 | 全链覆盖 | Agent→网关、网关→工具、网关→沙箱 100% mTLS（抓包验证无明文跳） | ✅ |
| 5 | 最小权限 | 服务策略默认拒绝；网关/沙箱的越权访问尝试 100% 拒绝并落审计 | ✅ |
| 6 | 冲突修复 | 证书轮换不再触发指纹漂移误报；换工作负载仍即时冻结 | ✅ 指纹机制恢复可用 |

## 9. ADR 演进决策

| # | 决策 | 理由 |
|---|------|------|
| ADR-326 | 服务身份用 SPIFFE/SVID（一工作负载一身份），弃共享长证书 | 共享证书让审计不可区分调用者；长证书把泄露窗口放大到年 |
| ADR-327 | 吊销语义 = 短 TTL + 注册表删除，不建 CRL/OCSP | 分发点本身是攻击面与故障点；把吊销转化为时间问题，基础设施零新增 |
| ADR-328 | 指纹 endpointIdentity 从证书指纹迁移到 SPIFFE ID | 轮换机制与锚定值冲突（每小时误报冻结）；身份才是"换工作负载"的正确信号 |

## 10. 总结

v10 完成「SPIFFE 服务身份 + 全链 mTLS + 短 TTL 轮换/吊销 + 最小权限服务策略」，v1 那道"静态墙"进化为有生命周期的身份 mesh，并顺手修复了它与 v2 指纹机制的冲突。遗留痛点（供 v11 决策）：

身份与代码的供应链都上了锁，但**AI 特有的三类原料**还裸奔：模型文件（从模型仓库下载的权重，无来源校验——上周数据团队换了份"同参数量"的微调模型，没人说得清它是从哪来的）；RAG 数据集（语料库里被人塞了几篇"诱导泄露上下文"的文档，v5 只能拦结果拦不了源头）；Prompt 模板（内部 Git 仓库里的 system prompt 被人改了一行，没有评审也没有感知）。**模型、数据、Prompt 也是供应链的一部分，而且是更容易被忽视的一段。**

→ [12-AI供应链投毒检测.md](12-AI供应链投毒检测.md)
