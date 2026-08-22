# 项目 04：React Agent 控制台 — 01-最小 Demo 搭建

> **定位**：从零搭起参考后端 + agent-console 前端两套工程，跑通第一条"输入 → SSE → 打字机输出"链路。本篇只有单会话、无历史、无工具可视化——**刻意保持最小**，为后续迭代留足演进空间。本文给出**完整可手写代码**（一行不省略）。
>
> 「遇到阻塞？→ [教程 15-React入门与现代前端工程 §4 Vite 工程]、[教程 17-React与SSE流式UI §1 fetch+ReadableStream]；API 真实性以 [附录 05-SpringAI2-API基准] 为准」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 一条能跑的流式对话链路：输入消息 → 后端调 LLM → 前端逐 token 打字机输出、可取消、失败可重试 |
| **影响了哪些模块** | 全部（这是地基，无历史包袱） |
| **架构如何演进** | 参考后端：Controller → `ChatClient.stream()` → SSE；前端：`services/sse.ts`（fetch+RS）→ `useChatStream`（useState）→ 组件 |
| **上一版痛点是什么** | 无（v0 是起点，痛点是**将要暴露的**） |

### 1.1 本节核对（四问）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 能说清 v1 的链路形态 | 输入 → POST /api/chat → SSE token 流 → 前端打字机输出 |
| 2 | 能说清"刻意不做"清单 | 会话持久化 / 多会话 / 工具可视化 / 断线恢复 / 用量展示，五项均不在 v1 |

## 2. 目标与量化验收

| # | 目标 | 验收 |
|---|------|------|
| 1 | 后端可启动 | `mvn spring-boot:run` 后 `curl` POST `/api/chat` 返回 SSE 事件流 |
| 2 | 前端可启动 | `npm run dev` 打开页面无编译错误 |
| 3 | 流式可见 | 发送"你好"，打字机逐帧出现（成组 token，非每字符一次） |
| 4 | 取消生效 | 流式中点"停止"，输出立即定格，无错误横幅 |
| 5 | 无乱码 | 发送含长中文的问题，输出完整无乱码（`TextDecoder stream:true` 生效） |

**本迭代明确不做**：会话持久化、多会话、工具可视化、断线恢复、用量展示。

### 2.1 本节核对（目标与量化验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 五条验收口径可度量 | 每条都有可观察结果（curl 返回 / 编译无错 / 打字机逐帧 / 定格无横幅 / 无乱码） |
| 2 | 验收只覆盖 v1 范围 | 五条均未涉及"明确不做"清单中的能力 |

---

## 3. 工程初始化

```bash
# 参考后端（Maven 工程，pom.xml / application.yml 见 00 §6.1-6.2）
mkdir agent-console-backend && cd agent-console-backend

# 前端（Vite 工程）
cd ..
npm create vite@latest agent-console -- --template react-ts
cd agent-console
npm install
npm install react-router-dom zustand @tanstack/react-query   # v1 只用到 react-router-dom，其余为后续迭代预装
npm run dev
```

v1 前端目录结构（随迭代膨胀）：

```
agent-console/src/
├── main.tsx
├── App.tsx
├── components/
│   ├── ChatInput.tsx
│   ├── MessageList.tsx
│   ├── LiveOutput.tsx
│   └── ErrorBanner.tsx
├── hooks/
│   └── useChatStream.ts      # 本篇主角
├── services/
│   └── sse.ts                # fetch+RS+parseSSE 封装
└── types/
    └── events.ts             # 事件契约（v1 只用到 token/round_end/error）
```

参考后端目录结构：

```
agent-console-backend/src/main/java/com/agent/console/
├── AgentConsoleApplication.java
├── config/AgentConfig.java
└── web/ChatController.java
```

### 3.1 本节测试与验证（工程初始化）

> **前置**：本机已装 Node ≥ 18 与 JDK 21；Maven 可用。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 执行 `npm create vite@latest agent-console -- --template react-ts` 后 `npm install` | 依赖安装无 error，`npm run dev` 能启动 |
| 2 | 目录结构与本节两棵树对照 | 前端 src 下 components/hooks/services/types 四目录、后端 com/agent/console 三类文件就位（types/events.ts 此时尚未创建，属 §4 内容） |
| 3 | `npm install react-router-dom zustand @tanstack/react-query` | package.json dependencies 出现三个包且版本号为正式版（无 ^ 脱失） |

**失败排查**：npm create 卡住 → 网络/registry 问题，换镜像源；目录树对不上 → create vite 模板版本变化，以 `react-ts` 模板实际产物为准调整。

---

## 4. 完整代码（照抄即可，一行不省略）

### 4.1 参考后端：启动类

```java
package com.agent.console;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentConsoleApplication.class, args);
    }
}
```

### 4.2 参考后端：配置类（ChatClient）

```java
package com.agent.console.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是企业内部知识助手，用中文简洁、准确地回答用户问题。")
                .build();
    }
}
```

> 说明：v1 不接记忆与工具（刻意最小）。迭代一给 ChatClient 挂 `MessageChatMemoryAdvisor`（[附录 05-00 §2]），迭代二注册 `@Tool`（[附录 05-02 §1]）。

### 4.3 参考后端：SSE Controller（v1 最小版）

```java
package com.agent.console.web;

import com.agent.console.event.AgentEvent;
import com.agent.console.event.ErrorEvent;
import com.agent.console.event.RoundEnd;
import com.agent.console.event.Token;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // POST /api/chat —— 流式对话（SSE）。事件数据是 AgentEvent 的 JSON（契约见 00 §6.5）
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> chat(@RequestBody ChatRequest request) {
        AtomicLong seq = new AtomicLong(0);
        String roundId = "r-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        return chatClient.prompt()
                .user(request.message())
                .stream()
                .content()                                   // Flux<String>：逐 token 增量
                .filter(text -> text != null && !text.isEmpty())
                .map(text -> wrap(new Token(seq.incrementAndGet(), System.currentTimeMillis(), text)))
                .concatWith(Mono.just(wrap(new RoundEnd(
                        seq.incrementAndGet(), System.currentTimeMillis(), roundId,
                        new com.agent.console.event.TokenUsage(0, 0), "stop"))))
                .onErrorResume(e -> Mono.just(wrap(new ErrorEvent(
                        seq.incrementAndGet(), System.currentTimeMillis(),
                        "AGENT_ERROR", e.getMessage(), true))));
    }

    private ServerSentEvent<AgentEvent> wrap(AgentEvent event) {
        return ServerSentEvent.<AgentEvent>builder()
                .id(String.valueOf(event.id()))              // SSE 层 id = 事件 id
                .data(event)                                 // 序列化为 JSON（Jackson）
                .build();
    }

    public record ChatRequest(String sessionId, String message) {}
}
```

> ⚠️ 上面的 `ChatController` 用到了 `AgentEvent`/`Token`/`RoundEnd`/`ErrorEvent`——这些类在 [00 §6.5](00-需求分析与架构设计.md#65-事件契约前后端共同的单一来源) 中完整定义，v1 只用其中三种事件。`TokenUsage` 是顶层 record，import 可用 `com.agent.console.event.TokenUsage`。v1 的 usage 是占位 0，迭代三接入真实计量。

### 4.4 前端：事件类型（v1 最小契约）

```ts
// types/events.ts —— v1 只声明用到的事件；完整协议见 00 §6.5，迭代二引入
export type AgentEvent =
  | { id: number; ts: number; type: 'token'; content: string }
  | { id: number; ts: number; type: 'round_end'; roundId: string; usage: { promptTokens: number; completionTokens: number }; finishReason: string }
  | { id: number; ts: number; type: 'error'; code: string; message: string; recoverable: boolean };
```

### 4.5 前端：SSE 消费封装（完整）

```ts
// services/sse.ts —— fetch + ReadableStream 消费 SSE（教程 17 §1.2-1.3，完整版）
import type { AgentEvent } from '../types/events';

export interface StreamHandlers {
  onEvent: (event: AgentEvent) => void;
  onDone: () => void;
  onError: (error: Error) => void;
}

interface ParsedEvents {
  parsed: AgentEvent[];
  remainder: string;
}

export async function streamChat(
  request: { sessionId: string; message: string },
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
    },
    body: JSON.stringify(request),
    signal,
  });

  if (!response.ok || !response.body) {
    throw new Error(`HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');   // stream:true 处理跨 chunk 多字节中文
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const events = parseSSE(buffer);
    buffer = events.remainder;                // 半条事件留到下一个 chunk

    for (const event of events.parsed) {
      handlers.onEvent(event);
    }
  }
  handlers.onDone();
}

export function parseSSE(buffer: string): ParsedEvents {
  const parsed: AgentEvent[] = [];
  // SSE 协议：事件以空行分隔；每行 field: value
  const frames = buffer.split('\n\n');
  const remainder = frames.pop() ?? '';       // 最后一段可能不完整，留待下个 chunk

  for (const frame of frames) {
    let data = '';
    for (const line of frame.split('\n')) {
      if (line.startsWith(':')) continue;                 // 心跳注释行（教程 10 §10.3）
      if (line.startsWith('data:')) data += line.slice(5).trim();
    }
    if (data) {
      try {
        parsed.push(JSON.parse(data) as AgentEvent);
      } catch {
        // 心跳/非 JSON 数据忽略——流不能因一帧坏数据整体崩溃
      }
    }
  }
  return { parsed, remainder };
}
```

### 4.6 前端：useChatStream Hook（v1 完整版）

```ts
// hooks/useChatStream.ts —— v1：本地 useState 版；迭代一升级为 reducer（教程 16 §2.2）
import { useCallback, useEffect, useRef, useState } from 'react';
import { streamChat } from '../services/sse';
import type { AgentEvent } from '../types/events';

type Status = 'idle' | 'streaming' | 'done' | 'error';

export function useChatStream() {
  const [text, setText] = useState('');
  const [status, setStatus] = useState<Status>('idle');
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // rAF 批量缓冲：token 先进 ref，每帧最多一次渲染（教程 17 §4.2）
  const pendingRef = useRef<string[]>([]);
  const rafRef = useRef<number | null>(null);

  const flush = useCallback(() => {
    if (rafRef.current !== null) { cancelAnimationFrame(rafRef.current); rafRef.current = null; }
    const chunk = pendingRef.current.join('');
    pendingRef.current = [];
    if (chunk) setText(prev => prev + chunk);
  }, []);

  const handleToken = useCallback((content: string) => {
    pendingRef.current.push(content);
    if (rafRef.current === null) {
      rafRef.current = requestAnimationFrame(flush);
    }
  }, [flush]);

  const handleEvent = useCallback((e: AgentEvent) => {
    switch (e.type) {
      case 'token':
        handleToken(e.content);
        break;
      case 'round_end':
        flush();
        setStatus('done');
        break;
      case 'error':
        flush();
        setStatus('error');
        setError(e.message);
        break;
    }
  }, [handleToken, flush]);

  const send = useCallback(async (message: string) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setText(''); setStatus('streaming'); setError(null);

    try {
      await streamChat(
        { sessionId: 'demo', message },
        {
          onEvent: handleEvent,
          onDone: () => flush(),
          onError: err => {
            if (err.name === 'AbortError') return;   // 主动取消不是错误（教程 17 §2）
            flush(); setStatus('error'); setError(err.message);
          },
        },
        controller.signal,
      );
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
    }
  }, [handleEvent, flush]);

  // 卸载清理纪律：abort 连接 + 取消 rAF（教程 17 §5.1）
  useEffect(() => () => { abortRef.current?.abort(); flush(); }, [flush]);

  return { text, status, error, send, cancel: () => abortRef.current?.abort() };
}
```

### 4.7 前端：组件接线（完整）

```tsx
// components/ChatInput.tsx —— 受控输入 + 流式中可取消
import { useState } from 'react';

interface ChatInputProps {
  onSend: (text: string) => void;
  streaming: boolean;
  onCancel: () => void;
}

function ChatInput({ onSend, streaming, onCancel }: ChatInputProps) {
  const [input, setInput] = useState('');

  const submit = () => {
    if (!input.trim()) return;
    onSend(input.trim());
    setInput('');
  };

  return (
    <div className="chat-input">
      <textarea
        value={input}
        onChange={e => setInput(e.target.value)}
        onKeyDown={e => {
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); }
        }}
        placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
      />
      {streaming
        ? <button className="btn-stop" onClick={onCancel}>停止</button>
        : <button onClick={submit}>发送</button>}
    </div>
  );
}
export default ChatInput;
```

```tsx
// components/MessageList.tsx —— v1 无历史，占位容器；迭代一接入 Query 历史
function MessageList() {
  return <div className="message-list" />;
}
export default MessageList;
```

```tsx
// components/LiveOutput.tsx —— 本轮流式输出区
interface LiveOutputProps {
  text: string;
  status: 'idle' | 'streaming' | 'done' | 'error';
}

function LiveOutput({ text, status }: LiveOutputProps) {
  if (status === 'idle') return null;
  return (
    <div className="live-output">
      <pre className="answer-text">{text}</pre>
      {status === 'streaming' && <span className="caret" aria-hidden="true" />}
    </div>
  );
}
export default LiveOutput;
```

```tsx
// components/ErrorBanner.tsx
interface ErrorBannerProps {
  message: string;
  onRetry: () => void;
}

function ErrorBanner({ message, onRetry }: ErrorBannerProps) {
  return (
    <div className="error-banner" role="alert">
      <span>{message}</span>
      <button onClick={onRetry}>重试</button>
    </div>
  );
}
export default ErrorBanner;
```

```tsx
// App.tsx —— 根组件接线
import { useCallback, useRef } from 'react';
import ChatInput from './components/ChatInput';
import ErrorBanner from './components/ErrorBanner';
import LiveOutput from './components/LiveOutput';
import MessageList from './components/MessageList';
import { useChatStream } from './hooks/useChatStream';

function App() {
  const { text, status, error, send, cancel } = useChatStream();
  const lastMessageRef = useRef('');

  const handleSend = useCallback((message: string) => {
    lastMessageRef.current = message;   // 记住上一条，供错误重试
    send(message);
  }, [send]);

  return (
    <div className="chat-page">
      <MessageList />
      <LiveOutput text={text} status={status} />
      {error && <ErrorBanner message={error} onRetry={() => send(lastMessageRef.current)} />}
      <ChatInput onSend={handleSend} streaming={status === 'streaming'} onCancel={cancel} />
    </div>
  );
}
export default App;
```

```tsx
// main.tsx —— 应用入口
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

```html
<!-- index.html -->
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Agent 控制台</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

### 4.8 本节测试与验证（v1 完整代码）

> **前置**：§3 两套工程已创建；`AgentEvent`/`Token`/`RoundEnd`/`ErrorEvent`/`TokenUsage` 已按 [00 §6.5] 手写；后端 `application.yml` 与 `pom.xml` 已按 [00 §6.1-6.2] 配好。

**材料**：`tsc --noEmit`（或 `npm run build`）做前端类型检查；后端 `mvn compile`；浏览器打开 `http://localhost:5173`。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 前端执行 `npx tsc --noEmit` | 零类型错误（events.ts 三种事件、sse.ts、useChatStream.ts、四个组件全部通过） |
| 2 | 后端执行 `mvn compile` | BUILD SUCCESS；ChatController 的 `Flux<ServerSentEvent<AgentEvent>>` 返回类型编译通过 |
| 3 | 浏览器 Console | 无红色报错；页面渲染出输入框（MessageList 为空占位是正常的） |
| 4 | 在输入框连按 Enter 空内容 | 不触发发送（submit 里 `!input.trim()` 守卫生效） |

**失败排查**：tsc 报 AgentEvent 类型不匹配 → 前端 events.ts 与 00 §6.5 契约字段名/类型抄错；后端编译找不到 AgentEvent → 包路径不是 `com.agent.console.event`；页面空白 → main.tsx 的挂载 id 与 index.html 的 `#root` 不一致。

---

## 5. 运行与联调

```sh
# 终端 1：启动参考后端
cd agent-console-backend
export DEEPSEEK_API_KEY=sk-xxx
mvn spring-boot:run

# 终端 2：启动前端（vite.config.ts 已把 /api 代理到 localhost:8080，见 00 §6.4）
cd agent-console
npm run dev

# 快速验证后端（不依赖前端）：
curl -N -X POST "http://localhost:8080/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo","message":"你好"}'
```

`curl -N` 关闭缓冲，能直接看到 `data: {...}\n\n` 一帧帧吐出。

### 5.1 本节测试与验证（运行与联调）

> **前置**：§4 代码全部落盘；`DEEPSEEK_API_KEY` 已 export；后端 8080、前端 5173 端口空闲。

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 终端 1 `mvn spring-boot:run` | 启动日志无异常，Tomcat/Netty 监听 8080 |
| 2 | 终端 2 `npm run dev` 后访问 `http://localhost:5173` | Vite 欢迎页被替换为 Agent 控制台（有输入框） |
| 3 | 执行本节 curl 命令 | 终端逐帧输出 `id: N` + `data: {"id":N,...,"type":"token"...}`，最后一帧 `type":"round_end"` |
| 4 | 页面发送"你好"（走 Vite 代理） | Network 面板 `/api/chat` 为 POST + fetch 流式响应（EventStream/fetch streaming），打字机逐组出现 |
| 5 | 停掉后端再发消息 | 页面出现错误横幅 + 重试按钮（fetch 抛 HTTP 错误路径生效） |

**失败排查**：curl 无输出 → API Key 未 export 或模型端点配错（看后端日志）；页面 404/跨域 → vite.config.ts 没按 00 §6.4 配 `/api` 代理；curl 有输出但页面空白 → 前端 fetch 路径不是 `/api/chat`（写成了绝对 8080 地址会绕过代理）。

## 6. ADR 演进决策

### ADR 04-01：v1 后端直接吐 AgentEvent JSON，而非裸文本 chunk
- **决策**：Controller 返回 `Flux<ServerSentEvent<AgentEvent>>`，每个 SSE 帧的 data 是事件 JSON（含 `type` 字段）
- **取舍理由**：虽然 v1 只消费 token，但从第一天起前端 `parseSSE` 就按事件协议解析——迭代二接 thought/tool 事件时**前端零改动**。若 v1 吐裸文本，迭代二要同时重写后端和 `parseSSE`，违背"契约先行"原则

### ADR 04-02：取消用 AbortController + ref，AbortError 静默分流
- **决策**：`abortRef` 存控制器；`onError` 里 `err.name === 'AbortError'` 直接 return
- **取舍理由**：主动取消是正常操作路径，不是故障；进错误态会吓到用户（[教程 17 §2]）

### 6.1 本节核对（ADR 演进决策）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 能解释 ADR 04-01 为何"契约先行" | v1 就按事件协议解析，迭代二加 thought/tool 事件前端 parseSSE 零改动 |
| 2 | 能解释 ADR 04-02 的分流位置 | AbortError 在 `onError` 回调里静默 return，而不是在 catch 里 |

## 7. 全篇回归验证

> 章节级验证已前移至各节（§3.1 工程初始化 / §4.8 完整代码 / §5.1 运行与联调）。以下为整体验收，不重复章节断言。

| # | 场景 | 期望 |
|---|------|------|
| 1 | 发送"你好" | 打字机逐帧出现（成组 token，非每字符一次） |
| 2 | 流式中点"停止" | 输出立即定格，无错误提示（AbortError 被正确分流） |
| 3 | 发送含长中文的问题 | 无乱码（TextDecoder stream:true 生效） |
| 4 | 流式中刷新页面 | 无报错残留（StrictMode 双连接被清理纪律吸收） |
| 5 | 后端返回 500 | 显示错误横幅 + 重试按钮；重试重发上一条消息 |

## 8. v1 的痛点（驱动下一迭代）

跑通之后立刻能感到的痛：
1. **刷新即失忆**——对话全在内存 state，没有会话概念
2. **单会话枷锁**——`sessionId` 写死为 `'demo'`，用户提第二个不相关的问题，上下文互相污染
3. **useState 拥挤**——再加两个状态（工具、审批）这个 Hook 就会失控
4. **消息列表裸奔**——没有分页、没有 TanStack Query 的缓存

这些痛点正是迭代一"多会话 + 三层状态"的需求来源。→ [02-流式对话界面.md](02-流式对话界面.md)

### 8.1 本节核对（v1 痛点）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四个痛点均能在跑通的 v1 上复现感受 | 刷新失忆 / sessionId 写死 demo / useState 单 Hook 承载 / 消息列表无分页缓存 |
| 2 | 痛点与迭代一需求一一对应 | 1↔会话持久化、2↔多会话、3↔reducer 状态机、4↔TanStack Query |

---

## 9. 总结

| 模块 | 交付物 | 关键点 |
|------|--------|--------|
| 参考后端 | 启动类 + AgentConfig + ChatController | `ChatClient.stream().content()` → `Flux<ServerSentEvent<AgentEvent>>`，SSE 层 id = 事件 id |
| 前端服务层 | `services/sse.ts` | fetch+RS、parseSSE + remainder 缓冲、AbortController 取消 |
| 前端 Hook | `useChatStream.ts` | rAF 批量缓冲（每帧一次渲染）、AbortError 分流、卸载清理 |
| 前端组件 | ChatInput / MessageList / LiveOutput / ErrorBanner / App | 受控输入 + 流式取消 + 错误重试 |

### 9.1 本节核对（总结）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四行交付物均能对应到 §4 的真实文件 | 后端 3 类 + sse.ts + useChatStream.ts + 5 个组件 |
| 2 | 关键点列的三条机制均在 §7 回归中被验证过 | SSE id=事件 id、rAF 批量缓冲、AbortError 分流 |
