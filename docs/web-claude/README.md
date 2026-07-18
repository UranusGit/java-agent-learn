# 网页版 Claude 与长程任务平台 - 目录索引

> 本系列是「网页版 Claude + 长程任务能力」实践项目的完整实验手册。
> 配套：调研笔记 + 项目设计 + 20 章实操 + 附录。
> 阅读对象：Java 工程师，熟悉 Spring AI / Agent / Web 开发。

## 阅读路径

```
Phase 0  调研与设计    00 → 01
Phase 1  MVP 骨架      02 → 09（v1.0 单租户）
Phase 2  企业接入      10 → 12（v1.1 多租户 + 长程任务 + Hooks）
Phase 3  机制深化      13 → 20（v1.2+ 完整工程范式）
Phase 4  Web 工程化    21 → 27（v2.0 Web 项目专属 + 安全 + 生产部署）
Phase 5  商业化（P0）  28 → 34（v2.1 限流/计费/多租户产品/合规，跳 30/32/33）
```

---

## 完整章节地图

### Phase 0：调研与设计

| # | 主题 | 重点 |
|---|------|------|
| [00](./00-调研笔记.md) | 调研笔记 | CC 源码 6 章精读 + 官方文档三件套 + 业界对照 + 复用 ai-serving |
| [01](./01-项目设计.md) | 项目设计 | 3 候选方案 + v1 架构 + 数据模型 + MVP 切片 + 模块图（含 Harness / Subagent / MCP / Events）|

### Phase 1：v1.0 MVP 骨架（单租户能跑通）

| # | 主题 | 关键产物 |
|---|------|----------|
| [02](./02-环境准备.md) | 环境准备 | JDK21 / Spring Boot 4 / Docker / 前端 / MinIO |
| [03](./03-项目骨架.md) | 项目骨架 | WS + ping/pong + 最简 AgentLoop |
| [04](./04-Agent-Loop.md) | Agent Loop 深化 | State + 5 终止条件 + 3 层中断 |
| [05](./05-工具系统与权限.md) | 工具系统与权限 | 5 内置工具 + 6 模式 + 3 行为 + DSL |
| [06](./06-沙箱接入.md) | 沙箱接入 | Docker 容器调度 + 隔离 + 安全加固 |
| [07](./07-Session持久化.md) | Session 持久化 | JSONL DAG + DB 索引 + Compact boundary |
| [08](./08-WebSocket重连.md) | WebSocket 重连 | seq + 断线重放 |
| [09](./09-Artifacts.md) | Artifacts | 代码/MD/HTML/SVG/Mermaid 渲染 |

### Phase 2：v1.1 企业接入

| # | 主题 | 关键产物 |
|---|------|----------|
| [10](./10-集成ai-serving.md) | 集成 ai-serving | 推理网关 + 多租户 + 配额 + OTel |
| [11](./11-长程任务.md) | 长程任务 | feature_list + Cron（Harness 最简实例）|
| [12](./12-Hooks系统.md) | Hooks 系统 | 事件 + http hook + defer |

### Phase 3：v1.2+ 机制深化（核心工程范式）

| # | 主题 | 关键产物 | 备注 |
|---|------|----------|------|
| [13](./13-Harness工程.md) | Harness 工程 | 5 层模型 + Guardrails + Protocol + Multi-Agent | harness 完整范式 |
| [14](./14-上下文工程.md) | 上下文工程 | 4 级压缩管线 + Prompt 装配 + fallback | **决定长程任务能否跑完** |
| [15](./15-Subagent编排.md) | Subagent 编排 | 4 种模式 + DAG 调度 + 隔离 + 聚合 | **长程任务提速** |
| [16](./16-MCP协议集成.md) | MCP 协议集成 | 3 种 transport + ToolRegistry 接入 | **工具生态** |
| [17](./17-全链路可观测前端.md) | 全链路可观测前端 | 12 类事件 + 活动流 + 3 档显示级别 | **解决用户焦虑** |
| [18](./18-错误恢复与重试.md) | 错误恢复与重试 | 分类 + 重试 + fallback + 对账 + 断路器 | **长程任务自愈** |
| [19](./19-AskUser与澄清式交互.md) | AskUser 澄清交互 | 5 种 question kind + 挂起-恢复 + 超时降级 | **对话式收敛** |
| [20](./20-审批与审核流.md) | 审批与审核流 | 工具审批 + Diff Review + 多角色 + 审批中心 | HITL 全场景 |

### Phase 4：v2.0 Web 工程化（Web 项目专属）

| # | 主题 | 关键产物 | 备注 |
|---|------|----------|------|
| [21](./21-Web前端工程化.md) | Web 前端工程化 | 路由 / Zustand / 代码分割 / ErrorBoundary / 移动端 / 快捷键 / i18n / 主题 | **产品化前端** |
| [22](./22-跨标签页与实时协作.md) | 跨标签页与实时协作 | BroadcastChannel / SharedWorker / Visibility / IndexedDB / presence | **Web 实时性** |
| [23](./23-Web安全与可分享性.md) | Web 安全与可分享性 | 注入防护 / artifact XSS / CSP / 分享链接 / Sentry / Web Vitals | **Web 安全 + 分享** |
| [24](./24-智能体安全.md) | 智能体安全 | Provenance / SecretGuard / PromptInjection 检测 / Subagent guardrail | **Agent 专属风险** |
| [25](./25-记忆与个性化系统.md) | 记忆与个性化 | 跨 session 偏好 / CLAUDE.md 导入 / 任务经验复用 / GDPR | **CLAUDE.md 等价物** |
| [26](./26-测试与评估.md) | 测试与评估 | 单元 / 集成 / Playwright / Eval 框架 / LLM Judge / CI 门禁 | **质量门禁** |
| [27](./27-生产部署深度.md) | 生产部署深度 | K8s / HPA / WS 跨节点 / 沙箱调度 / 归档 / SLO / DR | **集群生产** |

### Phase 5：v2.1 商业化（P0 已写，P1 待补）

| # | 主题 | 关键产物 | 状态 |
|---|------|----------|------|
| [28](./28-限流与防滥用.md) | 限流与防滥用 | 多维度限流 / 注册防爆破 / 邀请制 / 公平调度 / 分享防遍历 | ✅ P0 |
| [29](./29-成本治理与计费.md) | 成本治理与计费 | 多级预算 / 模型路由 / 实时余额 / Stripe / 发票 / 退款 | ✅ P0 |
| 30 | 多供应商 / 模型路由 / 跨家灾备 | Anthropic/OpenAI/国产/自托管切换 | P1 待写 |
| [31](./31-多租户产品层.md) | 多租户产品层 | tenant/project/角色/邀请/SSO/SCIM/跨租户迁移 | ✅ P0 |
| 32 | 国际化与合规地理 | 数据驻留 / 多语言 / RTL / 审查 | P1 待写 |
| 33 | 开发者生态 | Chrome 扩展 / VS Code / CLI / SDK / Webhook | P1 待写 |
| [34](./34-数据治理与合规审计.md) | 数据治理与合规审计 | 加密 / KMS / 审计 / SOC2 / GDPR / 事件响应 | ✅ P0 |
| 35 | 版本兼容 | API 版本化 / schema 演进 / 迁移 | P1 待写 |
| 36 | 用户引导 / 模板库 | Onboarding / 模板 / 教程 / 反馈通道 | P1 待写 |
| 37 | 沙箱到生产 / Git 集成 | 自动 PR / CI/CD / Code Review | P1 待写 |

### 附录

| # | 主题 |
|---|------|
| [99](./99-附录-部署与排错.md) | docker-compose + 性能调优 + 监控告警 + 安全清单 |

---

## 阅读约定

1. **代码块标注**：所有代码顶部带 `// 本代码仅作学习材料参考`，需用户**自己手写**到 `src/main/java`。
2. **不可跳过**：02-09 是 v1.0 硬核，必须按序；13-20 是机制深化，强烈推荐。
3. **可选**：10-12 视企业需求选做。
4. **前置依赖**：
   - Java 21 + Spring Boot 4 + Spring AI 2.0 基础；
   - React + TypeScript 基础；
   - Docker；
   - 读完 Spring AI 2.0 系列（同仓 `docs/spring-ai-2.0/`）。

---

## 章节依赖图

```
00 调研 → 01 设计
              ↓
02 环境 → 03 骨架 → 04 Agent Loop → 05 工具 → 06 沙箱 → 07 持久化 → 08 WS 重连 → 09 Artifacts
              ↓                                                                    
              └─────────────→ 17 全链路可观测（贯穿所有章节）
                              
10 集成 ai-serving → 11 长程任务 → 12 Hooks
                       ↓             ↓
                       ↓             ↓
13 Harness ─────→ 14 上下文工程 ──→ 18 错误恢复
                       ↓                
15 Subagent ─────→ 16 MCP 协议       
                                  
19 AskUser ──────→ 20 审批流

21 前端工程化 ──→ 22 跨标签页与协作 ──→ 23 Web 安全与分享
                                          ↓
24 智能体安全 ──→ 25 记忆系统
                                          
26 测试与评估 ──→ 27 生产部署深度
```

**17 章是横切关注点**：所有后端章节的 Agent Loop / 工具 / Hooks / 压缩 / 错误都要 emit 事件给 17 章。

**21-23 章是 Web 工程化横切**：21 给前 20 章前端代码"补地基"，22 加实时协作能力，23 加 Web 安全 / 分享。

**24-25 章是 Agent 维度的扩展**：智能体安全 + 记忆系统，与 05/14 章强相关。

**26-27 章是交付保障**：测试金字塔 + 生产级部署。

**28-34 章是商业化（P0 已写）**：限流防滥用 + 计费 + 多租户产品 + 合规审计，直接决定能否商用。

**30/32/33/35/36/37（P1 待写）**：多供应商 / 国际化 / 开发者生态 / 版本兼容 / 用户引导 / 沙箱到生产，差异化与体验。

---

## P0 / P1 / P2 分级

### P0（机制层，必须）

长程任务跑不通就不算"网页版 Claude"：

- 13 Harness 工程
- 14 上下文工程
- 15 Subagent 编排
- 16 MCP 协议集成
- 17 全链路可观测前端
- 18 错误恢复与重试
- 19 AskUser 澄清
- 20 审批与审核流

### P0（Web 项目维度，必须）

Web 项目跑不通就不算"Web 产品"：

- 21 Web 前端工程化
- 22 跨标签页与实时协作
- 23 Web 安全与可分享性
- 24 智能体安全
- 25 记忆与个性化系统
- 26 测试与评估
- 27 生产部署深度

### P1（产品完整性，建议）

- Git Worktree 与代码集成
- 多模态输入
- Prompt Caching 与成本优化
- RAG 与向量检索
- 实时协同编辑（Yjs）
- 移动端 PWA

### P2（企业级，按需）

- 配额与限流深度
- 成本归集与计费
- 审计与合规深度
- 可观测性深度
- Firecracker 沙箱
- 多区域 active-active

---

## 项目仓库结构（实验完成后）

```
demo01/
├── pom.xml
├── src/main/java/org/demo02/
│   ├── webclaude/
│   │   ├── WebClaudeApplication.java
│   │   ├── agent/                # Agent Loop + State
│   │   ├── tool/                 # Tool Registry + 内置工具
│   │   ├── permission/           # Permission Middleware
│   │   ├── session/              # Session 持久化（JSONL+DB）
│   │   ├── sandbox/              # Docker 沙箱
│   │   ├── artifact/             # Artifact 服务
│   │   ├── task/                 # 长程任务
│   │   ├── harness/              # Harness 工程（13 章）
│   │   ├── context/              # 上下文压缩（14 章）
│   │   ├── subagent/             # Subagent 编排（15 章）
│   │   ├── mcp/                  # MCP 集成（16 章）
│   │   ├── event/                # 事件总线（17 章）
│   │   ├── recovery/             # 错误恢复（18 章）
│   │   ├── human/                # HITL（19/20 章）
│   │   ├── hook/                 # Hook 系统（12 章）
│   │   ├── inference/            # ai-serving 网关客户端
│   │   ├── security/             # JWT / 多租户上下文（10 章）/ Agent 安全（24 章）
│   │   ├── memory/               # 记忆系统（25 章）
│   │   ├── eval/                 # Eval 框架（26 章）
│   │   └── ws/                   # WebSocket（08 章 + 22 章跨节点广播）
│   ├── config/
│   └── tools/                    # 已有 TimeTools
├── frontend/
│   ├── src/
│   │   ├── routes/               # 21 章路由
│   │   ├── stores/               # 21 章 Zustand
│   │   ├── i18n/                 # 21 章 i18n
│   │   ├── lib/
│   │   │   ├── crossTab.ts       # 22 章 BroadcastChannel
│   │   │   ├── offlineQueue.ts   # 22 章离线队列
│   │   │   ├── eventDb.ts        # 22 章 IndexedDB
│   │   │   └── urlSanitize.ts    # 23 章 URL 净化
│   │   └── e2e/                  # 26 章 Playwright
│   └── tests/                    # 26 章单元 + 组件测试
├── docs/
│   ├── spring-ai-2.0/            # 已有
│   ├── ai-serving/               # 已有
│   └── web-claude/               # 本系列
├── k8s/                          # 27 章 Helm chart
│   ├── charts/webclaude/
│   └── values-prod.yaml
└── docker/
    ├── sandbox/                  # 沙箱镜像
    ├── backend.Dockerfile
    ├── frontend.Dockerfile
    ├── nginx.conf
    └── compose.yaml
```

---

## 实验检查点

每章末尾有「检查点」，形如：

> **检查点 03-1**：浏览器中能看到流式回复。

通过当前检查点，才进入下一章。

---

## 反馈与勘误

- 先看 `99-附录-部署与排错.md`；
- 再看对应章节末尾的「常见问题」；
- 对照 `00-调研笔记.md` 找原始出处。

---

**开始实验 → [02-环境准备](./02-环境准备.md)**
