# 23 - Web 安全与可分享性：注入防护、XSS、分享链接、前端监控

> 本章目标：解决 Web 项目特有的安全风险，并提供"可分享"的产品能力。
> 完成后：URL / 粘贴 / 拖拽注入被拦截、artifact 不被恶意 SVG/HTML 反向 XSS、同事能通过公共链接只读 review、所有前端异常能回传到后端 / Sentry。
>
> **关联章节**：
> - Artifacts 渲染入口：[09 章](./09-Artifacts.md)（iframe sandbox 强化）；
> - CORS / Cookie / JWT：[10 章](./10-集成ai-serving.md)（本章扩展 CSRF / refresh token）；
> - 前端可观测基础：[17 章](./17-全链路可观测前端.md)（本章加前端 Sentry / Web Vitals）；
> - 跨用户协同：[22 章](./22-跨标签页与实时协作.md)（presence 隐私 / share token）；
> - 智能体层安全（prompt injection / 凭据）：[24 章](./24-智能体安全.md)。

---

## 0. Web 项目特有的攻击面

| 攻击面 | 后果 | 来源 |
|--------|------|------|
| URL 参数注入 | `?prompt=ignore previous & ...` | 共享链接 / 钓鱼 |
| 粘贴内容注入 | 用户粘贴网页内容带恶意 prompt | 复制粘贴 |
| 拖拽文件名注入 | 文件名 `; rm -rf / ;.png` 被拼进 shell | 桌面拖入 |
| postMessage 任意来源 | iframe artifact 反向操纵父页面 | 09 章 HTML artifact |
| SVG `<script>` | artifact SVG 里嵌脚本执行 | 09 章 |
| Markdown `<img onerror>` | react-markdown 默认渲染 raw HTML | 09 章 |
| 公共分享链接可猜测 | 他人会话被遍历 | 路由设计 |
| 浏览器扩展 | 编辑 DOM 注入 prompt | 无解，但可加签 |
| 第三方 CDN 投毒 | Monaco / mermaid 被替换 | SRI |

---

## 1. 输入侧：URL / 粘贴 / 拖拽

### 1.1 URL 参数白名单

只接受预定义的 query 参数：

```ts
// 本代码仅作学习材料参考
// src/lib/urlSanitize.ts
const ALLOWED_QUERY_KEYS = new Set([
  'view', 'level', 'event', 'tab', 'share', 'from',
]);

export function sanitizeQueryParams(search: string): URLSearchParams {
  const params = new URLSearchParams(search);
  for (const key of [...params.keys()]) {
    if (!ALLOWED_QUERY_KEYS.has(key)) {
      params.delete(key);
    }
    // 长度限制
    if (params.get(key)?.length && params.get(key)!.length > 200) {
      params.delete(key);
    }
  }
  return params;
}
```

### 1.2 拦截"自动注入 prompt"的 URL

某些恶意链接会形如 `https://webclaude.app/sessions/abc?prompt=...`，打开后**自动发送**给 agent。规则：

- URL 里的 `?prompt=` / `?q=` / `?text=` 一律**忽略**（不自动发送）；
- 如果确实需要"快速发消息"产品功能，要求用户**手动点击确认**：

```tsx
// 本代码仅作学习材料参考
const pendingPrompt = searchParams.get('prompt');
if (pendingPrompt) {
  // 不自动发，弹个对话框让用户确认
  toast.info('检测到 URL 中包含消息，是否发送？', {
    action: { label: '发送', onClick: () => send(pendingPrompt) },
  });
}
```

### 1.3 粘贴内容安全

用户粘贴内容是 prompt injection 的常见入口（例如复制 ChatGPT 输出，里面藏着 `ignore previous instructions`）。

```ts
// 本代码仅作学习材料参考
// src/lib/clipboard.ts
export function sanitizePaste(text: string): string {
  // 1. 去掉零宽字符（U+200B / U+200C / U+200D / U+FEFF）
  const cleaned = text.replace(/[​-‍﻿]/g, '');
  // 2. 去掉 markdown 注入隐藏标记（如 [//]: #）
  // 3. 截断（防止巨型粘贴爆 context）
  return cleaned.slice(0, 100_000);
}

// 监听 paste
editor.addEventListener('paste', (e) => {
  e.preventDefault();
  const text = e.clipboardData?.getData('text/plain') ?? '';
  document.execCommand('insertText', false, sanitizePaste(text));
});
```

> 注：粘贴的内容仍是用户主动决定的，不会自动发给 agent，但去掉隐藏字符可避免"不可见注入"。

### 1.4 文件名 / 路径净化

用户拖入文件，文件名可能含特殊字符。工具调用时拼到 Bash 命令会被注入：

```ts
// 本代码仅作学习材料参考
// 上传时把文件名净化，不直接拼 shell
export function sanitizeFileName(name: string): string {
  // 仅保留字母数字 / 汉字 / . _ -
  return name.replace(/[^\w一-龥.\-]/g, '_').slice(0, 100);
}

// 上传时存原始名 + 净化后的存储 key
const upload = async (file: File) => {
  const safe = sanitizeFileName(file.name);
  const formData = new FormData();
  formData.append('file', file);
  formData.append('safeName', safe);
  await api.post('/upload', formData);
};
```

### 1.5 拖拽事件边界

```ts
// 本代码仅作学习材料参考
// 只允许在指定区域接受 drop
const dropZone = document.getElementById('drop-zone');
window.addEventListener('dragover', (e) => {
  // 全局禁用，阻止用户不小心把恶意文件拖到全屏
  e.preventDefault();
});
dropZone.addEventListener('drop', (e) => {
  e.preventDefault();
  // 此处才处理
});
```

---

## 2. 输出侧：artifact XSS 防护

### 2.1 SVG XSS

SVG 文件可包含 `<script>` 标签 / `onload=` 事件：

```svg
<svg xmlns="http://www.w3.org/2000/svg">
  <script>alert(document.cookie)</script>
  <image href="x" onerror="fetch('/api/leak?'+document.cookie)"/>
</svg>
```

如果直接 `<img src="x.svg">`，浏览器**不会**执行 SVG 内的脚本（img 标签安全）。但如果用 `<embed>`、`<object>` 或 inline `<svg>`，会执行。

**规则**：

```tsx
// 本代码仅作学习材料参考
// 09 章的 SVG 渲染改用 <img>，永远不要 inline
case 'svg':
  return <img src={a.url} alt={a.title} />;
```

如果要支持交互式 SVG，需先在服务端用 `DOMPurify` 净化：

```ts
// 本代码仅作学习材料参考
import DOMPurify from 'dompurify';

const clean = DOMPurify.sanitize(svgText, {
  USE_PROFILES: { svg: true, svgFilters: true },
  FORBID_TAGS: ['script'],
  FORBID_ATTR: ['onload', 'onerror', 'onclick'],
});
```

### 2.2 HTML artifact iframe 强化

09 章用了 `sandbox="allow-scripts"`，进一步加固：

```tsx
// 本代码仅作学习材料参考
<iframe
  src={a.url}
  sandbox="allow-scripts"
  // 不允许 allow-same-origin（不能读父页 cookie/localStorage）
  // 不允许 allow-forms / allow-popups / allow-top-navigation
  referrerPolicy="no-referrer"
  loading="lazy"
  // CSP 在 artifact 响应头里设置
/>
```

artifact 服务端响应头：

```
Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; connect-src 'none';
X-Content-Type-Options: nosniff
```

### 2.3 postMessage 白名单

09 章 HTML artifact iframe 如果需要向父页通信（如"我加载完了"），用 postMessage。父页必须验来源：

```ts
// 本代码仅作学习材料参考
window.addEventListener('message', (e) => {
  // 1. 严格校验来源（artifact 是同源 / 已知域）
  if (e.origin !== location.origin) return;
  // 2. 校验数据 schema
  if (typeof e.data !== 'object') return;
  if (e.data.type !== 'artifact-ready' && e.data.type !== 'artifact-error') return;
  // 3. 校验 artifactId 合法
  if (!isValidArtifactId(e.data.artifactId)) return;
  // 通过后才处理
  handleArtifactMessage(e.data);
});

// iframe 内发消息
parent.postMessage({ type: 'artifact-ready', artifactId: 'xxx' }, '*');
```

### 2.4 Markdown 渲染防护

react-markdown 默认**不渲染** raw HTML，但开了 `rehype-raw` 就危险。规则：

- **不要** 用 `rehype-raw`；
- 必须渲染 HTML 时，用 `rehype-sanitize` 配合白名单：

```tsx
// 本代码仅作学习材料参考
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';

const schema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    code: [['className', 'language-*']],  // 仅允许 code 加 className
    a: [['target', '_blank'], ['rel', 'noopener noreferrer']],
  },
  // 永远禁 script / iframe / object / embed
  tagNames: [...(defaultSchema.tagNames ?? []), 'img'],
};

<ReactMarkdown
  remarkPlugins={[remarkGfm]}
  rehypePlugins={[[rehypeSanitize, schema]]}
>
  {content}
</ReactMarkdown>
```

### 2.5 Mermaid 几乎安全

mermaid 内部用了 `dompurify`，相对安全，但仍需：

```tsx
// 本代码仅作学习材料参考
import mermaid from 'mermaid';

mermaid.initialize({
  startOnLoad: false,
  securityLevel: 'strict',  // 禁止点击事件 / HTML
  // 禁用 htmlLabels（避免渲染 HTML）
  flowchart: { htmlLabels: false },
});
```

---

## 3. CORS / Cookie / CSRF

### 3.1 CORS 严格配置

后端 Spring 配置：

```java
// 本代码仅作学习材料参考
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${webclaude.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)  // 生产环境明确列出域名
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("X-Request-Id")
            .allowCredentials(true)  // 配合 cookie
            .maxAge(3600);

        // WebSocket 不走 CORS filter，但握手时会校验 Origin
    }
}
```

`application.yaml`：

```yaml
webclaude:
  cors:
    allowed-origins:
      - https://webclaude.example.com
      - http://localhost:5173   # 开发
```

> **永远不要** `*` + `allowCredentials(true)`，浏览器会拒绝。

### 3.2 CSRF

10 章 JWT 在 `Authorization` header 里，不依赖 cookie，天然免 CSRF。但如果有 cookie fallback：

```java
// 本代码仅作学习材料参考
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf
        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
    );
    return http.build();
}
```

前端 axios 自动带 X-XSRF-TOKEN：

```ts
// 本代码仅作学习材料参考
import axios from 'axios';
const api = axios.create({ withCredentials: true, xsrfCookieName: 'XSRF-TOKEN', xsrfHeaderName: 'X-XSRF-TOKEN' });
```

### 3.3 Cookie 安全标志

```
Set-Cookie: webclaude_session=xxx;
  HttpOnly;             # JS 读不到
  Secure;               # 仅 HTTPS
  SameSite=Strict;      # 跨站不带（默认推荐）
  Path=/;
  Max-Age=86400;
```

> SameSite=Lax：允许顶级导航带 cookie（部分 SSO 场景需要）；
> SameSite=None：必须 Secure，跨站 iframe 用，**不要默认开启**。

### 3.4 Refresh Token 轮转

JWT 短时（15min）+ Refresh Token 长时（7d）：

```ts
// 本代码仅作学习材料参考
// 401 时自动刷新
api.interceptors.response.use(undefined, async (err) => {
  if (err.response?.status === 401 && !err.config._retried) {
    err.config._retried = true;
    const { token } = await api.post('/auth/refresh', { refresh: getRefreshToken() });
    setAuthToken(token);
    err.config.headers.Authorization = `Bearer ${token}`;
    return api(err.config);
  }
  throw err;
});
```

后端 refresh 轮转（每次 refresh 后旧 refresh 失效）：

```java
// 本代码仅作学习材料参考
@PostMapping("/auth/refresh")
public TokenPair refresh(@RequestBody RefreshRequest req) {
    RefreshTokenEntity old = refreshRepo.findById(req.refresh())
        .filter(r -> r.getExpiresAt().isAfter(Instant.now()))
        .orElseThrow(() -> new AuthException("invalid refresh token"));
    // 旧的失效（防重放）
    refreshRepo.delete(old);
    // 签发新对
    TokenPair pair = tokenService.generate(old.getUserId(), old.getTenantId());
    refreshRepo.save(new RefreshTokenEntity(pair.refresh(), old.getUserId(),
        old.getTenantId(), Instant.now().plus(7, ChronoUnit.DAYS)));
    return pair;
}
```

---

## 4. 公共分享链接

### 4.1 设计

| 字段 | 说明 |
|------|------|
| `share_token` | 20 字节随机 URL-safe，不可枚举 |
| `session_id` | 关联的 session |
| `created_by` | 谁创建的 |
| `expires_at` | 过期时间（默认 7 天，可永久） |
| `mode` | `readonly` / `readonly_with_artifacts` |
| `password` | 可选，需输入密码访问 |
| `revoked` | 是否已撤销 |

### 4.2 表结构

```sql
-- 本代码仅作学习材料参考
CREATE TABLE session_share_links (
    id UUID PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    session_id UUID NOT NULL,
    created_by UUID NOT NULL,
    mode VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255),
    expires_at TIMESTAMP,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_share_token ON session_share_links(token);
```

### 4.3 生成 token

```java
// 本代码仅作学习材料参考
public String generateToken() {
    byte[] bytes = new byte[20];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    // ~27 字符，熵 160 bit，不可枚举
}
```

### 4.4 验证流程

```java
// 本代码仅作学习材料参考
@RestController
@RequestMapping("/api/share")
public class ShareController {

    @GetMapping("/{token}")
    public ShareView view(@PathVariable String token,
                          @RequestParam(required = false) String password) {
        SessionShareLink link = shareRepo.findByToken(token)
            .filter(l -> !l.isRevoked())
            .filter(l -> l.getExpiresAt() == null || l.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new NotFoundException("链接无效"));

        if (link.getPasswordHash() != null) {
            if (password == null || !passwordEncoder.matches(password, link.getPasswordHash())) {
                throw new UnauthorizedException("需要密码");
            }
        }

        return buildShareView(link);
    }
}
```

### 4.5 ShareRoute（只读视图）

```tsx
// 本代码仅作学习材料参考
// 21 章 ShareRoute.tsx 的实现
export default function ShareRoute() {
  const { token } = useParams();
  const [password, setPassword] = useState('');
  const [needPassword, setNeedPassword] = useState(false);

  const { data, error } = useQuery({
    queryKey: ['share', token, password],
    queryFn: () => api.get(`/share/${token}`, { params: { password } }),
    retry: false,
  });

  if (error?.status === 401) {
    return <PasswordPrompt onSubmit={setPassword} />;
  }

  return (
    <ReadOnlySessionView
      session={data.session}
      events={data.events}
      artifacts={data.artifacts}
      // 不显示输入框 / 审批按钮 / 取消按钮
    />
  );
}
```

### 4.6 只读 WebSocket

```java
// 本代码仅作学习材料参考
// WebSocket 握手时检查 share token
if (shareToken != null) {
    SessionShareLink link = shareService.validate(shareToken, sessionId, null);
    ws.getAttributes().put("readonly", true);
    ws.getAttributes().put("shareLinkId", link.getId());
    topics.subscribe(sessionId, ws.getId());
}

// send 拦截
@Override
protected void handleTextMessage(WebSocketSession ws, TextMessage raw) {
    if (Boolean.TRUE.equals(ws.getAttributes().get("readonly"))) {
        // 只读连接只能 ping，不能 user_input / answer / abort
        if (!"ping".equals(type)) {
            ws.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
    }
    // ...
}
```

### 4.7 撤销与过期

```java
// 本代码仅作学习材料参考
@PostMapping("/api/sessions/{id}/share/{linkId}/revoke")
public void revoke(@PathVariable UUID id, @PathVariable UUID linkId) {
    SessionShareLink link = shareRepo.findById(linkId).orElseThrow();
    // 权限校验：只有创建者 / 管理员能撤销
    if (!link.getCreatedBy().equals(currentUser())) throw new ForbiddenException();
    link.setRevoked(true);
    shareRepo.save(link);
    // 主动断开当前用该 link 的 WS 连接
    sessionTopicRegistry.evictShareLink(linkId);
}
```

---

## 5. SRI 与 CDN 防投毒

### 5.1 第三方依赖加 SRI

```html
<!-- 如果用了 CDN -->
<script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"
        integrity="sha384-..."
        crossorigin="anonymous"></script>
```

### 5.2 自托管优先

把 Monaco / mermaid / React 都打包到自家域名 + CDN，不引用第三方 CDN。

### 5.3 Subresource Integrity 自动化

Vite 用 `vite-plugin-sri`：

```ts
// 本代码仅作学习材料参考
import { sri } from 'vite-plugin-sri';
export default { plugins: [sri()] };
```

---

## 6. 前端可观测

### 6.1 Sentry 接入

```ts
// 本代码仅作学习材料参考
// src/main.tsx
import * as Sentry from '@sentry/react';

Sentry.init({
  dsn: import.meta.env.VITE_SENTRY_DSN,
  environment: import.meta.env.MODE,
  release: __APP_VERSION__,
  integrations: [
    Sentry.browserTracingIntegration(),
    Sentry.replayIntegration({ maskAllText: true, blockAllMedia: true }),
  ],
  tracesSampleRate: 0.1,
  // 过滤掉敏感字段
  beforeSend(event) {
    if (event.request?.url) {
      event.request.url = event.request.url.replace(/token=[^&]+/, 'token=REDACTED');
    }
    return event;
  },
});
```

### 6.2 错误边界 + Sentry

21 章 ErrorBoundary 升级：

```tsx
// 本代码仅作学习材料参考
import { ErrorBoundary as SentryBoundary } from '@sentry/react';

<SentryBoundary
  fallback={({ error, resetError }) => <ArtifactErrorFallback error={error} reset={resetError} />}
  beforeCapture={(scope) => scope.setTag('boundary', 'artifact')}
  showDialog
>
  <MonacoEditor />
</SentryBoundary>
```

### 6.3 Core Web Vitals

```ts
// 本代码仅作学习材料参考
import { onLCP, onINP, onCLS, onFCP, onTTFB } from 'web-vitals';

function report(metric) {
  Sentry.metrics.distribution(metric.name, metric.value, { unit: 'millisecond' });
  // 或自建通道
  navigator.sendBeacon('/api/metrics/vitals', JSON.stringify({
    name: metric.name, value: metric.value, id: metric.id, at: Date.now(),
  }));
}

onLCP(report);
onINP(report);
onCLS(report);
onFCP(report);
onTTFB(report);
```

后端聚合：

```java
// 本代码仅作学习材料参考
@PostMapping("/api/metrics/vitals")
public void record(@RequestBody WebVital metric) {
    // 写 Prometheus 计量
    webVitalLcp.labels(metric.getPage()).observe(metric.getValue() / 1000.0);
}
```

### 6.4 前端日志通道

把 `console.error` 转发到后端：

```ts
// 本代码仅作学习材料参考
const origError = console.error;
console.error = (...args) => {
  origError(...args);
  // 批量异步发，不阻塞
  queueLog({
    level: 'error',
    msg: args.map(a => typeof a === 'object' ? JSON.stringify(a) : String(a)).join(' '),
    at: Date.now(),
    url: location.pathname,
    sessionId: currentSessionId,
  });
};

const logBuffer: LogEntry[] = [];
let flushTimer: number;

function queueLog(entry: LogEntry) {
  logBuffer.push(entry);
  if (!flushTimer) {
    flushTimer = window.setTimeout(flush, 5000);
  }
}

function flush() {
  if (!logBuffer.length) return;
  const batch = logBuffer.splice(0);
  navigator.sendBeacon('/api/logs/frontend', JSON.stringify(batch));
  flushTimer = 0;
}

// 离开页面前 flush
window.addEventListener('beforeunload', flush);
```

后端落库到 `frontend_logs` 表（按租户隔离），Grafana 可查。

---

## 7. CSP 头

最严格的 CSP：

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'wasm-unsafe-eval';
  style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:;
  font-src 'self' data:;
  connect-src 'self' wss://webclaude.example.com https://sentry.io;
  frame-src 'self' blob:;
  object-src 'none';
  base-uri 'self';
  form-action 'self';
  frame-ancestors 'none';
  upgrade-insecure-requests;
```

Spring 配置：

```java
// 本代码仅作学习材料参考
@Bean
public WebFilter cspFilter() {
    return (exchange, chain) -> {
        exchange.getResponse().getHeaders().add("Content-Security-Policy", CSP);
        exchange.getResponse().getHeaders().add("X-Frame-Options", "DENY");
        exchange.getResponse().getHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.getResponse().getHeaders().add("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponse().getHeaders().add("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        return chain.filter(exchange);
    };
}
```

---

## 8. 浏览器扩展防御

无法完全防御恶意扩展，但可降低风险：

- 敏感操作前校验 DOM 完整性（如 hash 检查审批卡片）；
- 关键 prompt 走 signed iframe（自家 iframe 内签名，外层扩展改不了）；
- 提示用户使用官方扩展 / 不装可疑扩展。

> 这一块 v1 不做，仅记录。

---

## 9. 安全清单（Web 专项）

- [ ] URL 参数白名单 + `?prompt=` 不自动发送；
- [ ] 粘贴内容去除零宽字符 + 截断；
- [ ] 文件名净化后才能拼 Bash；
- [ ] SVG 用 `<img>` 渲染，inline 必须 DOMPurify；
- [ ] HTML artifact iframe sandbox 严格，postMessage 验来源；
- [ ] Markdown 用 rehype-sanitize 白名单；
- [ ] Mermaid securityLevel=strict；
- [ ] CORS 明确域名，禁 `*` + credentials；
- [ ] Cookie HttpOnly + Secure + SameSite=Strict；
- [ ] JWT 短时 + Refresh Token 轮转；
- [ ] 分享 token 20 字节随机，可撤销、可过期、可加密；
- [ ] 第三方依赖 SRI 或自托管；
- [ ] CSP 严格，禁 object/embed/base；
- [ ] Sentry 接入 + Web Vitals 上报；
- [ ] `console.error` 转发后端。

---

## 10. 本章产出

```
输入侧：
  ✅ URL 参数白名单 + 自动注入拦截
  ✅ 粘贴内容 sanitize
  ✅ 文件名净化
  ✅ 拖拽区域限制

输出侧：
  ✅ SVG 用 <img> + DOMPurify（需 inline 时）
  ✅ HTML iframe sandbox 严格 + postMessage 验来源
  ✅ Markdown rehype-sanitize 白名单
  ✅ Mermaid securityLevel=strict

传输侧：
  ✅ CORS 严格配置
  ✅ CSRF（如用 cookie）+ HttpOnly / Secure / SameSite
  ✅ Refresh Token 轮转

产品能力：
  ✅ 公共分享链接（可撤销 / 过期 / 加密）
  ✅ ShareRoute 只读视图

前端监控：
  ✅ Sentry 接入 + 错误边界
  ✅ Core Web Vitals 上报
  ✅ 前端日志通道

加固：
  ✅ SRI / 自托管
  ✅ CSP 头
  ✅ Permissions-Policy
```

## 11. 下一步

进入 [24-智能体安全](./24-智能体安全.md)，从 Agent 层面解决 prompt injection / 凭据泄露 / 沙箱逃逸 / 越权调用问题。
