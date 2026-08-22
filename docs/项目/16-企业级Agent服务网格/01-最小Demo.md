# 01-最小 Demo：第一次拦截——Sidecar 接管一次 LLM 调用

> **定位**：跑通 Mesh 最小闭环：Agent（零改造，环境变量指向 localhost 代理）→ **agent-sidecar** 劫持出站 LLM 调用 → 本地执行一条策略（限流）→ 透传上游 → 遥测一条记录。读者画像：想看到"零改造被接管"的开发者。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)。
>
> **铁律 0**：Sidecar 为自研代理（WebFlux WebClient 转发）；上游对接复用 OpenAI 兼容协议。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小拦截闭环：劫持→本地策略→转发→遥测 |
| **影响了哪些模块** | `agent-sidecar`（雏形：单条路由+一条限流策略+一条遥测） |
| **架构如何演进** | Agent 直连上游 → 本地代理接管 |
| **上一版痛点** | 无（首个版本） |

**本迭代验收**：① Agent 零代码改动（仅 `base-url` 指向 `127.0.0.1:15001`）② 限流策略本地生效（超限 429）③ 每次调用生成一条遥测（含 Token 数）④ Sidecar 挂掉可一键 bypass 直连（逃生门）。

### 一.1 本节核对（四问与迭代验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问口径齐全 | 新增需求/影响模块/架构演进/上一版痛点四行均有，无空答；"上一版痛点=无（首个版本）"表述自洽 |
| 2 | 本迭代验收可度量 | ①零改动仅改 base-url ②限流超限 429 ③每调用一条遥测 ④bypass 逃生门——四项均是可判定动作，非空话 |

---

## 二、最小链路

```mermaid
flowchart LR
    A["Agent 应用(零改造)<br/>base-url=127.0.0.1:15001"] -->|"HTTP"| SC["agent-sidecar<br/>:15001"]
    SC --> P["本地策略链<br/>①限流(静态配置)"]
    P -->|"放行"| UP["上游 LLM<br/>(OpenAI 兼容)"]
    P -->|"超限"| R429["429"]
    SC --> T["遥测<br/>{agent,path,tokens,ms,status}"]
    style SC fill:#e8f5e9
```

### 二.1 本节核对（最小链路）

- [ ] 链路上的每个节点都能在 §三 代码中找到落点（`/v1/chat/completions` 路由、`RateLimiter`、`upstream` WebClient、`emit` 遥测）
- [ ] 超限 → 429 与 放行 → 上游 LLM 两条出边与 §三 的 `if (!limiter.tryAcquire())` 分支一一对应

## 三、核心代码（Sidecar 转发骨架）

```java
package com.example.mesh.sidecar;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/** 最小 Sidecar——一个路由：劫持 /v1/chat/completions，本地限流后转发。 */
public class LlmProxyHandler {

    private final org.springframework.web.reactive.function.client.WebClient upstream;
    private final RateLimiter limiter = new TokenBucket(10, 5);   // 10 QPS，突发 5

    public LlmProxyHandler(WebClient.Builder wb) {
        this.upstream = wb.baseUrl(System.getenv("LLM_UPSTREAM")).build();
    }

    public RouterFunction<ServerResponse> route() {
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
                .POST("/v1/chat/completions", req -> {
                    if (!limiter.tryAcquire()) {
                        return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                                .bodyValue("{\"error\":\"mesh: local rate limited\"}");
                    }
                    long start = System.nanoTime();
                    return req.bodyToMono(String.class).flatMap(body ->
                            upstream.post().uri("/v1/chat/completions")
                                    .header("Content-Type", "application/json")
                                    .bodyValue(body)
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .flatMap(resp -> {
                                        emit(start, 200);      // 遥测（含耗时）
                                        return ServerResponse.ok().bodyValue(resp);
                                    }));
                })
                .build();
    }

    private void emit(long startNanos, int status) {
        // 最小遥测：stdout；06 迭代换 OTel 标准管道
        System.out.printf("[MESH] path=/v1/chat/completions status=%d ms=%d%n",
                status, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
```

> **为什么从"显式代理地址"起步**：透明劫持（iptables/eBPF/出口拦截）是 02 迭代——先证明策略价值，再消除接入成本。

### 三.1 本节测试与验证（Sidecar 转发骨架）

**前置条件**：Sidecar（`LlmProxyHandler`：路由+限流+转发+遥测）可编译运行；一个 OpenAI 兼容客户端作测试 Agent；`LLM_UPSTREAM` 环境变量已指向实际上游。

**材料——限流配置**：桶参数 `new TokenBucket(10, 5)`（10 QPS，突发 5）。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 编译启动 Sidecar（监听 15001） | 启动无异常；`base-url=127.0.0.1:15001` 的 Agent 首次调用透传成功，stdout 出现 `[MESH] ... status=200 ... ms=` |
| 2 | 连续快速请求超过突发 5（超桶速） | 返回 `429` 与 `"mesh: local rate limited"` 文案（本地限流生效） |
| 3 | ≤10 QPS 平稳请求 | 正常 200 透传，无 `Header 透传缺 Authorization` 类报错（上游仍能识别调用方） |
| 4 | 直连 vs 走 Sidecar 各 100 次计时 | Sidecar 引入增量延迟 ≤5ms |

**失败排查**：①改 base-url 后不通→`Authorization` 头未透传（转发时未拷贝请求 Header）；②限流不生效即全放行→桶算法窗口重置逻辑有误（检查 `tryAcquire` 的 refill）；③计时超 5ms→每请求新建上游连接（应复用 `WebClient` 连接池）。

## 四、逃生门（Bypass）

```yaml
# sidecar 自身故障时：本地配置 mesh.bypass=true → Agent 直连上游（治理降级但不中断业务）
# 工业纪律：旁路系统的第一设计原则——治理可失联，业务不可中断
```

### 四.1 本节测试与验证（逃生门 Bypass）

**前置条件**：Sidecar 已实现 `mesh.bypass` 开关；一个 Agent 走 Sidecar 接入。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 正常接入下 `bypass=false` | 调用走 Sidecar（遥测有记录） |
| 2 | `kill` Sidecar 进程且 `bypass=true` | Agent 仍可直连上游获得响应（治理降级不中断业务） |

**失败排查**：①bypass=true 且 Sidecar 存活时仍被接管→开关未在代理转发逻辑判断（应静默直连不劫持）；②bypass 后连不上→Agent 的 `base-url` 仍指向已死的 15001（bypass 需 Agent 侧回退到直连地址）。

## 五、全篇回归验证

> §三.1（转发链路）与 §四.1（逃生门）均通过后的整体验收。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | Agent 只改 base-url 指向 Sidecar（零代码改造）触发一轮调用 | 调用成功；Sidecar 遥测有该次记录（含 `[MESH]` 行） |
| 2 | 压测器（wrk/hey）发 15 QPS 持续 30s（桶 10） | 超桶速请求 429（本地限流生效）；≤10 QPS 正常通过 |
| 3 | kill Sidecar 且 bypass=true 后再压测 | Agent 仍可直连上游（逃生门可用） |
| 4 | 直连 vs 走 Sidecar 各 100 次计时 | Sidecar 增量延迟 ≤5ms |

**回归失败排查**：任一步 FAIL 按 §三.1/§四.1 对应排查项回溯（透传 Header / 桶算法 / 连接复用 / bypass 开关）。

## 六、本迭代痛点

① 策略写死 Sidecar（静态）→ 03 xDS 动态下发 ② 仅显式代理接入 → 02 透明劫持 ③ 遥测太原始 → 06 统一遥测。

> 本节核对（一句话）：三条痛点与后续迭代（03 xDS / 02 透明劫持 / 06 遥测）一一对应，无搁置项即 PASS。

## 七、验收对照

| 验收项 | 标准 | 状态 |
|--------|------|------|
| 零改造 | 仅改 base-url | ✅ |
| 本地策略 | 限流 429 | ✅ |
| 遥测 | 每调用一条 | ✅ |
| 逃生门 | bypass 可用 | ✅ |

**下一篇**：[02-透明拦截与注入](02-透明拦截与注入.md)。
