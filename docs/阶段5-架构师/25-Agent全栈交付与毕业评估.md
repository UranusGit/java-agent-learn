# 25 · Agent 全栈交付与毕业评估

> 阶段：5 架构师 · 难度：⭐⭐⭐⭐⭐ · 预计：2 天
> 前置：[24 Agent 技术中台与能力开放](24-Agent技术中台与能力开放.md)
> 产出：掌握 Agent 项目的全栈交付方法论——从设计到上线到运维的完整能力闭环

---

## 你将学会

- Agent 全栈交付的完整清单
- 毕业项目评估标准（技术 + 业务 + 工程三大维度）
- Agent 架构师能力模型
- 持续进阶路线

---

## 全栈交付清单

```mermaid
flowchart TB
    subgraph Design["设计阶段"]
        D1["需求分析"]
        D2["架构设计（ADR）"]
        D3["Prompt 设计"]
        D4["评估集设计"]
    end

    subgraph Build["构建阶段"]
        B1["Agent 核心开发"]
        B2["工具开发"]
        B3["知识库建设"]
        B4["安全防护"]
        B5["前端开发"]
    end

    subgraph Test["测试阶段"]
        T1["单元测试"]
        T2["集成测试"]
        T3["Golden Set 评估"]
        T4["安全测试"]
        T5["性能测试"]
    end

    subgraph Deploy["部署阶段"]
        DP1["Docker 镜像"]
        DP2["K8s 部署"]
        DP3["灰度发布"]
        DP4["监控接入"]
    end

    subgraph Operate["运维阶段"]
        O1["SLO 监控"]
        O2["成本追踪"]
        O3["反馈闭环"]
        O4["持续优化"]
    end

    Design --> Build --> Test --> Deploy --> Operate
    Operate -.反馈.-> Design
```

---

## 知识讲解

### 1. 毕业项目评估标准

```mermaid
mindmap
  root((毕业评估))
    技术维度（40%）
      Agent 核心能力
        多轮对话 ✓
        工具调用 ✓
        RAG 检索 ✓
        流式输出 ✓
      架构能力
        多 Agent 编排 ✓
        微服务拆分 ✓
        数据架构 ✓
      可靠性
        熔断降级 ✓
        重试容错 ✓
        灾备方案 ✓
      安全性
        内容审核 ✓
        注入防御 ✓
        数据脱敏 ✓
    业务维度（30%）
      用户体验
        首响应 < 1s ✓
        满意度 > 85% ✓
      评估指标
        Faithfulness > 0.85 ✓
        Recall@5 > 0.80 ✓
      可量化成果
        自动化率 ✓
        成本节省 ✓
    工程维度（30%）
      代码质量
        单测覆盖 > 70% ✓
        代码审查 ✓
      CI/CD
        自动化流水线 ✓
        评估门禁 ✓
      文档
        API 文档 ✓
        架构文档 ✓
        运维手册 ✓
      可观测
        全链路追踪 ✓
        指标看板 ✓
        告警就绪 ✓
```

### 2. 架构师能力模型

```mermaid
flowchart LR
    subgraph L1["L1 工程师（能做）"]
        L1A["Spring AI 基础开发"]
        L1B["单 Agent 实现"]
        L1C["基础 RAG"]
    end

    subgraph L2["L2 高级工程师（能设计）"]
        L2A["多 Agent 编排"]
        L2B["生产级可靠性"]
        L2C["成本优化"]
    end

    subgraph L3["L3 架构师（能决策）"]
        L3A["架构选型决策"]
        L3B["平台化设计"]
        L3C["技术战略"]
    end

    subgraph L4["L4 技术负责人（能引领）"]
        L4A["技术路线规划"]
        L4B["团队赋能"]
        L4C["行业影响力"]
    end

    L1 --> L2 --> L3 --> L4
```

### 3. 量化指标体系

```java
package demo.demo05.graduation;

/**
 * 毕业项目指标体系
 */
public class ProjectMetrics {

    // ===== 技术指标 =====

    /** 检索准确率 */
    public double recallAt5;        // 目标 > 0.80
    /** 回答忠实度 */
    public double faithfulness;     // 目标 > 0.85
    /** 回答相关性 */
    public double relevance;        // 目标 > 0.85
    /** P95 首token延迟 */
    public double p95FirstTokenMs;  // 目标 < 1000
    /** P95 完整响应延迟 */
    public double p95CompleteMs;    // 目标 < 10000

    // ===== 业务指标 =====

    /** 自动解决率（不需要人工） */
    public double autoResolveRate;  // 目标 > 60%
    /** 用户满意度 */
    public double satisfactionRate; // 目标 > 85%
    /** 平均对话轮数 */
    public double avgTurns;         // 目标 < 8
    /** 周活跃用户 */
    public int weeklyActiveUsers;
    /** 日均对话量 */
    public int dailyConversations;

    // ===== 成本指标 =====

    /** 单次对话平均成本 */
    public double avgCostPerChat;   // 目标 < $0.05
    /** 月度总成本 */
    public double monthlyTotalCost;
    /** 对比纯人工的成本节省 */
    public double costSavingsRate;  // 目标 > 70%

    // ===== 工程指标 =====

    /** 单测覆盖率 */
    public double testCoverage;     // 目标 > 70%
    /** CI/CD 流水线通过率 */
    public double pipelinePassRate; // 目标 > 95%
    /** 平均故障恢复时间 */
    public double mttrMinutes;      // 目标 < 30
    /** SLA 可用率 */
    public double slaAvailability;  // 目标 > 99.9%

    /**
     * 综合评分
     */
    public double overallScore() {
        double techScore = (recallAt5 + faithfulness + relevance) / 3 * 40;
        double bizScore = (autoResolveRate + satisfactionRate) / 2 * 30;
        double engScore = (testCoverage + slaAvailability) / 2 * 30;
        return techScore + bizScore + engScore;
    }
}
```

### 4. 3 分钟项目演示模板

```mermaid
flowchart LR
    M1["0:00-0:30<br/>痛点与方案<br/>'客服日均 5000 咨询<br/>人工处理需 20 人'"] --> M2["0:30-1:00<br/>架构亮点<br/>'多Agent + RAG + 全链路追踪'"]
    M2 --> M3["1:00-2:00<br/>现场演示<br/>'上传文档 → 提问 → 流式回答'"]
    M3 --> M4["2:00-2:30<br/>量化成果<br/>'自动化率 65%<br/>成本节省 75%'"]
    M4 --> M5["2:30-3:00<br/>展望<br/>'下一步：插件生态 + 多语言'"]
```

---

## 简历级量化指标模板

| 指标 | 写法 |
|------|------|
| 性能 | "P95 延迟 < 800ms，支持 500 QPS 并发" |
| 准确率 | "Faithfulness 0.87，Recall@5 0.83" |
| 成本 | "单次对话成本 $0.03，月节省 $15K" |
| 规模 | "日均 10K 对话，服务 50K 用户" |
| 可靠性 | "SLA 99.95%，MTTR 12 分钟" |
| 自动化 | "自动化解决率 68%，转人工率 15%" |

---

## 常见坑

- ❌ **只做功能不管指标** → 功能做完了但没有量化评估，无法证明价值
- ❌ **毕业项目太简单** → 只是调用 ChatClient，没有架构深度。需要多 Agent + RAG + 生产化
- ❌ **没有安全防护** → Agent 可以被 Prompt Injection 攻破，敏感数据泄露
- ❌ **不能一键部署** → 评审时要手动搭半天环境。必须 Docker Compose 一键启动
- ❌ **演示只展示 Happy Path** → 只演示正常流程，不展示异常处理和降级

---

## 验收检查

- [ ] 毕业项目通过三大维度评估（技术 40% + 业务 30% + 工程 30%）
- [ ] 有 3 个以上简历级量化指标
- [ ] 3 分钟演示能完整展示价值
- [ ] Docker Compose 一键部署成功
- [ ] 评估集 faithfulness > 0.85
- [ ] 全链路可观测（Trace + Metrics + Logs）
- [ ] 安全检查清单全部通过

---

## 持续进阶路线

```mermaid
flowchart TD
    Grad["毕业 🎓"] --> Path1["路径1：纵深<br/>Agent SRE / 平台工程"]
    Grad --> Path2["路径2：横向<br/>AI 产品架构师"]
    Grad --> Path3["路径3：前沿<br/>AI 研究 / 开源"]
    Grad --> Path4["路径4：创业<br/>AI 应用创业"]

    Path1 --> A1["→ 深耕可靠性<br/>→ 混沌工程<br/>→ 多活灾备"]
    Path2 --> A2["→ 产品策略<br/>→ 多模态交互<br/>→ 行业 AI"]
    Path3 --> A3["→ 论文 / 开源<br/>→ 模型优化<br/>→ AGI 研究"]
    Path4 --> A4["→ 找 PMF<br/>→ MVP → PMF<br/>→ 规模化"]

    style Grad fill:#4caf50,color:#fff
```

---

## 下一步

→ 阶段 6 前沿：[17 Agent 神经符号系统](../阶段6-前沿/17-Agent神经符号系统.md)
