# 09 - Artifacts：产出物实时渲染

> 本章目标：Agent 写文件后，浏览器侧能实时看到代码 / Markdown / HTML / SVG 等。
> 完成后：右侧面板展示产出物，与对话联动。
>
> **关联章节**：
> - artifact 事件并入 [17 章活动流](./17-全链路可观测前端.md)（artifact 作为一类事件渲染在时间线）；
> - artifact 由 Write/Edit 工具产出，详见 [05 章](./05-工具系统与权限.md)；
> - 工件审核（Diff Review）：[20 章](./20-审批与审核流.md)。
>
> **Web 安全升级（[23 章](./23-Web安全与可分享性.md)）**：
> - HTML artifact iframe 必须 sandbox 严格 + postMessage 验来源；
> - SVG 用 `<img>` 渲染（不 inline），如必须 inline 用 DOMPurify；
> - Markdown 用 rehype-sanitize 白名单，禁 rehype-raw；
> - Mermaid `securityLevel: strict`；
> - artifact 可分享公共 URL（[23 章 §4](./23-Web安全与可分享性.md) 分享链接）。

---

## 本章五步地图

| 步 | 节 | 你要带走什么 |
|----|----|---------|
| ① 痛点 | §1 | 没这一章，Agent 写的代码用户得 SSH 进容器才能看——这不算"网页版" |
| ② 最小实现 | §2–§4 | ArtifactService（S3 上传）+ WS 广播 + ArtifactPanel（多类型渲染）|
| ③ 验证 | §5 | 让 Agent 写 hello.py / README.md / index.html，右侧面板各自渲染 |
| ④ 对照 | §6 | 与"纯文本对话"的体验差异 |
| ⑤ 避坑 | §7 | iframe XSS / SVG 内联 / Markdown rehype-raw / 签名 URL |

---

## 1. 痛点：05/06 章的 Agent 能写文件，但用户看不到

05/06 章结束时 Agent 已经能 `Write("hello.py", ...)`，但**用户只能通过对话气泡里 Agent 的口头描述知道写了什么**——文件实际在沙箱容器里，用户看不见。

这导致 Agent 类产品最尴尬的局面：
- 用户问"帮我写个网站"，Agent 说"写好了"，用户**看不到效果**
- 用户问"画个流程图"，Agent 说"画好了"，用户**得自己 SSH 进容器**才能看
- 用户问"重写下这个 React 组件"，Agent 改完用户**没有 diff 可看**

> "网页版 Claude" 相对 "CLI Claude" 最大的体验优势就是 **Artifacts**：右侧面板实时渲染 Agent 产出的代码 / 文档 / 图表 / 网页。这一章就是这个能力。
>
> 注意：本章的 §7 安全只是**最小化提示**。完整 Web 安全方案（DOMPurify、CSP、SRI 等）在 23 章。

## 2. 设计要点

- 类型：code / markdown / html / svg / mermaid / image；
- 捕获：Write/Edit 工具完成后 → 检测文件类型 → 上传 S3 → 推送 artifact 事件；
- 渲染：iframe 沙箱（HTML）、Monaco（code）、react-markdown（MD）。

---

## 3. 后端：ArtifactService

### 3.1 表结构

新增 `V3__artifacts.sql`：

```sql
-- 本代码仅作学习材料参考
CREATE TABLE artifacts (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    task_id UUID,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(255),
    storage_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(64),
    size_bytes BIGINT,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_artifacts_session ON artifacts(session_id);
```

### 3.2 服务实现

新建 `src/main/java/org/demo02/webclaude/artifact/ArtifactService.java`：

```java
// 本代码仅作学习材料参考
package org.demo02.webclaude.artifact;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class ArtifactService {

    private static final String BUCKET = "web-claude";
    private final S3Client s3;

    public ArtifactService(S3Client s3) { this.s3 = s3; }

    public Artifact capture(UUID sessionId, UUID taskId, Path localPath, String type) {
        try {
            String title = localPath.getFileName().toString();
            String storageKey = "artifacts/" + sessionId + "/" + UUID.randomUUID() + "/" + title;
            String mime = guessMime(localPath.toString());
            long size = Files.size(localPath);

            s3.putObject(PutObjectRequest.builder()
                .bucket(BUCKET)
                .key(storageKey)
                .contentType(mime)
                .build(), RequestBody.fromFile(localPath));

            return new Artifact(UUID.randomUUID(), sessionId, taskId, type, title, storageKey, mime, size, 1);
        } catch (Exception e) {
            throw new RuntimeException("artifact capture failed", e);
        }
    }

    public String signedUrl(String storageKey, int ttlSeconds) {
        return s3.utilities().getUrl(b -> b.bucket(BUCKET).key(storageKey)).toString();
    }

    private String guessMime(String path) {
        if (path.endsWith(".md")) return "text/markdown";
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".json")) return "application/json";
        return "text/plain";
    }

    public record Artifact(UUID id, UUID sessionId, UUID taskId, String type,
                           String title, String storageKey, String mime,
                           long size, int version) {}
}
```

### 3.3 接入 WriteTool

修改 `WriteTool.apply`，在写完文件后调用 `ArtifactService.capture` 并推 WS：

```java
// 本代码仅作学习材料参考
@Override
public CompletableFuture<ToolResult> apply(Map<String, Object> input, ToolContext ctx) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            // ... 写文件 ...
            Path resolved = root.resolve((String) input.get("path")).normalize();
            Files.writeString(resolved, (String) input.get("content"));

            // 捕获 artifact
            String type = guessType(resolved.toString());
            ArtifactService.Artifact art = artifactService.capture(
                ctx.sessionId(), ctx.taskId(), resolved, type);

            // 推 WS 事件
            wsBroadcaster.broadcastArtifact(ctx.sessionId(), art);

            return ToolResult.artifact("wrote " + input.get("path"), type, resolved.toString());
        } catch (Exception e) {
            return ToolResult.error("write failed: " + e.getMessage());
        }
    });
}
```

### 3.4 WS 广播

```java
// 本代码仅作学习材料参考
public void broadcastArtifact(UUID sessionId, ArtifactService.Artifact art) {
    WebSocketSession ws = sessionWs.get(sessionId);
    if (ws == null) return;
    Map<String, Object> wire = Map.of(
        "type", "artifact",
        "session_id", sessionId,
        "artifact", Map.of(
            "id", art.id(),
            "type", art.type(),
            "title", art.title(),
            "url", "/api/artifacts/" + art.id() + "/content"
        )
    );
    try {
        ws.sendMessage(new TextMessage(om.writeValueAsString(wire)));
    } catch (Exception ignored) {}
}
```

### 3.5 REST 接口

新建 `src/main/java/org/demo02/webclaude/artifact/ArtifactController.java`：

```java
// 本代码仅作学习材料参考
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactService service;
    private final ArtifactRepository repo;

    @GetMapping("/{id}/content")
    public ResponseEntity<?> content(@PathVariable UUID id) {
        ArtifactEntity e = repo.findById(id).orElseThrow();
        String url = service.signedUrl(e.getStorageKey(), 3600);
        return ResponseEntity.status(302).location(java.net.URI.create(url)).build();
    }
}
```

---

## 4. 前端：ArtifactPanel

`src/components/ArtifactPanel.tsx`：

```tsx
// 本代码仅作学习材料参考
import { useEffect, useState } from 'react';
import Editor from '@monaco-editor/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import mermaid from 'mermaid';

export interface ArtifactWire {
  id: string;
  type: 'code' | 'markdown' | 'html' | 'svg' | 'mermaid' | 'image';
  title: string;
  url: string;
}

export default function ArtifactPanel({ artifacts }: { artifacts: ArtifactWire[] }) {
  const [active, setActive] = useState<string | null>(null);
  const current = artifacts.find((a) => a.id === active) ?? artifacts[artifacts.length - 1];

  useEffect(() => {
    if (current?.type === 'mermaid') {
      mermaid.run();
    }
  }, [current]);

  if (!current) return <div style={{ padding: 24 }}>暂无工件</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
      <div style={{ borderBottom: '1px solid #ccc', padding: 8 }}>
        {artifacts.map((a) => (
          <button
            key={a.id}
            onClick={() => setActive(a.id)}
            style={{ marginRight: 8, fontWeight: a.id === current.id ? 'bold' : 'normal' }}
          >
            {a.title}
          </button>
        ))}
      </div>
      <div style={{ flex: 1, padding: 16 }}>
        {renderByType(current)}
      </div>
    </div>
  );
}

function renderByType(a: ArtifactWire) {
  switch (a.type) {
    case 'code':
      return (
        <Editor
          height="100%"
          path={a.title}
          defaultLanguage={guessLang(a.title)}
          defaultValue=""
          value={undefined}
        />
      );
    case 'markdown':
      return (
        <PromiseText url={a.url}>
          {(text) => <ReactMarkdown remarkPlugins={[remarkGfm]}>{text}</ReactMarkdown>}
        </PromiseText>
      );
    case 'html':
      return <iframe src={a.url} sandbox="allow-scripts" style={{ width: '100%', height: '100%' }} />;
    case 'svg':
    case 'image':
      return <img src={a.url} alt={a.title} style={{ maxWidth: '100%' }} />;
    case 'mermaid':
      return (
        <PromiseText url={a.url}>
          {(text) => <div className="mermaid">{text}</div>}
        </PromiseText>
      );
  }
}

function PromiseText({ url, children }: { url: string; children: (t: string) => React.ReactNode }) {
  const [text, setText] = useState('');
  useEffect(() => { fetch(url).then(r => r.text()).then(setText); }, [url]);
  return <>{children(text)}</>;
}

function guessLang(name: string): string {
  if (name.endsWith('.ts') || name.endsWith('.tsx')) return 'typescript';
  if (name.endsWith('.js') || name.endsWith('.jsx')) return 'javascript';
  if (name.endsWith('.java')) return 'java';
  if (name.endsWith('.py')) return 'python';
  if (name.endsWith('.json')) return 'json';
  if (name.endsWith('.md')) return 'markdown';
  return 'plaintext';
}
```

> Monaco 编辑器要在线拉内容，需要再封一个 PromiseEditor，思路同 PromiseText。

### 4.2 接入 App

修改 `App.tsx`：

```tsx
// 本代码仅作学习材料参考
const [artifacts, setArtifacts] = useState<ArtifactWire[]>([]);

ws.onMessage((m) => {
  if (m.type === 'artifact') {
    setArtifacts((prev) => [...prev, (m as any).artifact]);
  }
});

return (
  <div style={{ display: 'flex', height: '100vh' }}>
    <ChatPanel sessionId={sessionId} />
    <ArtifactPanel artifacts={artifacts} />
  </div>
);
```

---

## 5. 验证：测试 Artifacts

### 5.1 流程

1. 让 Agent "写一个 hello.py"；
2. 看到 artifact 事件；
3. 右侧面板出现 hello.py 标签；
4. 点开看到 Monaco 渲染。

### 5.2 各类型测试

让 Agent 依次写：
- `README.md` → markdown 渲染；
- `index.html` → iframe 预览；
- `chart.svg` → 图片预览。

**检查点 09-1**：所有类型 artifact 都能正确渲染。

---

## 6. 对照：与"纯文本对话"的体验差异

| 维度 | 05/06 章（无 artifact） | 09 章（有 artifact） |
|------|------------------------|---------------------|
| 用户能否看到产出 | ❌ 只能听 Agent 说 | ✅ 右侧实时渲染 |
| 代码 | 文本气泡 | ✅ Monaco 高亮 |
| Markdown | 原始 md 文本 | ✅ 渲染后版式 |
| HTML | 看不到效果 | ✅ iframe 实时预览 |
| SVG | 看不到 | ✅ 图片预览 |
| Mermaid | 看不到 | ✅ 流程图渲染 |
| 可分享 | ❌ | ⚠️ v1 简化（23 章做签名 URL） |

## 7. 避坑：Artifacts 渲染常踩的雷

| 雷 | 现象 | 规避 |
|----|------|------|
| HTML artifact 用 same-origin iframe | 跑父页 cookie / 改你 DOM | `sandbox="allow-scripts"`（不带 allow-same-origin）|
| SVG 直接 inline 渲染 | XSS（SVG 内嵌 script） | 用 `<img src>` 渲染；要 inline 先 DOMPurify |
| Markdown 用 rehype-raw | 嵌入任意 HTML | 禁 rehype-raw + 启 rehype-sanitize |
| Mermaid 默认配置 | 用户输入触发 XSS | `securityLevel: 'strict'` |
| artifact URL 长期有效 | 链接泄漏后任何人可看 | 生产用短期签名 URL（v1 是简化版）|
| Monaco 体积太大（>2MB） | 首屏白屏 | 懒加载（路由进入再 dynamic import）|
| iframe blob URL | 内存泄漏 / 跨 tab 复用难 | 用 S3 URL，不要 blob: |
| 大文件（>10MB）一次性 fetch | 浏览器卡死 | 分块加载 / 限制 artifact 大小 |
| 写同一文件多个版本 | 用户看不到旧版 | 加 `version` 字段，UI 支持版本切换 |

> 本章 §8 是最小化安全提示，完整方案在 **[23 章](./23-Web安全与可分享性.md)**。

## 8. 安全注意

- HTML artifact 必须 `sandbox="allow-scripts"`（不允许 same-origin）；
- iframe 内不允许访问父页面的 cookie/localStorage；
- Artifact URL 必须是短期签名（v1 简化为公开 URL，生产要签名）。

> **重要**：本章 §4 是最简化的安全提示。完整 Web 安全方案（DOMPurify SVG、
> rehype-sanitize Markdown、Mermaid strict、postMessage 白名单、CSP 头、
> SRI / 自托管、可分享链接）见 **[23 章](./23-Web安全与可分享性.md)** §2 §3 §4 §5 §7。

---

## 9. v2 升级路径

- React 组件实时渲染（沙箱执行）：用 Sandpack 或自研 iframe + esbuild.wasm；
- 多版本 diff（同一逻辑 artifact 的不同版本）；
- 协同编辑（接入 Yjs）：[22 章 §6](./22-跨标签页与实时协作.md) 给出基础接入示例。

---

## 10. 本章产出

```
后端：
  ✅ ArtifactService（capture + signed URL）
  ✅ ArtifactController（REST content）
  ✅ WriteTool/EditTool 触发 artifact 捕获
  ✅ WS 广播 artifact 事件

前端：
  ✅ ArtifactPanel（多类型渲染）
  ✅ WS 接入 artifact 事件
```

## 11. 下一步

进入 [10-集成ai-serving](./10-集成ai-serving.md)，把单租户 MVP 接入企业级 AI 网关与多租户基础设施。
