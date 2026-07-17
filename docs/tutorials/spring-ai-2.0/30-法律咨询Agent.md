# 30 行业实战 3：法律咨询 Agent（法条检索 / 合同审查 / 引用溯源）

> 法律是 LLM 的天然主场（语言密集 + 引用严格），但幻觉在法律场景零容忍。本文以法条检索、合同审查、案例检索为例，讲清法律 Agent 的核心：**引用溯源 + 拒答策略 + 律师在回路**。
>
> 前置：[`./07-RAG工程化实战.md`](./07-RAG工程化实战.md) + [`./13-安全工程与红队.md`](./13-安全工程与红队.md)
> 预计：2 天

---

## 0. 认知地图

```
法律 Agent 三大铁律
├── 引用必须可溯源（每句话都标注出处）
├── 不给"最终法律意见"（必须有律师签字）
└── 拒答优于幻觉（不知道就说不知道）

主流场景：
├── 法条检索：从庞大法库里找适用条款
├── 合同审查：找风险条款 / 缺漏
├── 案例检索：找类似判例
├── 法律咨询：面向普通用户的初步咨询
└── 文书起草：合同 / 起诉状 / 律师函
```

---

## 1. 合规约束

### 1.1 中国监管要点

- **律师法**：只有持证律师能提供法律意见。AI 不能独立执业。
- **司法部对 AI 法律服务的态度**：可作为辅助工具，不能替代律师。
- **数据合规**：客户数据属商业秘密 / 个人信息，需脱敏。

### 1.2 海外参考

- **美国 ABA Model Rule 1.1**：律师需保持胜任力（包括理解 AI 工具的限制）。
- **EU AI Act**：法律咨询 AI 列为有限风险，需透明告知用户。
- **2023 美国 Mata v. Avianca 案**：律师用 ChatGPT 编造判例被制裁——AI 幻觉在法律场景的典型教训。

### 1.3 合规铁律

```
1. Agent 输出明确标注"仅供参考，不构成法律意见"
2. 所有引用必须可点击跳转到原始法条 / 判例
3. 不确定时拒答（"我未找到明确依据，建议咨询律师"）
4. 用户问题涉及刑事 / 重大商事时强制人工介入
5. 客户数据脱敏 + 律师-客户特权保护
6. 全链路审计（保留期 5-10 年）
```

---

## 2. 法条检索 Agent

### 2.1 场景

```
用户：房东不退押金怎么办？
    ↓ Agent 检索
适用法条：
  - 《民法典》第 577 条（违约责任）
  - 《民法典》第 733 条（租赁合同终止）
  - 《最高人民法院关于审理城镇房屋租赁合同纠纷案件具体应用法律若干问题的解释》第 X 条
建议步骤：
  1. 协商
  2. 调解（社区 / 街道）
  3. 诉讼（小额诉讼程序）
免责声明：本回复仅供参考，具体问题请咨询执业律师。
```

### 2.2 RAG 架构

```
法律法规库（结构化）
    ├── 国家法律（全国人大）
    ├── 行政法规（国务院）
    ├── 司法解释（最高法 / 最高检）
    ├── 地方性法规
    └── 部门规章
    ↓ Hybrid Search（dense + sparse + 法条编号精确匹配）
    ↓ Reranker（bge-reranker-large）
    ↓ LLM 重组（必须引用 source_id）
```

### 2.3 数据准备的特殊性

法条库不只是文本，还有：

- **法条编号**（必须支持精确匹配："民法典 577" 直接命中）
- **生效 / 废止日期**（不能引用已废止法条）
- **修订历史**（同一法条不同版本表述不同）
- **关联关系**（"本法第 X 条" 引用链）

```sql
CREATE TABLE legal_articles (
    id VARCHAR(64),                  -- 唯一 ID（如 civil_code_577）
    law_name VARCHAR(128),           -- 法律名
    article_no VARCHAR(16),          -- 条号
    content TEXT,                    -- 条文内容
    effective_date DATE,
    repeal_date DATE,                -- NULL = 现行有效
    revision_of VARCHAR(64),         -- 修订前的 id
    level VARCHAR(16),               -- law/regulation/interpretation/local
    PRIMARY KEY (id)
);
```

检索时强制带 `repeal_date IS NULL`（除非用户明确要历史版本）。

### 2.4 Hybrid Search

```java
// 本代码仅作学习材料参考
public List<LegalArticle> searchLegal(String query) {
    // 1. 精确匹配（法条编号）
    List<LegalArticle> exact = legalRepo.findByArticleNo(query);
    
    // 2. 稠密检索（语义）
    List<LegalArticle> dense = vectorStore.search(query, topK=20);
    
    // 3. 稀疏检索（BM25，关键词 + 法律术语）
    List<LegalArticle> sparse = bm25Store.search(query, topK=20);
    
    // 4. RRF 融合
    List<LegalArticle> fused = rrfFuse(exact, dense, sparse);
    
    // 5. Reranker 精排
    return reranker.rerank(query, fused, topK=5);
}
```

### 2.5 LLM 重组（强制引用）

```java
// 本代码仅作学习材料参考
public LegalAnswer answer(String question, List<LegalArticle> articles) {
    return chatClient.prompt()
            .system("""
                    你是法律咨询助手。根据检索到的法条回答问题。
                    # 铁律
                    1. 每个法律断言必须以 [source:ID] 形式引用，引用必须来自上下文
                    2. 上下文没有的不要编造（"我没有找到相关法条"也是合法回答）
                    3. 不下"最终法律意见"，只给参考
                    4. 用户问题涉及刑事 / 重大标的时建议咨询律师
                    5. 必须在末尾输出 disclaimer
                    """)
            .user(u -> u.text("""
                    用户问题：{q}
                    
                    检索到的法条：
                    {articles}
                    """)
                    .param("q", question)
                    .param("articles", formatArticles(articles)))
            .call()
            .entity(LegalAnswer.class);
}

public record LegalAnswer(
        String summary,
        List<CitedStatement> statements,    // 每条都带 source_id
        String disclaimer,
        boolean recommendLawyer
) {}

public record CitedStatement(String text, String sourceId) {}
```

### 2.6 引用校验 Advisor

```java
// 本代码仅作学习材料参考
public class CitationValidationAdvisor implements BaseAdvisor {
    @Override
    public ChatClientResponse after(ChatClientResponse resp, AdvisorChain chain) {
        LegalAnswer answer = parseAnswer(resp);
        Set<String> validIds = getValidSourceIds(resp);
        
        for (CitedStatement s : answer.statements()) {
            if (!validIds.contains(s.sourceId())) {
                // 引用了不存在的 source，强制重写
                return retryWithWarning(resp, "Citation " + s.sourceId() + " not in context");
            }
        }
        return resp;
    }
}
```

引用没在上下文里就强制重写——根除幻觉。

---

## 3. 合同审查 Agent

### 3.1 场景

用户上传合同，Agent 标注风险条款 + 缺漏条款 + 不利条款，并给出修改建议。

### 3.2 实现

```java
// 本代码仅作学习材料参考
public record ContractReview(
        List<RiskClause> risks,
        List<String> missingClauses,
        List<Modification> modifications,
        String overallRiskLevel   // LOW / MEDIUM / HIGH
) {}

public record RiskClause(
        String clause,
        String clauseText,
        RiskType type,             // ILLEGAL / UNFAVORABLE / AMBIGUOUS
        String explanation,
        String legalBasis,         // 引用法律
        String suggestedRevision
) {}

public ContractReview review(String contractText, ContractContext ctx) {
    return chatClient.prompt()
            .system("""
                    你是合同审查助手。审查合同并标注：
                    1. 违法条款（必须引用法律依据）
                    2. 不利条款（风险点）
                    3. 歧义条款（需澄清）
                    4. 缺漏条款（重要但缺失）
                    
                    # 输出要求
                    - 每条 risk 必须有 legalBasis（引用法律条文）
                    - suggestedRevision 必须具体（不是"建议修改"）
                    - 不知道的法律依据就标 "need_lawyer_review"
                    """)
            .user(u -> u.text("""
                    合同类型：{type}
                    我方角色：{role}
                    合同全文：{contract}
                    """)
                    .param("type", ctx.type())
                    .param("role", ctx.role())
                    .param("contract", contractText))
            .tools(
                contractTemplateTool,    // 标准合同模板
                legalSearchTool,         // 法条检索
                caseLawTool              // 类似判例
            )
            .call()
            .entity(ContractReview.class);
}
```

### 3.3 律师 review

Agent 输出后必须律师 review：

```
[Agent 输出]
风险条款 1：第 X 条违反《合同法》第 Y 条
    suggestedRevision: ...
    
[律师 UI]
✓ 采纳          ✗ 不采纳（写理由）        ✏️ 修改
    
[最终意见]
律师签字 + 出具法律意见书
```

---

## 4. 案例检索 Agent

### 4.1 场景

律师 / 法务："找最近 3 年北京法院关于竞业协议违约金调整的判例"。

### 4.2 判例库的特殊性

判例库（中国裁判文书网 / Westlaw / LexisNexis）的特点：

- 长文本（一份判决书几万字）
- 多字段（当事人 / 法院 / 案由 / 判决结果 / 法官 / 律师）
- 时间敏感（旧判例参考价值低）
- 等级敏感（最高法 > 高院 > 中院 > 基层）

### 4.3 多阶段检索

```
1. 元数据过滤（court_level, date_range, cause_of_action）
2. 稠密检索（语义）
3. Reranker（按"判决要旨"段落精排）
4. LLM 抽取要点（"本案判决要旨：..."）
```

### 4.4 判例要点抽取

```java
// 本代码仅作学习材料参考
public record CaseSummary(
        String caseId,
        String court,
        LocalDate date,
        String causeOfAction,
        List<String> keyFacts,
        String legalReasoning,
        String judgment,
        Double similarityToQuery       // 与用户查询的相似度
) {}

public List<CaseSummary> summarize(List<CaseDoc> docs, String userQuery) {
    return chatClient.prompt()
            .system("""
                    对每个判例抽取关键要点，输出结构化摘要。
                    # 注意
                    - 不篡改判决内容
                    - keyFacts 必须忠于原文
                    - judgment 字段必须是判决主文原话
                    """)
            .user(docs.toString())
            .call()
            .entity(CaseSummary[].class).length > 0 ? List.of(...) : List.of();
}
```

---

## 5. 法律咨询 Chatbot（面向 C 端用户）

### 5.1 风险最高的场景

普通用户问"我这种情况能赢官司吗？"——Agent 不能保证胜诉。

### 5.2 防御设计

```
1. 任何回答末尾强制 disclaimer
2. 不预测胜诉概率（"无法预测，请咨询律师"）
3. 不给具体诉讼策略（只给参考法律依据）
4. 引导用户咨询律师（在 UI 上加"联系律师"按钮）
5. 不收"咨询费"（避免构成法律服务关系）
```

### 5.3 UI 设计

```
Agent 回答
    ↓
免责声明（折叠）
    ↓
"本回答仅供参考，不构成法律意见"
    ↓
[联系律师] 按钮（跳转律所合作）
```

---

## 6. 引用溯源（核心工程）

### 6.1 每条断言带 source_id

LLM 输出格式约束：

```json
{
  "statements": [
    {"text": "房东违约应承担违约责任", "sourceId": "civil_code_577"},
    {"text": "租赁合同终止后承租人应返还租赁物", "sourceId": "civil_code_733"}
  ]
}
```

### 6.2 Source 验证

```java
// 本代码仅作学习材料参考
public class SourceValidator {
    public ValidationResult validate(LegalAnswer answer, RetrievalContext ctx) {
        Set<String> contextIds = ctx.getRetrievedArticleIds();
        
        for (CitedStatement s : answer.statements()) {
            if (!contextIds.contains(s.sourceId())) {
                log.warn("Hallucinated citation: {}", s.sourceId());
                return ValidationResult.fail("Citation out of context: " + s.sourceId());
            }
            
            // 检查引用内容是否真的对应那条法律
            LegalArticle article = legalRepo.findById(s.sourceId());
            if (!isSemanticallyRelated(s.text(), article.content())) {
                return ValidationResult.fail("Citation mismatch: " + s.sourceId());
            }
        }
        
        return ValidationResult.ok();
    }
}
```

### 6.3 UI 上点击跳转

```html
<span class="cited">
  房东违约应承担违约责任
  <a href="/law/civil_code_577">[民法典 577]</a>
</span>
```

用户可点开看法条原文，验证 Agent 说得对不对。

---

## 7. 拒答策略

### 7.1 何时该拒答

- 检索 recall 太低（top-1 相似度 < 0.5）
- 用户问题超出系统知识范围（如外国法律）
- 涉及刑事辩护（必须律师）
- 涉及具体诉讼策略

### 7.2 拒答模板

```java
// 本代码仅作学习材料参考
public LegalAnswer refuse(String reason) {
    return new LegalAnswer(
        "抱歉，我无法准确回答这个问题。原因：" + reason + "。建议咨询执业律师。",
        List.of(),
        DISCLAIMER,
        true    // recommendLawyer
    );
}

if (topSimilarity < 0.5) {
    return refuse("未检索到明确适用法条");
}
if (isCriminalCase(question)) {
    return refuse("刑事案件必须由律师代理");
}
```

### 7.3 拒答率监控

拒答率太高（>30%）= RAG 召回不足；拒答率太低（<5%）= 可能强答。目标 10-20%。

---

## 8. 模型选型

### 8.1 推荐

| 用途 | 模型 |
|------|------|
| 主对话 | Qwen2.5-72B / DeepSeek-V3（中文法律强） |
| 长文档（合同） | Qwen2.5-72B（32K context）/ Kimi（200 万 context） |
| Embedding | bge-m3（中文法律） |
| Reranker | bge-reranker-large |

### 8.2 微调

- 用真实法律 QA 数据 SFT
- 用律师标注的"好回答 / 差回答" DPO
- 法律术语词典增强 embedding

### 8.3 评估

| 指标 | 计算 |
|------|------|
| **citation_precision** | 引用正确的 source 占比 |
| **citation_recall** | 应引用但没引用的占比 |
| **faithfulness** | 答案是否忠于源 |
| **answer_correctness** | 律师评分 |
| **refusal_rate** | 拒答比例 |

---

## 9. 反模式速查

| 反模式 | 后果 | 修复 |
|--------|------|------|
| LLM 输出无 source 标注 | 不可信 | 强制 [source:ID] 格式 |
| 引用未验证 | 编造法条 | CitationValidationAdvisor |
| 引用已废止法律 | 法律错误 | effective/repeal_date 过滤 |
| 不区分国家 / 法系 | 适用错误 | 显式声明适用区域 |
| 涉刑事不引导律师 | 监管风险 | 强制 recommendLawyer |
| 缺 disclaimer | 法律责任 | 强制末尾 disclaimer |
| 用商业 API 处理客户数据 | 商业秘密泄漏 | 私有化 |
| 预测胜诉率 | 误导用户 | 不预测 |

---

## 10. 实战任务

1. 搭建一个最小法条库（500 条），实现 Hybrid Search（dense + sparse + 编号精确匹配）。
2. 实现"强制引用 + Citation 校验" advisor，根除幻觉。
3. 设计拒答策略：top-K 相似度阈值 + 关键词触发。
4. 实现合同审查 Agent，含律师 review UI（mock 即可）。
5. 评估 citation_precision / faithfulness，目标 > 0.95。
6. （进阶）实现判例库检索 + 要点抽取，跑 100 个真实判例。
7. （选做）调研 Westlaw / 北大法宝 的检索算法，对比本文方案。

---

## 11. 理解检查

1. 为什么法律 Agent 必须强制引用 source_id？
2. CitationValidationAdvisor 解决什么问题？
3. 判例库的元数据过滤为什么是必要的？
4. 何时该拒答？目标拒答率是多少？
5. 法律场景的 disclaimer 应包含哪些要素？
6. 律师在回路在合同审查里怎么落地？

---

## 12. 相关文档

- [`./07-RAG工程化实战.md`](./07-RAG工程化实战.md) —— RAG 基础
- [`./19-RAG高级篇.md`](./19-RAG高级篇.md) —— Hybrid Search / RRF
- [`./13-安全工程与红队.md`](./13-安全工程与红队.md) —— Prompt Injection
- [`./22-Prompt工程深入.md`](./22-Prompt工程深入.md) —— 结构化输出
- [`./11-评估闭环与Prompt版本管理.md`](./11-评估闭环与Prompt版本管理.md) —— 引用指标评估
- [中国裁判文书网](https://wenshu.court.gov.cn/)
- [北大法宝](https://www.pkulaw.com/)
- [Westlaw](https://legal.thomsonreuters.com/en/westlaw)
- [Mata v. Avianca 案例](https://en.wikipedia.org/wiki/Mata_v._Avianca)（AI 幻觉警示）

---

回到 [`./00-目录索引.md`](./00-目录索引.md)。
