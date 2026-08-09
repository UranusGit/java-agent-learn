# CodeForge Sprint 1：Agent 循环 + 文件工具 + Web IDE

> 目标：Agent 能读写文件、搜索代码，用自然语言完成简单编程任务
> 时间：2 周

---

## Day 1-3：ToolRegistry 统一工具注册表

### 核心设计思想（借鉴 Claude Code ch14）

> **所有能力遵循同一接口时，系统可以在工具层面施加统一的横切关注点**——权限控制、并发管理、输入验证、结果缓存——而不需要每个工具自己操心。

### Step 1：统一工具接口

```java
package com.codeforge.tool;

/**
 * 所有工具的统一接口（借鉴 Claude Code 的 Tool 类型）
 * 每个工具是自描述的能力单元。
 */
public interface Tool {

    /** 工具名称（LLM 看到的） */
    String name();

    /** 工具描述（LLM 靠它判断何时调用） */
    String description();

    /** 是否只读（只读工具可安全并发） */
    default boolean isReadOnly() { return false; }

    /** 是否危险（危险操作需要确认） */
    default boolean isDangerous() { return false; }

    /** 是否启用（条件注册） */
    default boolean isEnabled() { return true; }
}
```

### Step 2：ToolRegistry（声明式注册 + 条件加载）

```java
package com.codeforge.tool;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ToolRegistry {

    private final List<Tool> allTools;
    private final Map<String, Tool> toolMap = new HashMap<>();

    public ToolRegistry(List<Tool> tools) {
        this.allTools = tools;
        tools.forEach(t -> toolMap.put(t.name(), t));
    }

    /**
     * 获取所有启用的工具对象（注册到 ChatClient）
     */
    public Object[] getEnabledTools() {
        return allTools.stream()
                .filter(Tool::isEnabled)
                .toArray();
    }

    /**
     * 按权限获取工具（条件注册——借鉴 Claude Code 的 feature gate）
     */
    public Object[] getToolsForMode(PermissionModel mode) {
        return allTools.stream()
                .filter(Tool::isEnabled)
                .filter(t -> mode.allows(t))
                .toArray();
    }

    public Tool getTool(String name) { return toolMap.get(name); }
}
```

### Step 3：FileReadTool（借鉴 Claude Code ch15）

```java
package com.codeforge.tool.file;

import com.codeforge.tool.Tool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.stream.Collectors;

@Component
public class FileReadTool implements Tool {

    @Override public String name() { return "read_file"; }
    @Override public String description() { return "读取文件内容"; }
    @Override public boolean isReadOnly() { return true; }

    @Tool(description = "读取指定文件的内容。path 是文件路径（相对于项目根目录）。"
         + "大文件会自动截断到前 2000 行。")
    public String readFile(String path) {
        // 安全校验：路径必须在项目目录内
        Path resolved = validatePath(path);

        if (!Files.exists(resolved)) {
            return "⚠️ 文件不存在：" + path;
        }
        if (Files.isDirectory(resolved)) {
            return "⚠️ 这是一个目录，不是文件：" + path;
        }

        try {
            List<String> lines = Files.readAllLines(resolved);
            // 大文件截断（借鉴 Claude Code 的 applyToolResultBudget）
            if (lines.size() > 2000) {
                return lines.subList(0, 2000).stream()
                        .collect(Collectors.joining("\n"))
                        + "\n\n... (截断，共 " + lines.size() + " 行，只显示前 2000 行)";
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return "⚠️ 读取失败：" + e.getMessage();
        }
    }

    private Path validatePath(String path) {
        // 防止路径遍历
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path resolved = projectRoot.resolve(path).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new SecurityException("路径越界：" + path);
        }
        return resolved;
    }
}
```

### Step 4：FileEditTool（精确字符串替换——借鉴 Claude Code ch15）

```java
@Component
public class FileEditTool implements Tool {

    @Override public String name() { return "edit_file"; }
    @Override public boolean isReadOnly() { return false; }
    @Override public boolean isDangerous() { return true; }  // 写操作

    /**
     * 精确字符串替换（不是行号编辑）。
     * 借鉴 Claude Code FileEditTool 的设计哲学：
     * 精确匹配比行号更可靠，因为行号会随着编辑而漂移。
     */
    @Tool(description = "编辑文件：用新内容替换文件中的指定字符串。"
         + "path 是文件路径，oldString 是要被替换的精确文本，"
         + "newString 是替换后的新文本。oldString 必须在文件中唯一存在。")
    public String editFile(String path, String oldString, String newString) {
        Path resolved = validatePath(path);

        if (!Files.exists(resolved)) return "⚠️ 文件不存在";
        try {
            String content = Files.readString(resolved);

            // 检查唯一性
            int count = countOccurrences(content, oldString);
            if (count == 0) return "⚠️ 未找到要替换的文本。请检查 oldString 是否精确匹配文件内容。";
            if (count > 1) return "⚠️ 找到 " + count + " 处匹配。请提供更多上下文使其唯一。";

            // 执行替换
            String newContent = content.replace(oldString, newString);
            Files.writeString(resolved, newContent);

            return "✅ 已修改 " + path;
        } catch (Exception e) {
            return "⚠️ 修改失败：" + e.getMessage();
        }
    }
    // ... validatePath, countOccurrences
}
```

### Step 5：GrepTool（代码搜索——借鉴 Claude Code ch17）

```java
@Component
public class GrepTool implements Tool {

    @Override public String name() { return "grep"; }
    @Override public boolean isReadOnly() { return true; }

    @Tool(description = "在项目中搜索代码内容。pattern 是搜索关键词（支持正则）。"
         + "返回匹配的文件名和行内容。")
    public String grep(String pattern) {
        try {
            // 用 Java 的 Files.walk + 正则匹配
            Path root = Path.of(System.getProperty("user.dir"));
            var matches = new ArrayList<String>();

            Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java") ||
                             p.toString().endsWith(".xml") ||
                             p.toString().endsWith(".yml"))
                .forEach(file -> {
                    try {
                        var lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).contains(pattern)) {
                                matches.add(root.relativize(file) + ":" + (i+1) + ": " + lines.get(i).trim());
                            }
                        }
                    } catch (Exception ignored) {}
                });

            if (matches.isEmpty()) return "没有找到匹配 '" + pattern + "' 的内容";
            if (matches.size() > 50) {
                return matches.subList(0, 50).stream().collect(Collectors.joining("\n"))
                     + "\n... (共 " + matches.size() + " 条匹配，只显示前 50 条)";
            }
            return String.join("\n", matches);
        } catch (Exception e) {
            return "⚠️ 搜索失败：" + e.getMessage();
        }
    }
}
```

---

## Day 4-7：AgentLoop 核心循环

### Step 6：AgentLoop（借鉴 Claude Code ch5）

```java
package com.codeforge.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AgentLoop {

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final PermissionModel permissionModel;

    /**
     * 执行一个完整的 Agent 任务。
     * Spring AI 的 ToolCallingAdvisor 自动管理循环（decide-act-observe）。
     * 我们只需要配置好工具和保护机制。
     */
    public String execute(String instruction, String sessionId) {
        // 获取当前权限模式下允许的工具
        var tools = toolRegistry.getToolsForMode(permissionModel);

        return chatClient.prompt()
                .system("""
                    你是一个代码助手。你在一个 Java 项目中工作。
                    按照用户指令，逐步使用工具完成任务。
                    规则：
                    1. 每次只执行一步，观察结果后再决定下一步
                    2. 修改文件前先用 read_file 读取当前内容
                    3. 任务完成后用自然语言汇总你做了什么
                    """)
                .user(instruction)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .tools(tools)
                .call()
                .content();
    }
}
```

### Step 7：三重保护配置

```java
@Configuration
public class AgentConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  ChatMemory memory,
                                  InputFilterAdvisor inputFilter,
                                  TokenBillingAdvisor billing,
                                  BudgetGuardAdvisor budgetGuard,
                                  LoopDetectionAdvisor loopDetection,
                                  AuditAdvisor audit) {
        return builder
                .defaultAdvisors(
                    inputFilter,             // -100: 输入过滤
                    MessageChatMemoryAdvisor.builder(memory).build(),  // 0
                    budgetGuard,             // 3: 预算保护
                    loopDetection,           // 4: 死循环检测
                    billing,                 // 10: 计费
                    audit                    // 300: 审计
                )
                .build();
    }
}
```

---

## Day 8-10：SSE 流式 + Web IDE

### Step 8：SSE 流式通信

> **技术选型**：使用 SSE（Server-Sent Events）而非 WebSocket。
> Agent 对话是"用户 POST 一条消息 → 服务端流式返回 Token"的单向流，SSE 是企业级 AI 对话的标准选型——原生 EventSource 自动重连，兼容 HTTP 基础设施。

```java
@RestController
@RequestMapping("/api/agent")
public class AgentSseController {

    private final AgentLoop agentLoop;

    /**
     * SSE 流式输出 Agent 回复
     * 前端使用 EventSource 订阅
     */
    @PostMapping(value = "/{sessionId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> request) {

        String instruction = request.get("instruction");

        return agentLoop.executeStream(instruction, sessionId)
            .map(chunk -> ServerSentEvent.<String>builder()
                .id(sessionId)
                .event("chunk")
                .data(chunk)
                .build())
            .concatWith(Flux.just(ServerSentEvent.<String>builder()
                .event("done")
                .data("[DONE]")
                .build()));
    }
}
```

**前端 EventSource 示例**：
```javascript
// SSE 订阅 Agent 流式输出
const eventSource = new EventSource(`/api/agent/${sessionId}/stream`);

eventSource.addEventListener("chunk", (event) => {
    appendToLog(event.data);  // 流式追加到日志面板
});

eventSource.addEventListener("done", (event) => {
    eventSource.close();  // Agent 完成
});

eventSource.onerror = () => {
    // SSE 原生自动重连——无需手动处理
};
```

### Step 9：Web IDE 前端骨架

```html
<!-- 简化的 Web IDE 布局 -->
<div class="ide-layout">
    <div class="sidebar"><!-- 文件树 --></div>
    <div class="editor-area">
        <div id="monaco-editor"></div>  <!-- Monaco Editor -->
    </div>
    <div class="chat-panel">
        <div id="agent-log"><!-- Agent 执行日志流式显示 --></div>
        <div class="confirm-dialog" id="confirm" style="display:none">
            <!-- 危险操作确认对话框 -->
            <p>Agent 想要执行：<span id="confirm-action"></span></p>
            <button onclick="approve()">允许</button>
            <button onclick="deny()">拒绝</button>
        </div>
        <input id="instruction" placeholder="描述你的任务...">
        <button onclick="sendInstruction()">执行</button>
    </div>
</div>
```

---

## Sprint 1 验收

- [ ] `ToolRegistry` 能统一管理所有工具
- [ ] Agent 能读取文件、搜索代码、编辑文件
- [ ] 工具错误返回信息（不崩溃）
- [ ] SSE 流式输出正常
- [ ] 路径遍历被拦截
- [ ] Agent 有三重保护

---

## 下一步

→ [Sprint 2：安全 Shell + 权限模型](Sprint2-Shell权限.md)
