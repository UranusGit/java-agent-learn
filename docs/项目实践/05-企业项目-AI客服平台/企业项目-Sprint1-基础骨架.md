# Sprint 1 详细实现：基础骨架 + 单轮对话

> 目标：Spring Boot 项目跑起来，调通 LLM，有基础聊天界面
> 时间：2 周 · 前置：阶段 0-1 教程完成

---

## Day 1-2：项目初始化

### Step 1：创建 Maven 项目

```xml
<!-- pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.10</version>
    </parent>

    <groupId>com.agentforge</groupId>
    <artifactId>agent-forge</artifactId>
    <version>0.1.0-SNAPSHOT</version>

    <properties>
        <java.version>21</java.version>
        <spring-ai.version>1.0.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring AI - OpenAI (DeepSeek 兼容) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>

        <!-- WebFlux（流式 SSE 需要） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Redis（会话存储） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### Step 2：创建启动类

```java
package com.agentforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentForgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentForgeApplication.class, args);
    }
}
```

### Step 3：配置文件

```yaml
# application.yml
spring:
  application:
    name: agent-forge

  ai:
    openai:
      base-url: ${LLM_BASE_URL:https://api.deepseek.com}
      api-key: ${LLM_API_KEY:${DEEPSEEK_API_KEY}}
      chat:
        model: ${LLM_MODEL:deepseek-chat}
        temperature: 0.7
        timeout: 60s

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

server:
  port: 8080

logging:
  level:
    com.agentforge: DEBUG
    org.springframework.ai: DEBUG
```

### Step 4：验证启动

```bash
mvn spring-boot:run
# 看到 "Started AgentForgeApplication" 说明成功
```

---

## Day 3-4：单轮对话接口

### Step 5：ChatClient 配置

```java
package com.agentforge.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                    你是 AgentForge 智能客服助手。
                    你的职责是帮助企业客户解答技术问题、处理工单。
                    回答要准确、简洁、有礼貌。
                    如果不确定，坦诚告知并建议联系人工客服。
                    """)
                .build();
    }
}
```

### Step 6：聊天 Controller

```java
package com.agentforge.controller;

import com.agentforge.entity.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 单轮对话（Sprint 1 基础版）
     */
    @GetMapping("/ask")
    public ResponseEntity<ApiResponse> ask(@RequestParam String q) {
        // 输入校验
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("问题不能为空"));
        }
        if (q.length() > 2000) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("问题太长，请限制在 2000 字以内"));
        }

        try {
            String reply = chatClient.prompt()
                    .user(q)
                    .call()
                    .content();

            return ResponseEntity.ok(ApiResponse.ok(reply));

        } catch (Exception e) {
            log.error("LLM 调用失败", e);
            return ResponseEntity.status(503)
                    .body(ApiResponse.error("AI 服务暂时不可用，请稍后重试"));
        }
    }
}
```

### Step 7：统一响应格式

```java
package com.agentforge.entity;

public record ApiResponse(
        boolean success,
        String message,
        Object data,
        long timestamp
) {
    public static ApiResponse ok(Object data) {
        return new ApiResponse(true, "ok", data, System.currentTimeMillis());
    }

    public static ApiResponse error(String message) {
        return new ApiResponse(false, message, null, System.currentTimeMillis());
    }
}
```

### Step 8：测试

```bash
curl "http://localhost:8080/api/chat/ask?q=你好"
# {"success":true,"message":"ok","data":"你好！我是AgentForge智能客服...","timestamp":...}

curl "http://localhost:8080/api/chat/ask?q="
# {"success":false,"message":"问题不能为空","data":null,"timestamp":...}
```

---

## Day 5-7：前端聊天界面

### Step 9：创建聊天页面

创建 `src/main/resources/static/index.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AgentForge 智能客服</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh; display: flex; justify-content: center; align-items: center;
        }
        .chat-app {
            width: 100%; max-width: 900px; height: 80vh;
            background: white; border-radius: 20px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
            display: flex; flex-direction: column; overflow: hidden;
        }
        .header {
            background: linear-gradient(135deg, #667eea, #764ba2);
            color: white; padding: 20px; text-align: center;
        }
        .header h1 { font-size: 20px; margin-bottom: 4px; }
        .header p { font-size: 13px; opacity: 0.8; }
        .messages {
            flex: 1; overflow-y: auto; padding: 20px;
            display: flex; flex-direction: column; gap: 16px;
        }
        .msg { display: flex; gap: 12px; max-width: 80%; }
        .msg.user { align-self: flex-end; flex-direction: row-reverse; }
        .msg .avatar {
            width: 36px; height: 36px; border-radius: 50%;
            display: flex; align-items: center; justify-content: center;
            font-size: 18px; flex-shrink: 0;
        }
        .msg.ai .avatar { background: #667eea; }
        .msg.user .avatar { background: #764ba2; }
        .msg .bubble {
            padding: 12px 16px; border-radius: 16px;
            line-height: 1.6; font-size: 14px; word-break: break-word;
        }
        .msg.ai .bubble { background: #f0f2f5; color: #333; }
        .msg.user .bubble { background: #667eea; color: white; }
        .input-bar {
            padding: 16px 20px; border-top: 1px solid #eee;
            display: flex; gap: 12px; background: #fafafa;
        }
        .input-bar input {
            flex: 1; padding: 12px 16px; border: 2px solid #e0e0e0;
            border-radius: 24px; font-size: 15px; outline: none;
            transition: border-color 0.2s;
        }
        .input-bar input:focus { border-color: #667eea; }
        .input-bar button {
            padding: 12px 28px; background: linear-gradient(135deg, #667eea, #764ba2);
            color: white; border: none; border-radius: 24px;
            font-size: 15px; cursor: pointer; transition: opacity 0.2s;
        }
        .input-bar button:hover { opacity: 0.9; }
        .input-bar button:disabled { opacity: 0.5; cursor: not-allowed; }
        .typing { display: inline-block; width: 8px; height: 14px;
                  background: #667eea; animation: blink 0.8s infinite; }
        @keyframes blink { 50% { opacity: 0; } }
    </style>
</head>
<body>
    <div class="chat-app">
        <div class="header">
            <h1>🤖 AgentForge 智能客服</h1>
            <p>Powered by Spring AI</p>
        </div>
        <div class="messages" id="messages">
            <div class="msg ai">
                <div class="avatar">🤖</div>
                <div class="bubble">你好！我是 AgentForge 智能客服。有什么可以帮你的？</div>
            </div>
        </div>
        <div class="input-bar">
            <input id="input" placeholder="输入你的问题..." autocomplete="off"
                   onkeypress="if(event.key==='Enter')send()">
            <button id="btn" onclick="send()">发送</button>
        </div>
    </div>

    <script>
        async function send() {
            const input = document.getElementById('input');
            const btn = document.getElementById('btn');
            const messages = document.getElementById('messages');
            const q = input.value.trim();
            if (!q) return;

            // 显示用户消息
            messages.innerHTML += `
                <div class="msg user">
                    <div class="avatar">👤</div>
                    <div class="bubble">${escapeHtml(q)}</div>
                </div>`;

            // 显示加载动画
            const loadingId = 'loading-' + Date.now();
            messages.innerHTML += `
                <div class="msg ai" id="${loadingId}">
                    <div class="avatar">🤖</div>
                    <div class="bubble"><span class="typing"></span></div>
                </div>`;
            scrollDown();
            input.value = '';
            btn.disabled = true;

            try {
                const resp = await fetch(`/api/chat/ask?q=${encodeURIComponent(q)}`);
                const data = await resp.json();

                // 移除加载动画
                document.getElementById(loadingId)?.remove();

                if (data.success) {
                    messages.innerHTML += `
                        <div class="msg ai">
                            <div class="avatar">🤖</div>
                            <div class="bubble">${escapeHtml(data.data)}</div>
                        </div>`;
                } else {
                    messages.innerHTML += `
                        <div class="msg ai">
                            <div class="avatar">🤖</div>
                            <div class="bubble" style="color:#e53e3e">⚠️ ${escapeHtml(data.message)}</div>
                        </div>`;
                }
            } catch (e) {
                document.getElementById(loadingId)?.remove();
                messages.innerHTML += `
                    <div class="msg ai">
                        <div class="avatar">🤖</div>
                        <div class="bubble" style="color:#e53e3e">⚠️ 网络错误，请稍后重试</div>
                    </div>`;
            }

            btn.disabled = false;
            scrollDown();
        }

        function scrollDown() {
            const m = document.getElementById('messages');
            m.scrollTop = m.scrollHeight;
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }
    </script>
</body>
</html>
```

### Step 10：访问测试

```bash
mvn spring-boot:run
# 打开 http://localhost:8080/
# 输入问题，能看到 AI 回复
```

---

## Day 8-10：Git 提交 + 健康检查 + 文档

### Step 11：健康检查接口

```java
package com.agentforge.controller;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "service", "AgentForge",
            "version", "0.1.0",
            "timestamp", System.currentTimeMillis()
        );
    }
}
```

### Step 12：Git 提交

```bash
git add -A
git commit -m "feat(sprint1): 项目初始化 + 单轮对话 + 前端界面

- Spring Boot 3 + Spring AI 项目骨架
- ChatClient 配置 + 客服 System Prompt
- /api/chat/ask 单轮对话接口
- 前端聊天界面（HTML/CSS/JS）
- 健康检查接口
- 统一 ApiResponse 格式

Co-Authored-By: Claude <noreply@anthropic.com>"
```

### Step 13：写 Sprint 1 文档

创建 `docs/README.md`：
- 项目简介
- 技术栈
- 快速启动指南
- Sprint 1 产出清单

---

## Sprint 1 验收

- [ ] `mvn spring-boot:run` 能正常启动
- [ ] `curl http://localhost:8080/api/health` 返回 UP
- [ ] `curl http://localhost:8080/api/chat/ask?q=你好` 返回 AI 回复
- [ ] 浏览器 `http://localhost:8080/` 能聊天
- [ ] 空输入/超长输入有错误处理
- [ ] LLM 出错有 503 降级
- [ ] Git 有提交记录

---

## 下一步

→ [Sprint 2：多轮对话 + 记忆 + 流式](企业项目-Sprint2-多轮流式.md)
