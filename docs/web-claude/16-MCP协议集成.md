# 16 - MCP 协议集成：接入外部工具生态

> 本章让 web-claude 不再是"孤岛"。MCP（Model Context Protocol）是 Anthropic 主推的
> 工具生态协议，社区已有数百个 server（github / filesystem / slack / database / browser...）。
> 完成后：用户配置一个 MCP server，agent 就能用它的工具。

---

## 本章五步地图

| 步 | 节 | 你要带走什么 |
|----|----|---------|
| ① 痛点 | §1 | 05 章工具是内置的——生态闭，自己写工具成本高 |
| ② 最小实现 | §2–§7 | MCP 数据模型 + Client 管理器 + 三种 transport + ToolRegistry 整合 + 命名空间 + 生命周期 |
| ③ 验证 | §12 | 配一个 GitHub MCP server，让 Agent 创建 issue |
| ④ 对照 | §13 | 与"内置工具硬编码"的生态差异 |
| ⑤ 避坑 | §9 | stdio 子进程泄漏 / SSE 重连 / 命名冲突 / 安全风险 |

---

## 1. 为什么 MCP 重要

### 0.1 内置工具的局限

05 章我们做了 5 个内置工具（Read/Write/Bash/Edit/Grep），但真实业务需要：
- 接 GitHub PR / Issue；
- 查数据库；
- 发 Slack 通知；
- 操作 K8s；
- 浏览器自动化；
- ……

每写一个工具都是从零开始。**MCP 让生态共享**。

### 0.2 MCP 是什么

- Anthropic 提出的开放协议；
- 协议层：JSON-RPC 2.0；
- 传输层：stdio / SSE / streamable HTTP；
- 三类能力：tools（工具调用）/ resources（资源读取）/ prompts（prompt 模板）；
- 每个 MCP server 暴露若干能力，client 连接后可枚举。

类比：MCP 之于 LLM = USB 之于计算机 —— 统一接口，即插即用。

---

## 2. 架构

```
Agent Engine
     ↓
ToolRegistry（统一注册）
     ↓
McpClientManager
     ↓ ↓ ↓ ↓
  GitHub  Filesystem  Slack  Database  ...
  (各自作为子进程或 HTTP server)
```

MCP server 可以是：
- **本地子进程**（stdio）：用户机器上的 server；
- **远程 HTTP/SSE server**：部署在别处；
- **租户内置**：平台预装的（如 filesystem 共享 worktree）。

---

## 3. 数据模型

### 2.1 mcp_servers 表

新增 `V7__mcp.sql`：

```sql
-- 本代码仅作学习材料参考
CREATE TABLE mcp_servers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    scope VARCHAR(32) NOT NULL,         -- tenant / project / session
    name VARCHAR(64) NOT NULL,
    transport VARCHAR(16) NOT NULL,     -- stdio / sse / http
    config JSONB NOT NULL,              -- 启动配置（command/url/env/headers）
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(16) DEFAULT 'disconnected',  -- connected/disconnected/error
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, scope, name)
);

CREATE TABLE mcp_tools (
    id UUID PRIMARY KEY,
    mcp_server_id UUID NOT NULL REFERENCES mcp_servers(id),
    name VARCHAR(128) NOT NULL,
    description TEXT,
    input_schema JSONB NOT NULL,
    discovered_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (mcp_server_id, name)
);
```

### 2.2 配置示例

```json
{
  "name": "github",
  "transport": "stdio",
  "config": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-github"],
    "env": {
      "GITHUB_PERSONAL_ACCESS_TOKEN": "${secrets.github_token}"
    }
  }
}
```

```json
{
  "name": "filesystem",
  "transport": "stdio",
  "config": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"],
    "env": {}
  }
}
```

```json
{
  "name": "playwright",
  "transport": "sse",
  "config": {
    "url": "http://localhost:3001/sse",
    "headers": {
      "Authorization": "Bearer ${secrets.mcp_token}"
    }
  }
}
```

---

## 4. McpClientManager

```java
// 本代码仅作学习材料参考
@Component
public class McpClientManager {

    private final Map<UUID, McpClient> clients = new ConcurrentHashMap<>();
    private final McpServerRepository repo;

    public void connect(UUID serverId) {
        McpServerEntity e = repo.findById(serverId).orElseThrow();
        McpClient client = buildClient(e);
        try {
            client.connect();
            client.initialize();
            client.listTools().forEach(t -> persistTool(serverId, t));
            e.setStatus("connected");
            repo.save(e);
            clients.put(serverId, client);
        } catch (Exception ex) {
            e.setStatus("error");
            e.setLastError(ex.getMessage());
            repo.save(e);
        }
    }

    private McpClient buildClient(McpServerEntity e) {
        return switch (e.getTransport()) {
            case "stdio" -> new StdioMcpClient(e.getConfig());
            case "sse" -> new SseMcpClient(e.getConfig());
            case "http" -> new HttpMcpClient(e.getConfig());
            default -> throw new IllegalArgumentException("unknown transport: " + e.getTransport());
        };
    }

    public McpToolResult callTool(UUID serverId, String toolName, Map<String, Object> input) {
        McpClient client = clients.get(serverId);
        if (client == null) throw new IllegalStateException("MCP server not connected: " + serverId);
        return client.callTool(toolName, input);
    }

    public void disconnect(UUID serverId) {
        McpClient c = clients.remove(serverId);
        if (c != null) c.close();
    }
}
```

---

## 5. 三种 Transport 实现

### 4.1 StdioMcpClient

最常见。MCP server 是一个子进程，通过 stdin/stdout 收发 JSON-RPC。

```java
// 本代码仅作学习材料参考
public class StdioMcpClient implements McpClient {

    private final JsonObject config;
    private Process process;
    private OutputStream stdin;
    private BufferedReader stdout;
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();

    public void connect() throws IOException {
        String command = config.path("command").asText();
        ArrayNode args = (ArrayNode) config.path("args");
        ObjectNode env = (ObjectNode) config.path("env");

        ProcessBuilder pb = new ProcessBuilder(buildCmd(command, args));
        Map<String, String> envMap = pb.environment();
        env.fields().forEachRemaining(e -> envMap.put(e.getKey(), e.getValue().asText()));
        pb.redirectErrorStream(false);
        process = pb.start();
        stdin = process.getOutputStream();
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

        // 读 stdout 的线程
        Thread reader = new Thread(this::readLoop);
        reader.setDaemon(true);
        reader.start();
    }

    public void initialize() throws Exception {
        JsonNode resp = send("initialize", Map.of(
            "protocolVersion", "2024-11-05",
            "clientInfo", Map.of("name", "web-claude", "version", "1.0.0")
        )).get(10, TimeUnit.SECONDS);
    }

    public List<McpToolInfo> listTools() throws Exception {
        JsonNode resp = send("tools/list", Map.of()).get(10, TimeUnit.SECONDS);
        List<McpToolInfo> tools = new ArrayList<>();
        for (JsonNode t : resp.path("tools")) {
            tools.add(new McpToolInfo(
                t.path("name").asText(),
                t.path("description").asText(),
                om.readTree(t.path("inputSchema").toString())
            ));
        }
        return tools;
    }

    public McpToolResult callTool(String name, Map<String, Object> input) {
        try {
            JsonNode resp = send("tools/call", Map.of("name", name, "arguments", input))
                .get(60, TimeUnit.SECONDS);
            return McpToolResult.fromResponse(resp);
        } catch (Exception e) {
            return McpToolResult.error("MCP call failed: " + e.getMessage());
        }
    }

    private CompletableFuture<JsonNode> send(String method, Object params) {
        String id = String.valueOf(idSeq.incrementAndGet());
        ObjectNode req = om.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", om.valueToTree(params));

        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            synchronized (stdin) {
                stdin.write((om.writeValueAsString(req) + "\n").getBytes());
                stdin.flush();
            }
        } catch (IOException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private void readLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                JsonNode msg = om.readTree(line);
                String id = msg.path("id").asText(null);
                if (id == null) continue;  // notification，忽略
                CompletableFuture<JsonNode> f = pending.remove(id);
                if (f == null) continue;
                if (msg.has("error")) {
                    f.completeExceptionally(new McpException(msg.path("error").toString()));
                } else {
                    f.complete(msg.path("result"));
                }
            }
        } catch (IOException ignored) {}
    }

    @Override public void close() {
        if (process != null) process.destroyForcibly();
    }
}
```

### 4.2 SseMcpClient

远程 SSE：长连接 + POST 上行。

```java
// 本代码仅作学习材料参考
public class SseMcpClient implements McpClient {
    private final WebClient http;
    private final String url;
    private final Map<String, String> headers;
    private Flux<String> sseStream;
    private Disposable subscription;
    private String postEndpoint;
    // ... 与 stdio 类似的请求-响应匹配 ...
}
```

### 4.3 HttpMcpClient

streamable HTTP：每次请求发 POST，响应可能是单次 JSON 或 SSE 流。这是 MCP 2025 推荐的新传输。

---

## 6. 与 ToolRegistry 整合

### 5.1 McpToolAdapter

把 MCP 工具包装成项目内的 Tool 接口：

```java
// 本代码仅作学习材料参考
@Component
public class McpToolAdapter implements Tool {

    private final UUID serverId;
    private final McpServerEntity server;
    private final McpToolEntity toolEntity;
    private final McpClientManager manager;

    @Override public String name() {
        // 命名空间化：mcp__github__create_issue
        return "mcp__" + server.getName() + "__" + toolEntity.getName();
    }

    @Override public String description() {
        return "[MCP/" + server.getName() + "] " + toolEntity.getDescription();
    }

    @Override public Kind kind() {
        // MCP 工具的 kind 根据 server 推断
        return switch (server.getName()) {
            case "filesystem" -> Kind.READ;
            case "playwright" -> Kind.EXEC;
            default -> Kind.NETWORK;
        };
    }

    @Override public Map<String, Object> schema() {
        try {
            return om.readValue(toolEntity.getInputSchema().toString(), Map.class);
        } catch (Exception e) { return Map.of(); }
    }

    @Override
    public CompletableFuture<ToolResult> apply(Map<String, Object> input, ToolContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            McpToolResult r = manager.callTool(serverId, toolEntity.getName(), input);
            if (r.isError()) return ToolResult.error(r.error());
            return ToolResult.text(r.content());
        });
    }
}
```

### 5.2 动态注册

MCP server 连接成功后，自动把发现的工具注册到 ToolRegistry：

```java
// 本代码仅作学习材料参考
@EventListener
public void onMcpConnected(McpConnectedEvent event) {
    for (McpToolEntity tool : event.tools()) {
        Tool adapter = new McpToolAdapter(event.server(), tool, manager);
        toolRegistry.registerDynamic(adapter);
    }
}

@EventListener
public void onMcpDisconnected(McpDisconnectedEvent event) {
    toolRegistry.unregisterByPrefix("mcp__" + event.serverName() + "__");
}
```

---

## 7. 工具命名空间

MCP 工具用前缀避免冲突：

```
mcp__<server>__<tool>
```

例：
- `mcp__github__create_issue`
- `mcp__filesystem__read_file`
- `mcp__playwright__screenshot`

模型调用时直接用全名。前端展示（17 章）分组：

```
GitHub MCP:
  - create_issue
  - list_prs
Filesystem MCP:
  - read_file
  - write_file
```

---

## 8. 生命周期管理

### 7.1 启动时

```java
// 本代码仅作学习材料参考
@PostConstruct
public void autoConnectEnabled() {
    repo.findByEnabledTrue().forEach(s -> {
        try { connect(s.getId()); }
        catch (Exception e) { log.warn("auto connect failed: {}", e.getMessage()); }
    });
}
```

### 7.2 健康检查

```java
// 本代码仅作学习材料参考
@Scheduled(fixedRate = 30_000)
public void healthCheck() {
    clients.forEach((id, client) -> {
        if (!client.isAlive()) {
            log.warn("MCP {} died, reconnecting", id);
            connect(id);
        }
    });
}
```

### 7.3 关闭时

```java
// 本代码仅作学习材料参考
@PreDestroy
public void shutdown() {
    clients.keySet().forEach(this::disconnect);
}
```

---

## 9. 错误处理

| 错误类型 | 处理 |
|---------|------|
| 启动失败 | 标 status=error，记录 last_error，UI 显示重试按钮 |
| 调用超时 | 60s 默认超时；超时返回错误，UI 显示 |
| 协议错误（JSON-RPC error） | 透传给 agent，让 agent 决定下一步 |
| 子进程崩溃 | 检测到 stdout EOF → 自动 reconnect（最多 3 次）|
| 网络断（SSE/HTTP） | 重连 + 重新 listTools |

---

## 10. 避坑：MCP 集成常踩的雷

| 雷 | 现象 | 规避 |
|----|------|------|
| stdio 子进程泄漏 | session 结束 server 还在 | 生命周期 §8 严格 close |
| SSE 重连风暴 | 网络抖动疯狂重连 | 指数退避 + 上限 |
| 命名空间冲突 | 两个 server 都叫 `read` | §7 强制前缀 `github__read` |
| 不可信 MCP server | 用户配恶意 server | 沙箱化 + 权限审批（首次必须 ASK）|
| 大 tool result 卡死 | 单工具返回 10MB | 复用 14 章 ToolResultBudget |
| Server 调用阻塞 | http server 挂了主流程卡 | 所有调用加 timeout |
| 协议版本不匹配 | 新 server 用 v2 老客户端不识别 | 启动时 negotiate version |
| 凭据明文配置 | YAML 里写 PAT | 走 secret store + 注入环境变量 |
| 工具 schema 漂移 | server 升级后参数变了 | 启动时缓存 schema + 失效检测 |
| Server 进程 OOM | server 跑大任务挂 | 监控 + 自动重启 + 上报事件 |

## 11. 安全

### 9.1 命令注入防护

stdio 配置里的 `command` 必须是白名单（不允许 `rm`/`bash` 等）：

```java
// 本代码仅作学习材料参考
private static final Set<String> ALLOWED = Set.of("npx", "node", "python", "python3", "docker");

if (!ALLOWED.contains(command)) {
    throw new SecurityException("command not allowed: " + command);
}
```

### 9.2 环境变量隔离

子进程默认只继承 `PATH`，其他必须显式声明。`env` 中的 `${secrets.xxx}` 通过 vault 解析。

### 9.3 工作目录

stdio server 的工作目录强制是沙箱内 `/workspace`，不允许访问外部。

### 9.4 权限

每个 MCP 工具仍然走 PermissionMiddleware（05 章）。租户管理员可以配置规则：

```
mcp__github__*(*) ALLOW  # 允许所有 github 工具
mcp__*__delete_*(*) DENY  # 禁止所有"删除"类
```

### 9.5 资源限制

- 单 server 内存上限 256MB（cgroup）；
- 调用频率限制（rate limit）；
- 输出大小限制（避免吃光 context）。

---

## 12. 可观测性（接 17 章）

每次 MCP 调用都产生事件：

```json
{
  "type": "mcp_call",
  "toolCallId": "tc_002",
  "server": "github",
  "method": "tools/call",
  "toolName": "create_issue",
  "input": {...},
  "at": "..."
}

{
  "type": "mcp_result",
  "toolCallId": "tc_002",
  "durationMs": 453,
  "isError": false,
  "contentPreview": "...",
  "at": "..."
}

{
  "type": "mcp_server_status",
  "server": "github",
  "status": "connected",
  "toolsAvailable": ["create_issue", "list_prs", ...]
}
```

前端专门有"MCP Servers"面板，展示连接状态、工具列表、最近调用。

---

## 13. 配置 UI

租户管理员页面：

```
┌─ MCP Servers ────────────────────────────────┐
│ [+ 添加 Server]                              │
│                                              │
│  ● github         stdio    12 tools    [管理]│
│  ● filesystem     stdio     5 tools    [管理]│
│  ● playwright     sse       8 tools    [管理]│
│  ○ slack          stdio    disconnected [管理]│
└──────────────────────────────────────────────┘
```

添加表单：
- name
- transport（stdio / sse / http）
- command / url
- args
- env（含 secret 引用）
- scope（tenant / project / session）

---

## 14. 验证：测试

### 12.1 用 mock MCP server

写一个 echo server：

```python
# 本代码仅作学习材料参考
# docker/mcp-mock/echo_server.py
from mcp.server import Server
app = Server("echo")

@app.tool("echo")
def echo(text: str):
    return {"content": [{"type": "text", "text": f"echo: {text}"}]}
```

测试：

```java
// 本代码仅作学习材料参考
@Test
void echoToolRoundTrip() {
    UUID id = registerStdio("echo", "python", "echo_server.py");
    manager.connect(id);
    McpToolResult r = manager.callTool(id, "echo", Map.of("text", "hi"));
    assertThat(r.content()).contains("echo: hi");
}
```

### 12.2 故障注入

- 杀子进程 → 自动重连；
- 调用慢工具 → 超时；
- 配置非法 command → 拒绝。

---

## 15. 与已有章节的关系

| 章节 | 关系 |
|------|------|
| 05-工具系统 | MCP 工具通过 Adapter 加入 ToolRegistry |
| 05-权限 | MCP 工具同样过 PermissionMiddleware |
| 06-沙箱 | stdio server 跑在沙箱内 |
| 10-集成ai-serving | MCP server 可作为模型代理网关的另一种入口 |
| 17-全链路可观测 | mcp_call / mcp_result 事件 |
| 18-错误恢复 | MCP 调用错误的重试策略 |

---

## 16. 本章产出

```
后端：
  ✅ mcp_servers / mcp_tools 表
  ✅ McpClientManager + 3 种 transport
  ✅ McpToolAdapter（注册到 ToolRegistry）
  ✅ 健康检查 + 自动重连
  ✅ 命令白名单 + secret 解析
  ✅ 配额与权限接入

前端：
  ✅ MCP Servers 管理面板
  ✅ MCP 工具调用事件渲染
```

## 15. v2 路线

- MCP resources（资源读取）；
- MCP prompts（prompt 模板）；
- 跨租户共享的 MCP server 池；
- MCP server marketplace；
- 流式 MCP 工具（部分工具支持流式返回）。

---

## 16. 下一步

进入 [17-全链路可观测前端](./17-全链路可观测前端.md)，把 Agent 内部所有事件暴露给用户。
