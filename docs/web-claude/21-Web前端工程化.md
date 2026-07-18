# 21 - Web 前端工程化：把"演示 demo"升级成"真实产品"

> 本章目标：把前 20 章散落各处的前端片段收敛成一套**可维护、可扩展、可上线**的 Web 前端架构。
> 完成后：路由 / 状态 / 错误边界 / 移动端 / 键盘 / i18n / 主题 / 代码分割全部就位，长程任务在浏览器里跑得稳。
>
> **关联章节**：
> - 路由与 [17 章活动流](./17-全链路可观测前端.md) / [19 章 AskUser](./19-AskUser与澄清式交互.md) / [20 章审批](./20-审批与审核流.md) 强相关（多页面共享 session 状态）；
> - 跨标签页 / 实时协作见 [22 章](./22-跨标签页与实时协作.md)；
> - Web 安全（XSS / postMessage / CSP / 分享链接）见 [23 章](./23-Web安全与可分享性.md)；
> - 前端错误监控见 [23 章 §6](./23-Web安全与可分享性.md)；
> - 本章是横切章节，给前 20 章的前端代码"补地基"。

---

## 本章五步地图

| 步 | 节 | 你要带走什么 |
|----|----|---------|
| ① 痛点 | §0 | 前 20 章 useState + inline style + 单文件 App.tsx 在真实产品里会爆雷 |
| ② 最小实现 | §1–§13 | 目录结构 / 路由 / 状态分层 / 代码分割 / ErrorBoundary / 移动端 / 键盘 / i18n / 主题 / 流式渲染 / a11y |
| ③ 验证 | §12 | 性能基准（首屏 / bundle / Lighthouse）|
| ④ 对照 | §14 | 与"前 20 章 demo 写法"的差异 |
| ⑤ 避坑 | §13 | 大依赖首屏 / ErrorBoundary 漂白 / 移动端误触 / i18n 占位符 |

---

## 0. 为什么单独成章

前 20 章为了讲解机制，前端代码大多是 `useState` + 写死 inline style + 单文件 App.tsx。这种写法在 demo 阶段够用，但放到真实 Web 产品会立刻爆雷：

- 几百个 agent_events 一上来，散在各组件的 `useState` 会乱成意大利面；
- Artifact 渲染的 Monaco / mermaid 都是大依赖，首屏不拆会拖到 10s+；
- React 组件抛错会让整个页面白屏，用户正在审的审批弹窗直接消失；
- 用户在地铁里用手机审批，写死 `padding: 24` 的布局点不到按钮；
- 海外同事打开网页全是中文，看不懂。

本章解决这些"工程层"的问题，让前 20 章的机制能落到真实产品。

---

## 1. 技术选型

| 维度 | 选择 | 理由 |
|------|------|------|
| 框架 | React 18 + TypeScript | 与 03 章一致，社区成熟 |
| 构建 | Vite 5 + Rollup | 03 章已选 |
| 路由 | React Router 6（Data Router） | 主流、支持 loader / lazy |
| 状态管理 | Zustand（全局）+ React Query（服务端态） | Zustand 极简、Query 解决缓存失效 |
| UI 库 | shadcn/ui + Tailwind CSS | 可控、不锁包大小、a11y 友好 |
| 代码分割 | React.lazy + Suspense + Vite manualChunks | |
| 错误边界 | react-error-boundary | 主流、轻量 |
| i18n | react-i18next | 生态成熟 |
| 主题 | next-themes（兼容 Vite） | 一行切深浅色 |
| 键盘 | 自封装 useHotkeys（基于 hotkeys-js） | |
| 移动端 | Tailwind 响应式 + 触摸事件 polyfill | |
| 表单 | react-hook-form + zod | 19 章 form question 复用 |
| 虚拟滚动 | @tanstack/react-virtual | 17 章活动流已用 |

---

## 2. 目录结构

```
frontend/
├── index.html
├── vite.config.ts
├── tsconfig.json
├── tailwind.config.ts
├── src/
│   ├── main.tsx                   # 入口
│   ├── App.tsx                    # 路由壳
│   ├── router.tsx                 # 路由表（lazy）
│   ├── routes/                    # 页面级组件（懒加载）
│   │   ├── SessionRoute.tsx
│   │   ├── TaskRoute.tsx
│   │   ├── TasksRoute.tsx
│   │   ├── ApprovalsRoute.tsx
│   │   ├── SettingsRoute.tsx
│   │   ├── ShareRoute.tsx         # 公共分享只读视图
│   │   └── LoginRoute.tsx
│   ├── components/                # 复用组件
│   │   ├── chat/                  # 04/08/19 章
│   │   ├── activity/              # 17 章
│   │   ├── artifact/              # 09 章
│   │   ├── approval/              # 20 章
│   │   ├── task/                  # 11 章
│   │   ├── layout/                # AppShell / Sidebar / TopBar
│   │   └── common/                # Button / Dialog / Toast
│   ├── stores/                    # Zustand 全局 store
│   │   ├── sessionStore.ts
│   │   ├── uiStore.ts             # 主题 / 显示级别 / 侧栏开关
│   │   ├── approvalStore.ts       # 20 章审批中心
│   │   └── authStore.ts
│   ├── api/                       # REST + WS 客户端
│   │   ├── client.ts              # axios + interceptor
│   │   ├── ws.ts                  # SessionWS（08 章）
│   │   └── hooks/                 # React Query 封装
│   ├── i18n/
│   │   ├── index.ts
│   │   └── locales/
│   │       ├── zh-CN.json
│   │       └── en-US.json
│   ├── lib/
│   │   ├── keyboard.ts            # 快捷键
│   │   ├── share.ts               # 分享链接
│   │   └── platform.ts            # 移动端检测 / 触摸支持
│   ├── styles/
│   │   └── globals.css            # Tailwind base
│   └── types/
└── tests/                         # 26 章
```

---

## 3. 路由设计

### 3.1 路由表

新建 `src/router.tsx`：

```tsx
// 本代码仅作学习材料参考
import { createBrowserRouter, lazyRouteComponent } from 'react-router-dom';
import { Suspense } from 'react';
import { AppShell } from './components/layout/AppShell';
import { RouteFallback } from './components/common/RouteFallback';
import { ErrorBoundary } from './components/common/ErrorBoundary';

const lazy = (loader: () => Promise<{ default: React.ComponentType }>) =>
  lazyRouteComponent(loader, 'default');

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    errorElement: <RootError />,
    children: [
      { index: true, element: <Navigate to="/sessions" replace /> },
      {
        path: 'sessions',
        loader: sessionsListLoader,
        element: <Suspense fallback={<RouteFallback />}><SessionsRoute /></Suspense>,
      },
      {
        path: 'sessions/:sessionId',
        element: <Suspense fallback={<RouteFallback />}><SessionRoute /></Suspense>,
      },
      {
        path: 'tasks',
        element: <Suspense fallback={<RouteFallback />}><TasksRoute /></Suspense>,
      },
      {
        path: 'tasks/:taskId',
        element: <Suspense fallback={<RouteFallback />}><TaskRoute /></Suspense>,
      },
      {
        path: 'approvals',
        element: <Suspense fallback={<RouteFallback />}><ApprovalsRoute /></Suspense>,
      },
      {
        path: 'settings',
        element: <Suspense fallback={<RouteFallback />}><SettingsRoute /></Suspense>,
      },
    ],
  },
  {
    // 公共分享链接（不鉴权、只读）
    path: '/share/:token',
    element: <Suspense fallback={<RouteFallback />}><ShareRoute /></Suspense>,
    errorElement: <RootError />,
  },
  {
    path: '/login',
    element: <Suspense fallback={<RouteFallback />}><LoginRoute /></Suspense>,
  },
]);
```

### 3.2 路由级懒加载

`SessionRoute.tsx`、`TaskRoute.tsx`、`ApprovalsRoute.tsx` 都是 chunk 大户（Monaco / mermaid / 复杂表单），通过 `lazy()` 自动分块。

### 3.3 路由守卫

```tsx
// 本代码仅作学习材料参考
function AppShell() {
  const auth = useAuthStore();
  if (!auth.token) return <Navigate to="/login" replace />;
  return (
    <div className="flex h-screen">
      <Sidebar />
      <main className="flex-1 overflow-hidden">
        <Outlet />
      </main>
    </div>
  );
}
```

### 3.4 路由参数约定

| 路径 | 含义 |
|------|------|
| `/sessions/:id?view=activity&level=debug` | 单 session 视图，URL 控制显示级别 |
| `/sessions/:id?event=42` | 直接定位到某个 event（便于分享） |
| `/tasks/:id?tab=features` | 任务视图，URL 控制 tab |
| `/approvals?status=pending` | 审批中心 |

**原则**：所有"用户能看到的状态"都进 URL（深链接、刷新可恢复、可分享）。

---

## 4. 状态管理分层

### 4.1 三层划分

| 层 | 工具 | 例子 |
|---|------|------|
| **服务端态**（远端数据 + 缓存失效） | React Query | session 列表、task 详情、approvals |
| **客户端态**（跨组件共享、本地派生） | Zustand | 当前 sessionId、UI 偏好、活动流 events |
| **组件本地态**（自闭合、不共享） | useState | 输入框文本、临时 hover |

### 4.2 全局 stores

```ts
// 本代码仅作学习材料参考
// src/stores/uiStore.ts
interface UIState {
  activityLevel: 'simple' | 'detailed' | 'debug';
  sidebarCollapsed: boolean;
  theme: 'light' | 'dark' | 'system';
  setActivityLevel: (l: UIState['activityLevel']) => void;
  // ...
}
export const useUIStore = create<UIState>(persist(
  (set) => ({
    activityLevel: 'detailed',
    sidebarCollapsed: false,
    theme: 'system',
    setActivityLevel: (l) => set({ activityLevel: l }),
  }),
  { name: 'webclaude.ui' }
));
```

### 4.3 活动流 events 单独 store

17 章的活动流可能积累上千 events，单独放 store 避免组件重渲染：

```ts
// 本代码仅作学习材料参考
// src/stores/activityStore.ts
interface ActivityState {
  events: AgentEvent[];          // 按 seq 排序
  bySeq: Map<number, AgentEvent>;
  append: (e: AgentEvent) => void;
  appendMany: (es: AgentEvent[]) => void;
  reset: () => void;
}

export const useActivityStore = create<ActivityState>((set, get) => ({
  events: [],
  bySeq: new Map(),
  append: (e) => {
    if (get().bySeq.has(e.seq)) return;
    set((s) => {
      const next = [...s.events, e];
      const bySeq = new Map(s.bySeq);
      bySeq.set(e.seq, e);
      return { events: next, bySeq };
    });
  },
  appendMany: (es) => {
    set((s) => {
      const bySeq = new Map(s.bySeq);
      es.forEach((e) => bySeq.set(e.seq, e));
      const merged = [...s.events, ...es]
        .filter((e, i, arr) => arr.findIndex(x => x.seq === e.seq) === i)
        .sort((a, b) => a.seq - b.seq);
      return { events: merged, bySeq };
    });
  },
  reset: () => set({ events: [], bySeq: new Map() }),
}));
```

### 4.4 React Query 缓存策略

```ts
// 本代码仅作学习材料参考
// src/api/hooks/useSession.ts
export function useSession(id: string) {
  return useQuery({
    queryKey: ['session', id],
    queryFn: () => api.get(`/sessions/${id}`),
    staleTime: 60_000,            // 1 分钟内不重发
    refetchOnWindowFocus: false,  // 22 章 Visibility 自管
  });
}

export function useApprovals() {
  return useQuery({
    queryKey: ['approvals'],
    queryFn: () => api.get('/approvals?status=pending'),
    refetchInterval: 30_000,      // 30 秒轮询一次（长程任务离线场景兜底）
  });
}
```

---

## 5. 代码分割与首屏

### 5.1 大依赖懒加载

```tsx
// 本代码仅作学习材料参考
// Monaco / mermaid / xterm 都不在首屏加载
const MonacoEditor = lazy(() => import('@monaco-editor/react').then(m => ({ default: m.default })));
const Mermaid = lazy(() => import('./Mermaid'));
const XTerm = lazy(() => import('./XTerm'));

function CodeArtifact({ url }: { url: string }) {
  return (
    <Suspense fallback={<Skeleton className="h-96" />}>
      <MonacoEditor path={url} />
    </Suspense>
  );
}
```

### 5.2 Vite manualChunks

```ts
// 本代码仅作学习材料参考
// vite.config.ts
export default defineConfig({
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'monaco': ['@monaco-editor/react'],
          'markdown': ['react-markdown', 'remark-gfm', 'rehype-raw'],
          'mermaid': ['mermaid'],
          'tanstack': ['@tanstack/react-query', '@tanstack/react-virtual'],
        },
      },
    },
    chunkSizeWarningLimit: 1500,
  },
});
```

### 5.3 预加载策略

```tsx
// 本代码仅作学习材料参考
// 用户 hover sidebar 上"任务"链接时，预取 chunk
<Link to="/tasks" onMouseEnter={() => import('./routes/TasksRoute')}>
  任务
</Link>
```

### 5.4 目标

| 指标 | 目标 |
|------|------|
| 首屏 JS（gzip） | < 200KB |
| 首屏 LCP | < 2s（4G） |
| 路由切换 | < 300ms（含 chunk 下载） |
| Monaco chunk | 单独，按需 |

---

## 6. ErrorBoundary

### 6.1 多层错误边界

```tsx
// 本代码仅作学习材料参考
// src/components/common/ErrorBoundary.tsx
import { ErrorBoundary as EB } from 'react-error-boundary';

function ArtifactErrorFallback({ error, resetErrorBoundary }: FallbackProps) {
  useEffect(() => {
    // 上报到 23 章 Sentry 通道
    reportFrontendError('artifact_render', error);
  }, [error]);
  return (
    <div className="p-4 text-red-600">
      <p>渲染失败：{error.message}</p>
      <Button onClick={resetErrorBoundary}>重试</Button>
    </div>
  );
}

export function ArtifactBoundary({ children }: { children: React.ReactNode }) {
  return (
    <EB FallbackComponent={ArtifactErrorFallback} onReset={() => location.reload()}>
      {children}
    </EB>
  );
}
```

### 6.2 嵌套边界

```
<RootErrorBoundary>           // 全页崩 → 显示 "出错了，刷新页面"
  <RouteErrorBoundary>        // 路由级崩 → 显示 "本页出错了，回首页"
    <ArtifactBoundary>        // 单 artifact 崩 → 仅替换该面板
      <MonacoEditor />
    </ArtifactBoundary>
    <ActivityBoundary>        // 活动流崩 → 仅替换时间线
      <ActivityFeed />
    </ActivityBoundary>
  </RouteErrorBoundary>
</RootErrorBoundary>
```

**关键**：内层边界吃掉错误，外层不白屏。Monaco 抛错时不影响用户继续发消息。

### 6.3 路由级 errorElement

```tsx
// 本代码仅作学习材料参考
function RootError() {
  const error = useRouteError();
  return (
    <div className="grid place-items-center h-screen">
      <Card>
        <p>页面异常</p>
        <pre className="text-xs">{String(error)}</pre>
        <Button onClick={() => location.href = '/'}>回首页</Button>
      </Card>
    </div>
  );
}
```

---

## 7. 移动端响应式

### 7.1 viewport + meta

`index.html`：

```html
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, viewport-fit=cover" />
<meta name="theme-color" content="#0a0a0a" />
<meta name="apple-mobile-web-app-capable" content="yes" />
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
```

### 7.2 Tailwind 断点

```ts
// 本代码仅作学习材料参考
// tailwind.config.ts
screens: {
  sm: '640px',    // 大手机横屏
  md: '768px',    // 平板
  lg: '1024px',   // 桌面
  xl: '1280px',   // 大桌面
},
```

### 7.3 布局适配

```tsx
// 本代码仅作学习材料参考
// 桌面：左侧栏 + 中间对话 + 右侧活动流
// 移动：底部 tab 切换，三栏变一栏
function SessionLayout({ children }: { children: React.ReactNode }) {
  const isDesktop = useMediaQuery('(min-width: 1024px)');
  const [mobileTab, setMobileTab] = useState<'chat' | 'activity' | 'artifact'>('chat');

  if (isDesktop) {
    return (
      <div className="grid grid-cols-[280px_1fr_360px] h-full">
        <Sidebar />
        <main className="overflow-y-auto">{children}</main>
        <aside><ActivityPanel /></aside>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 overflow-hidden">
        {mobileTab === 'chat' && children}
        {mobileTab === 'activity' && <ActivityPanel />}
        {mobileTab === 'artifact' && <ArtifactPanel />}
      </div>
      <nav className="flex border-t">
        {(['chat', 'activity', 'artifact'] as const).map(t => (
          <button
            key={t}
            onClick={() => setMobileTab(t)}
            className={cn('flex-1 py-2', mobileTab === t && 'bg-accent')}
            aria-current={mobileTab === t}
          >
            {t === 'chat' ? '对话' : t === 'activity' ? '活动' : '产物'}
          </button>
        ))}
      </nav>
    </div>
  );
}
```

### 7.4 触摸优化

- 点击区域 ≥ 44×44pt（iOS HIG）；
- 长按事件用 `onPointerDown` + 时长判断，不依赖 `onContextMenu`（移动端不可用）；
- 滚动用 `-webkit-overflow-scrolling: touch`；
- 输入框 `inputmode` / `enterkeyhint` 暗示键盘类型；
- `user-select: none` 防止误选；
- `touch-action: manipulation` 去掉 300ms 延迟。

### 7.5 审批卡片移动端

```tsx
// 本代码仅作学习材料参考
// 20 章的 ToolApprovalCard 在移动端要全屏
<Dialog>
  <DialogContent className={cn(
    'max-w-lg',
    'max-md:max-w-none max-md:h-screen max-md:rounded-none max-md:top-0'
  )}>
    {/* ... */}
  </DialogContent>
</Dialog>
```

---

## 8. 键盘快捷键

### 8.1 全局 hotkeys

| 快捷键 | 动作 |
|--------|------|
| `Cmd/Ctrl + K` | 打开命令面板 |
| `Cmd/Ctrl + /` | 聚焦输入框 |
| `Cmd/Ctrl + Enter` | 发送消息 |
| `Esc` | 取消当前 turn（10 章 abort） |
| `Cmd/Ctrl + Shift + A` | 切换活动流显示级别 |
| `Cmd/Ctrl + [` / `]` | 上一 / 下一 session |
| `?` | 显示快捷键帮助 |
| `g` 然后 `s` | 跳到 sessions（vim 风） |
| `g` 然后 `t` | 跳到 tasks |
| `g` 然后 `a` | 跳到 approvals |

### 8.2 实现

```ts
// 本代码仅作学习材料参考
// src/lib/keyboard.ts
import hotkeys from 'hotkeys-js';

export function useHotkeys() {
  useEffect(() => {
    hotkeys.filter = (event) => {
      // 输入框内只允许带 modifier 的快捷键
      const tag = (event.target as HTMLElement)?.tagName;
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(tag)) {
        return event.metaKey || event.ctrlKey;
      }
      return true;
    };

    hotkeys('mod+k', (e) => { e.preventDefault(); openCommandPalette(); });
    hotkeys('mod+/', (e) => { e.preventDefault(); focusInput(); });
    hotkeys('mod+enter', (e) => { e.preventDefault(); sendMessage(); });
    hotkeys('esc', () => abortTurn());
    hotkeys('mod+shift+a', () => toggleActivityLevel());

    // g 然后 s/t/a
    hotkeys('g', () => hotkeys.setScope('g'));
    hotkeys('s', 'g', () => navigate('/sessions'));
    hotkeys('t', 'g', () => navigate('/tasks'));
    hotkeys('a', 'g', () => navigate('/approvals'));

    return () => hotkeys.unbind();
  }, []);
}
```

### 8.3 命令面板

```tsx
// 本代码仅作学习材料参考
function CommandPalette() {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const commands = useCommands();

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Command>
        <CommandInput value={query} onValueChange={setQuery} placeholder="搜索命令、session、task..." />
        <CommandList>
          <CommandEmpty>无结果</CommandEmpty>
          {commands
            .filter(c => c.label.includes(query))
            .map(c => (
              <CommandItem key={c.id} onSelect={() => { c.run(); setOpen(false); }}>
                {c.icon} {c.label}
                <CommandShortcut>{c.shortcut}</CommandShortcut>
              </CommandItem>
            ))}
        </CommandList>
      </Command>
    </Dialog>
  );
}
```

---

## 9. i18n

### 9.1 入口

```ts
// 本代码仅作学习材料参考
// src/i18n/index.ts
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from './locales/zh-CN.json';
import enUS from './locales/en-US.json';

i18n.use(initReactI18next).init({
  resources: {
    'zh-CN': { translation: zhCN },
    'en-US': { translation: enUS },
  },
  lng: navigator.language,
  fallbackLng: 'zh-CN',
  interpolation: { escapeValue: false },
});

export default i18n;
```

### 9.2 文案 key 命名约定

```json
{
  "common": { "send": "发送", "cancel": "取消", "retry": "重试" },
  "session": { "title": "会话", "new": "新建会话" },
  "approval": {
    "tool": "工具审批",
    "options": {
      "allow": "允许",
      "allow_once": "本次允许",
      "deny": "拒绝"
    }
  }
}
```

### 9.3 使用

```tsx
const { t } = useTranslation();
<Button>{t('approval.options.allow')}</Button>
```

### 9.4 复数 / 性别

```json
{ "tasks_count": "{{count}} 个任务_one": "{{count}} 个任务_other": "{{count}} 个任务" }
```

---

## 10. 主题（深 / 浅 / 跟随系统）

### 10.1 next-themes

```tsx
// 本代码仅作学习材料参考
// src/main.tsx
import { ThemeProvider } from 'next-themes';

createRoot(document.getElementById('root')!).render(
  <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
    <RouterProvider router={router} />
  </ThemeProvider>
);
```

### 10.2 Tailwind dark mode

```ts
// 本代码仅作学习材料参考
// tailwind.config.ts
darkMode: 'class',
```

```tsx
<div className="bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100">
```

### 10.3 Monaco 主题同步

```tsx
// 本代码仅作学习材料参考
const { resolvedTheme } = useTheme();
<MonacoEditor theme={resolvedTheme === 'dark' ? 'vs-dark' : 'light'} />
```

### 10.4 切换无闪烁

```html
<!-- index.html head 内联脚本，提前应用主题 -->
<script>
  const t = localStorage.getItem('theme');
  if (t === 'dark' || (!t && matchMedia('(prefers-color-scheme: dark)').matches)) {
    document.documentElement.classList.add('dark');
  }
</script>
```

---

## 11. 加载状态与流式渲染

### 11.1 Suspense 分层

```tsx
// 本代码仅作学习材料参考
<Suspense fallback={<SessionSkeleton />}>
  <SessionView id={id}>
    <Suspense fallback={<ActivitySkeleton />}>
      <ActivityFeed />
    </Suspense>
    <Suspense fallback={<ArtifactSkeleton />}>
      <ArtifactPanel />
    </Suspense>
  </SessionView>
</Suspense>
```

### 11.2 流式 token 优化

17 章 model_stream 事件高频（每 token 一帧），需用 `useSyncExternalStore` + 节流：

```tsx
// 本代码仅作学习材料参考
function useStreamingText(sessionId: string) {
  return useSyncExternalStore(
    (cb) => activityStore.subscribe(cb),
    () => activityStore.getSnapshot().streamingText[sessionId],
  );
}

// 节流：每 50ms 才触发一次 re-render
const throttled = useMemo(() => throttle(setText, 50), []);
```

---

## 12. 性能基准

| 指标 | 目标 |
|------|------|
| 首屏 LCP | < 2s（4G） |
| 路由切换 INP | < 200ms |
| 1000 events 不卡 | CLS < 0.1，滚动 60fps |
| Monaco 首次加载 | < 1s（chunk 单独） |
| 内存（10 个 session） | < 200MB |

监控方案见 [23 章 §6 Core Web Vitals](./23-Web安全与可分享性.md)。

---

## 13. 可访问性 (a11y)

### 13.1 最低要求

- 所有交互元素可 Tab 聚焦，焦点可见；
- 用 `<button>` 不用 `<div onClick>`；
- 图标按钮带 `aria-label`；
- 表单字段带 `<label>`；
- Dialog 用 Radix / shadcn 自带 a11y；
- 颜色对比度 ≥ 4.5:1（WCAG AA）。

### 13.2 活动流的 a11y

```tsx
// 本代码仅作学习材料参考
<li role="listitem" aria-label={`${event.type} at ${event.at}`}>
  <EventCard event={event} />
</li>
```

### 13.3 屏幕阅读器实时播报

```tsx
// 本代码仅作学习材料参考
<div aria-live="polite" className="sr-only">
  {lastEventDescription}
</div>
```

---

## 14. 本章产出

```
前端：
  ✅ 目录结构（routes / components / stores / api / i18n）
  ✅ React Router 6 路由表（含懒加载）
  ✅ 状态管理分层（Zustand + React Query）
  ✅ Vite manualChunks + 路由懒加载
  ✅ 多层 ErrorBoundary
  ✅ 移动端响应式 + 触摸优化
  ✅ 全局键盘快捷键 + 命令面板
  ✅ i18n（zh-CN / en-US）
  ✅ 主题（深 / 浅 / 系统）
  ✅ Suspense 流式渲染优化
  ✅ a11y 基础
```

---

## 15. 对照：与前 20 章 demo 写法的差异

| 维度 | demo 写法（前 20 章）| 工程化（本章）|
|------|------------------|--------------|
| 状态 | 各组件 useState | Zustand 全局 + React Query 缓存 |
| 路由 | 单页 hash | React Router 6 + 懒加载 |
| 错误 | 整页白屏 | 三层 ErrorBoundary 兜底 |
| 首屏 | 一次加载 10s+ | manualChunks + Suspense |
| 移动端 | padding 写死 | 断点 + 触摸优化 |
| 多语言 | 中文写死 | i18n zh/en |
| 主题 | 写死浅色 | 深浅跟随系统 |
| 键盘 | 鼠标党 | 快捷键 + 命令面板 |
| 可访问性 | 无 | a11y 焦点 + aria |

**结论**：每条单独看都"小"，组合起来就是把 demo 升级为产品的门槛。

---

## 16. 避坑：前端工程化常踩的雷

| 雷 | 现象 | 规避 |
|----|------|------|
| 大依赖首屏炸 | Monaco / mermaid 同步加载 | §5 manualChunks + 懒加载 |
| ErrorBoundary 漂白 | 边界捕获后没 resetKey | 加 key 重置组件树 |
| Suspense 瀑布 | 嵌套 Suspense 串行 | 提前 prefetch + fallback |
| 移动端误触 | 按钮点击区域 < 44px | §7 触摸优化 + hit area |
| i18n 占位符错位 | `{name}` 与中英文语序冲突 | 用 ICU MessageFormat |
| 主题闪烁 | 首次刷新白闪 | `<script>` 内联 readSystemTheme |
| 键盘冲突 | 全局快捷键拦截到输入框 | §8 input/textarea/contenteditable 排除 |
| 懒加载 SSR 错位 | React.lazy 在 SSR 报错 | 用 IsomorphicLazy / 动态 import 客户端 |

---

## 17. 下一步

进入 [22-跨标签页与实时协作](./22-跨标签页与实时协作.md)，让多标签页 / 多人能同时观察同一 session，且状态一致。
