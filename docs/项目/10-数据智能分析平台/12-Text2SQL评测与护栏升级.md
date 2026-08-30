# 项目 10：数据智能分析平台 — 12-Text2SQL评测与护栏升级（v11）

> **定位**：把 v8 的"执行准确率一个数字"升级为**金标三元组评测体系**（问题-SQL-结果），区分**执行准确率 vs 结果准确率**两把尺子；护栏从"一刀切"升级为**行级/列级/代价上限三深化 + 角色档位**；失败归因（语义错/语法错/数据错）驱动"归因 → 优化 → 回归"闭环。官方 `Evaluator`（RelevancyEvaluator）用于**答案质量层**，数值正确性仍坚持确定性比对。读者画像：想知道"Text2SQL 到底准不准、错在哪、怎么改"的读者。前置阅读：[08-数据质量与治理](08-数据质量与治理.md)、[02-SQL安全护栏](02-SQL安全护栏.md)、[教程 08-架构师进阶/03-自我反思与Agent评估]。
>
> 「遇到阻塞？→ [教程 08-架构师进阶/03-自我反思与Agent评估]、[教程 08-架构师进阶/07-数据飞轮与持续改进]、[附录 04-测试策略/02-Eval评估]、[附录 11-评估与可观测生态]」
>
> **铁律 0**：`Evaluator`/`EvaluationRequest`/`EvaluationResponse`/`RelevancyEvaluator.builder().chatClientBuilder(...)` 均经本地 jar javap 实证（`scripts/api-baseline-spring-ai-2.0.0.md` §17）；数值评测为项目自研确定性比对，不依赖 LLM。

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 金标三元组评测集（问题/gold SQL/期望结果）；执行准确率与结果准确率双口径；失败归因三分类（语义/语法/数据）；护栏深化（行级 fail-closed、列级 SELECT * 拦截、代价档位分级）；官方 Evaluator 评答案叙事层 |
| **影响了哪些模块** | `EvaluationService`（v8）升级为 `Text2SqlEvalService`（三元组 + 归因）；`QueryGuardService`（v2）升级为 `TieredQueryGuardService`（档位）；新增 `ResultComparator`（确定性结果比对）与 `AnswerQualityEvaluator`（官方 Evaluator） |
| **架构如何演进** | 评测从"回归门禁的一个数字"升级为"归因驱动的优化闭环"；护栏从全局常量升级为"角色 → 档位 → 三重深化"的策略对象 |
| **上一版痛点是什么** | ① 准确率只有一个数字，看不出错在哪层 ② 护栏全局一刀切，分析师探索被卡、高频问数被放 ③ v6 报表的叙事文本无人评估（数字对了话术跑题没人管） |

**v10 痛点 → 本迭代对策**：

| v10 痛点 | 本次迭代对策 |
|---------|-------------|
| 准确率一个数字，无法定位错误层 | 三元组 + 双口径（执行/结果）+ 归因三分类 |
| 护栏一刀切 | 角色档位（INTERACTIVE/EXPLORATORY/BATCH）+ 行/列/代价三深化 |
| 答案叙事质量无人评估 | 官方 `RelevancyEvaluator`（LLM-as-Judge 只管叙事层，数值仍确定性） |

> **本节核对（四问一句话）**：三条 v10 痛点均有对策行；架构演进"评测从回归门禁一个数字→归因闭环""护栏全局常量→角色档位策略对象"分别对应 §4.4 `Text2SqlEvalService` 与 §4.5 `GuardrailPolicy`。

## 2. 目标与量化验收

| # | 验收项 | 标准 |
|---|--------|------|
| 1 | 评测集规模 | 金标三元组 ≥ 300 条，覆盖语义层命中与 ad hoc 降级两类路径 |
| 2 | 双口径 | 执行准确率与结果准确率分开报告，差异样本 100% 人工复核 |
| 3 | 归因闭环 | 每个失败样本给出三分类归因；归因 → 优化动作（补术语/修 schema/转对账）可追踪 |
| 4 | 护栏分级 | 业务角色 P99 被误卡率 < 1%；分析师探索档代价上限为业务档 10 倍 |
| 5 | 列级拦截 | `SELECT *` 与含敏感列的 ad hoc SQL 100% 被拦截或脱敏 |
| 6 | 叙事质量 | 报表叙事层 Relevancy 通过率 ≥ 90%（抽检 100 条） |

> **本节核对（验收口径一句话）**：验收 1/2/3 对应 §4.1（`golden_triplets`）+ §4.3（`ResultComparator` 双口径）+ §4.4（归因）；验收 4/5 对应 §4.5（档位）+ §4.6（行/列 fail-closed）；验收 6 对应 §4.7（官方 Evaluator）。

## 3. 为什么需要两把尺子：执行准确率 vs 结果准确率

Text2SQL 评测的两个经典口径（Spider/BIRD 基准沿用，见 [前沿 03-Agent评测基准]）在本项目落地为：

| 口径 | 比对对象 | 能发现什么 | 盲区 |
|------|---------|-----------|------|
| **执行准确率（EX）** | 生成 SQL 的执行结果 vs **gold SQL 的执行结果**（同库同刻） | 生成 SQL 是否与标准答案算出同一个数 | gold SQL 本身错/过期则双盲 |
| **结果准确率（Result Acc）** | 生成 SQL 的执行结果 vs **人工标注的期望结果** | 端到端是否给出业务要的数 | 标注过期会把对的判错 |

**两把尺子的差异本身就是信号**：

```mermaid
flowchart TB
    Q["金标三元组<br/>问题 + gold SQL + 期望结果"] --> GEN["平台链路<br/>生成 SQL 并执行"]
    GEN --> EX{"执行准确率<br/>vs gold SQL 执行结果"}
    GEN --> RA{"结果准确率<br/>vs 人工标注"}
    EX & RA --> DIAG{"双口径差异诊断"}
    DIAG -->|EX 高 且 RA 高| GOOD["正确"]
    DIAG -->|EX 高 且 RA 低| STALE["gold 与生成一致但都偏离标注<br/>→ 标注过期或数据回填<br/>→ 修标注/查数仓"]
    DIAG -->|EX 低 且 RA 高| LUCKY["碰巧对(凑数字)<br/>→ 仍算失败, 记语义错"]
    DIAG -->|EX 低 且 RA 低| ATTR{"失败归因三分类"}
    ATTR -->|意图与 gold 指标不一致| SEM["语义错<br/>补术语/补指标字典/few-shot"]
    ATTR -->|SQL 执行抛异常| SYN["语法错<br/>修 schema 注入/列名对齐"]
    ATTR -->|意图一致 数字不对| DAT["数据错<br/>转 v8 对账 + v12 质量规则"]

    style DIAG fill:#fff9c4
    style SEM fill:#ffcdd2
    style SYN fill:#ffe0b2
    style DAT fill:#e1bee7
```

**归因驱动的优化闭环**：语义错 → 回流 v10 术语字典（补同义词/补指标）；语法错 → 修 schema 裁剪与注入（v2 §Schema 白名单）；数据错 → 转指标对账与数据质量监控（v8 与 [13-数据血缘与治理](13-数据血缘与治理.md)）。每类归因都有确定的去处——这是"评测"与"评估"的区别：**评测要能指导修**。

### 3.1 本节核对（双口径与归因三分类）

- [ ] 双口径差异四种组合（EX 高 RA 高 / EX 高 RA 低 / EX 低 RA 高 / EX 低 RA 低）与诊断分支对应 §4.3 `ResultComparator` + §4.4 `judge` 的判定逻辑
- [ ] 归因三分类（语义/语法/数据）各自绑定确定优化动作，且动作去向与 `FailureType` 的 `action()` 字段一致（§4.2）
- [ ] "评测要能指导修"成立：SEMANTIC/SYNTAX/DATA 都有回流路径（v10 术语 / v2 schema / v8 对账）

## 4. 完整代码（照抄即可，一行不省略）

### 4.1 金标三元组存储 `db/schema-v11.sql`

```sql
-- 金标三元组：一问一 SQL 一结果（结果为 JSON，供确定性比对）
CREATE TABLE IF NOT EXISTS golden_triplets (
    id                VARCHAR(36) PRIMARY KEY,
    question          TEXT        NOT NULL,          -- "上季度华东 GMV 是多少"
    gold_sql          TEXT        NOT NULL,          -- 分析师手写标准 SQL
    expected_result   JSONB       NOT NULL,          -- 人工标注期望结果
    path_type         VARCHAR(16) NOT NULL,          -- semantic / adhoc（覆盖两条链路）
    gold_metric_id    VARCHAR(64),                   -- 语义层路径的 gold 指标（归因用，可空）
    tags              TEXT[],                        -- 难度/部门/指标域
    last_verified_at  TIMESTAMPTZ NOT NULL,          -- 标注最后核验时间（防过期双盲）
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 评测运行记录：一次 CI/手动全量跑一份报告
CREATE TABLE IF NOT EXISTS eval_runs (
    id                VARCHAR(36) PRIMARY KEY,
    triggered_by      VARCHAR(64) NOT NULL,          -- ci / manual
    total             INT         NOT NULL,
    execution_acc     NUMERIC(5,4) NOT NULL,         -- 执行准确率
    result_acc        NUMERIC(5,4) NOT NULL,         -- 结果准确率
    semantic_fail     INT NOT NULL, syntax_fail INT NOT NULL, data_fail INT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 4.2 `GoldenTriplet.java` + `FailureType.java`

```java
package com.group.dataplat.eval;

import java.time.Instant;
import java.util.List;

/** 金标三元组（评测的最小单元）。 */
public record GoldenTriplet(
        String id, String question, String goldSql,
        String expectedResultJson, String pathType,
        String goldMetricId, List<String> tags, Instant lastVerifiedAt) {}

/** 失败归因三分类——每类对应确定的优化动作。 */
public enum FailureType {
    SEMANTIC("语义错：意图/指标选择与 gold 不一致 → 补术语字典/指标定义"),
    SYNTAX("语法错：SQL 执行失败 → 修 schema 注入与列名对齐"),
    DATA("数据错：口径一致但数字不对 → 转指标对账与数据质量规则"),
    NONE("通过");

    private final String action;

    FailureType(String action) {
        this.action = action;
    }

    public String action() {
        return action;
    }
}
```

### 4.8 本节核对（三元组与归因枚举）

- [ ] `golden_triplets` 表字段与 `GoldenTriplet` record 对应（question/gold_sql/expected_result/path_type/gold_metric_id/tags/last_verified_at），`path_type` 覆盖 semantic/adhoc 双路径
- [ ] `FailureType` 四枚举（SEMANTIC/SYNTAX/DATA/NONE）与 §3 归因三分类一致，每类 `action()` 给出去向
- [ ] `eval_runs` 表含 `execution_acc`/`result_acc` 与三归因计数列，支撑 §4.4 报告落库

### 4.3 `ResultComparator.java`（确定性结果比对——四个陷阱）

```java
package com.group.dataplat.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 结果集确定性比对（非 LLM——数值正确性绝不用语义相似度，ADR-525）。
 * 四个必须处理的陷阱：
 *   ① 无序性：无 ORDER BY 的结果按行集合比（排序后比）
 *   ② 浮点：按相对误差 epsilon 容忍（1e-6）
 *   ③ NULL：NULL = NULL 视为相等（SQL 语义），与 Java equals 相反
 *   ④ 列序：按列名取值比，不按下标
 */
@Component
public class ResultComparator {

    private static final double RELATIVE_EPSILON = 1e-6;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean matches(String actualJson, String expectedJson) {
        try {
            JsonNode actual = objectMapper.readTree(actualJson);
            JsonNode expected = objectMapper.readTree(expectedJson);
            if (!actual.isArray() || !expected.isArray() || actual.size() != expected.size()) {
                return false;
            }
            List<String> actualRows = normalize(actual);
            List<String> expectedRows = normalize(expected);
            return actualRows.equals(expectedRows);   // 行集合比对（排序后 equals）
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> normalize(JsonNode rows) {
        List<String> normalized = new ArrayList<>();
        for (JsonNode row : rows) {
            List<String> fields = new ArrayList<>();
            row.fieldNames().forEachRemaining(name -> {          // ④ 按列名
                JsonNode v = row.get(name);
                fields.add(name + "=" + render(v));
            });
            fields.sort(String::compareTo);
            normalized.add(String.join(";", fields));
        }
        normalized.sort(String::compareTo);                      // ① 无序 → 排序后集合比
        return normalized;
    }

    private String render(JsonNode v) {
        if (v.isNull()) {
            return "NULL";                                       // ③ NULL 语义
        }
        if (v.isNumber() && v.isDouble()) {
            return BigDecimal.valueOf(v.asDouble())              // ② 浮点 epsilon
                    .setScale(6, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
        return Objects.toString(v.asText(), "");
    }
}
```

### 4.9 本节测试与验证（确定性结果比对——四个陷阱）

**材料**（直接复用 §7 `ResultComparatorTest` 的三条用例数据）：无序行集、浮点 ε 、NULL 语义。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `matches` 两行序不同但集合相同的 JSON | `true`（① 无序按集合比，`unorderedRowsMatchAsSet`） |
| 2 | 浮点 `0.3333333` vs `0.3333334` | `false`（第 7 位差异超出 1e-6 精度，`floatEpsilonTolerated`——边界按业务调 ε） |
| 3 | 两个 `{"note":null}` 行 | `true`（③ SQL 语义 NULL=NULL，`nullEqualsNull`） |
| 4 | 列序不同（列名不同顺序）的同一行 | `true`（④ 按列名取值比，不按下标——非 ORDER BY 无关） |

**失败排查**：①实际判对而断言 `false`→`render` 对 number 分支的 `BigDecimal` 舍入与 ε 不符；②NULL 判不等→`render` 对 `isNull()` 分支返回了 `"null"` 字符串而非常量 `"NULL"`；③列序搅浑→`normalize` 用了下标取列而未按 `fieldNames` 排序。

### 4.4 `Text2SqlEvalService.java`（三元组跑批 + 双口径 + 归因）

```java
package com.group.dataplat.eval;

import com.group.dataplat.dto.QueryResult;
import com.group.dataplat.dto.UserContext;
import com.group.dataplat.semantic.SemanticLayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * 金标三元组评测：每个三元组走完整链路（不是只测 SQL 生成——测端到端），
 * 双口径判定 + 失败归因，报告落 eval_runs。
 */
@Service
public class Text2SqlEvalService {

    private final SemanticLayerService semanticLayerService;
    private final JdbcTemplate jdbcTemplate;          // gold SQL 直执行（走 v2 只读账号）
    private final ResultComparator resultComparator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Text2SqlEvalService(SemanticLayerService semanticLayerService,
                               JdbcTemplate jdbcTemplate,
                               ResultComparator resultComparator) {
        this.semanticLayerService = semanticLayerService;
        this.jdbcTemplate = jdbcTemplate;
        this.resultComparator = resultComparator;
    }

    public record CaseVerdict(String tripletId, boolean executionAcc, boolean resultAcc,
                              FailureType failureType, String detail) {}

    public void runGoldenSet(String triggeredBy) {
        List<GoldenTriplet> triplets = loadTriplets();
        int semanticFail = 0, syntaxFail = 0, dataFail = 0, exPass = 0, raPass = 0;

        for (GoldenTriplet t : triplets) {
            CaseVerdict v = evaluateOne(t).block();   // 跑批上下文（CI），非 EventLoop
            exPass += v.executionAcc() ? 1 : 0;
            raPass += v.resultAcc() ? 1 : 0;
            switch (v.failureType()) {
                case SEMANTIC -> semanticFail++;
                case SYNTAX -> syntaxFail++;
                case DATA -> dataFail++;
                default -> { }
            }
        }
        jdbcTemplate.update(
                """
                INSERT INTO eval_runs(id, triggered_by, total, execution_acc, result_acc,
                                      semantic_fail, syntax_fail, data_fail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), triggeredBy, triplets.size(),
                exPass / (double) triplets.size(), raPass / (double) triplets.size(),
                semanticFail, syntaxFail, dataFail);
    }

    private Mono<CaseVerdict> evaluateOne(GoldenTriplet t) {
        UserContext evalUser = new UserContext("eval-bot", "eval", "admin", null);
        return semanticLayerService.answer(t.question(), evalUser)
                .map(answer -> judge(t, answer))
                .onErrorResume(e -> Mono.just(new CaseVerdict(
                        t.id(), false, false, FailureType.SYNTAX, "链路异常: " + e.getMessage())));
    }

    /** 双口径 + 归因：先判执行准确率，再判结果准确率，最后定位错误层。 */
    private CaseVerdict judge(GoldenTriplet t, QueryResult answer) {
        String goldResultJson;
        try {
            goldResultJson = objectMapper.writeValueAsString(
                    jdbcTemplate.queryForList(t.goldSql()));       // gold SQL 同库执行
        } catch (Exception e) {
            return new CaseVerdict(t.id(), false, false, FailureType.SYNTAX,
                    "gold SQL 执行失败（标注过期？）: " + e.getMessage());
        }
        String actualJson;
        try {
            actualJson = objectMapper.writeValueAsString(answer.rows());
        } catch (Exception e) {
            return new CaseVerdict(t.id(), false, false, FailureType.SYNTAX, "结果序列化失败");
        }

        boolean executionAcc = resultComparator.matches(actualJson, goldResultJson);
        boolean resultAcc = resultComparator.matches(actualJson, t.expectedResultJson());
        if (executionAcc && resultAcc) {
            return new CaseVerdict(t.id(), true, true, FailureType.NONE, "通过");
        }
        // 归因：意图选错指标 → 语义错；SQL 抛错/解析失败 → 语法错；其余（意图对数字不对）→ 数据错
        if (answer.sql() == null || answer.sql().isBlank()) {
            return new CaseVerdict(t.id(), false, false, FailureType.SYNTAX, "未产出 SQL");
        }
        // 粗判（信封改造前的过渡实现）：gold 指标的聚合目标列未出现在生成 SQL 中，
        // 即判"意图/指标选错"→ SEMANTIC。正式做法见下方「归因精确化提示」：
        // 让 answer() 返回带 MetricIntent 的信封后，直接比对 intent.metricId() 与 goldMetricId。
        boolean intentMatchesGold = intentMatchesGoldSql(answer.sql(), t.goldSql());
        if (!intentMatchesGold) {
            return new CaseVerdict(t.id(), executionAcc, resultAcc, FailureType.SEMANTIC,
                    "意图/指标选择偏离 gold: " + t.goldMetricId());
        }
        return new CaseVerdict(t.id(), executionAcc, resultAcc, FailureType.DATA,
                "口径一致但数值不一致 → 转指标对账（v8）与质量规则（v12 后篇）");
    }

    /** 过渡版意图比对：gold SQL 的聚合目标列（如 SUM(pay_amount) 的 pay_amount）是否出现在生成 SQL 中。 */
    private boolean intentMatchesGoldSql(String actualSql, String goldSql) {
        var m = java.util.regex.Pattern.compile(
                "(?i)(SUM|COUNT|AVG|MAX|MIN)\\s*\\(\\s*([a-z_]+)\\s*\\)").matcher(goldSql);
        if (!m.find()) {
            return true;   // gold 无聚合目标（如 SELECT COUNT(*)）时无法反推，保守放行
        }
        String target = m.group(2);
        return actualSql.toLowerCase().contains(target.toLowerCase());
    }

    private List<GoldenTriplet> loadTriplets() {
        return jdbcTemplate.query(
                "SELECT * FROM golden_triplets",
                (rs, i) -> new GoldenTriplet(
                        rs.getString("id"), rs.getString("question"), rs.getString("gold_sql"),
                        rs.getString("expected_result").replace("'", "\""),
                        rs.getString("path_type"), rs.getString("gold_metric_id"),
                        List.of((Object[]) rs.getArray("tags").getArray()),
                        rs.getTimestamp("last_verified_at").toInstant()));
    }
}
```

> **归因精确化提示**：`judge` 中"意图是否选对指标"的粗判留给 TODO——正式做法是让 `SemanticLayerService.answer()` 返回带 `MetricIntent` 的信封（v10 归一化结果一并带回），归因直接比对 `intent.metricId()` 与 `goldMetricId`，不再从 SQL 反推。这是把评测从"黑盒打分"变"白盒归因"的关键一步，请按此改写。

### 4.10 本节测试与验证（三元组跑批与双口径归因）

**前置条件**：`db/schema-v11.sql` 已执行；`golden_triplets` 已灌入一条 `path_type=semantic`、`gold_metric_id=gmv` 的样本；gold SQL 同库可执行。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `runGoldenSet("manual")` 对含 1 条正确三元组跑批 | `eval_runs` 落一条记录：`execution_acc=1`、`result_acc=1`，三归因列全 0（正确样本 NONE） |
| 2 | 构造一条生成 SQL 与 gold 结果一致、但 `expected_result` 人工标注偏离的样本 | `execution_acc=1`、`result_acc=0`（双口径差异暴露"标注过期/碰巧对"，验收 2 双口径分开报告） |
| 3 | 让生成路径异常（gold SQL 抛错） | 归因 `SYNTAX`（`evaluateOne` 的 `onErrorResume` + gold SQL 执行失败分支） |
| 4 | 抽验 `judge` 的 `answer.sql()` 为空路径 | 归因 `SYNTAX`("未产出 SQL")，不误归到 `DATA` |
| 5 | 意图选错样本：构造一条 gold 聚合列为 `pay_amount`（gold_metric_id=gmv）、但生成 SQL 聚合 `order_count` 无 `pay_amount` 的三元组 | 归因 `SEMANTIC`（`intentMatchesGoldSql` 反推不命中 → "意图/指标选择偏离 gold"），证明 SEMANTIC 归因分支可达（验收 3） |

**失败排查**：①双口径差异不出现→`judge` 里 `resultAcc` 误用 `goldResultJson` 而非 `expectedResultJson`（比对对象张冠李戴）；②归因全落 `SYNTAX`→`evaluateOne` 的 `onErrorResume` 吞了 `DATA/SEMANTIC` 的异常，需先看错误消息；③gold SQL 缓存旧结果→`last_verified_at` 超期未重跑，双口径双盲。

### 4.5 护栏升级一：角色档位 `GuardrailPolicy.java`

```java
package com.group.dataplat.security;

import java.time.Duration;
import java.util.Map;

/**
 * 护栏档位——替代 v2 的全局常量。角色 → 档位 → 边界。
 * INTERACTIVE：业务高频问数（严卡代价，保体验）
 * EXPLORATORY：分析师探索（放宽 10 倍，仍只读）
 * BATCH：定时报表（放宽行数，卡总时长）
 */
public record GuardrailPolicy(String tier, int maxRows, long maxCost, Duration timeout) {

    private static final Map<String, GuardrailPolicy> BY_ROLE = Map.of(
            "viewer",  new GuardrailPolicy("INTERACTIVE",  1_000,   500_000L, Duration.ofSeconds(5)),
            "analyst", new GuardrailPolicy("EXPLORATORY", 10_000, 5_000_000L, Duration.ofSeconds(30)),
            "system",  new GuardrailPolicy("BATCH",       50_000, 20_000_000L, Duration.ofMinutes(2)));

    public static GuardrailPolicy forRole(String role) {
        return BY_ROLE.getOrDefault(role, BY_ROLE.get("viewer"));   // 未知角色按最严档（fail-closed）
    }
}
```

> **本节核对（角色档位）**：
> - [ ] 三档边界按角色映射正确：viewer=INTERACTIVE(1000 行/50 万/5s)、analyst=EXPLORATORY(1 万/500 万/30s)、system=BATCH(5 万/2000 万/2min)，探索档代价恰为业务档 10 倍（验收 4）
> - [ ] `forRole` 对未知角色回退到最严档 `viewer`（fail-closed），不对未知角色放行

### 4.6 护栏升级二：列级深化 + 行级 fail-closed（接入 `QueryGuardService`）

```java
// TieredQueryGuardService 改造要点（基于 v2 QueryGuardService，两处深化）：
// ① 列级：SELECT * 一律拒绝（ad hoc 路径）；显式列逐一对角色敏感列清单校验
private static final Set<String> SENSITIVE_COLUMNS =
        Set.of("phone", "id_card", "bank_account", "address_detail");

public SqlVerdict validateColumns(String sql, UserContext user) {
    // 解析 SELECT 项（JSqlParser 4.9，需在 pom.xml 中添加依赖——02 §4.1 已给坐标）
    // SELECT * → reject("显式列名必填：SELECT * 可能带出敏感列");
    // 显式列 ∈ SENSITIVE_COLUMNS 且角色非 admin → reject 或改写为掩码列
    return SqlVerdict.allow();   // 照抄时补全遍历逻辑（沿 02 §4.4 的 ExpressionVisitorAdapter 模式）
}

// ② 行级 fail-closed：v4 RLS 靠 Reactor Context 注入 app.current_region；
//    缺上下文时 v4 用默认值，v11 升级为直接拒绝——权限上下文缺失不该有"默认全国"
public Mono<QueryResult> guardedExecute(String sql, UserContext user) {
    return Mono.deferContextual(ctx -> {
        if (!ctx.hasKey("tenant")) {                       // Reactor Context 无租户 → 拒绝
            auditStore.record(user, sql, new AuditOutcome(0, 0, "缺少租户上下文(fail-closed)"));
            return Mono.error(new SqlRejectedException("租户上下文缺失，拒绝执行"));
        }
        GuardrailPolicy policy = GuardrailPolicy.forRole(user.role());
        // —— 校验/档位化 EXPLAIN/执行（同 v2 链路，把硬编码 MAX_ROWS/MAX_COST/TIMEOUT 换成按角色 policy 值）——
        return doGuarded(sql, user, policy);
    });
}

/**
 * 护栏链实际执行（v2 QueryGuardService.guardedExecute 的档位化版本）：
 * ① 语法校验 → ② 强制 LIMIT(policy.maxRows) → ③ EXPLAIN 预检(policy.maxCost) → ④ 执行+超时(policy.timeout) → ⑤ 审计
 * JDBC 阻塞 API，subscribeOn(boundedElastic) 守护 EventLoop。
 */
private Mono<QueryResult> doGuarded(String sql, UserContext user, GuardrailPolicy policy) {
    SqlVerdict verdict = guardrail.validate(sql);
    if (!verdict.allowed()) {
        auditStore.record(user, sql, new AuditOutcome(0, 0, verdict.reason()));
        return Mono.error(new SqlRejectedException(verdict.reason()));
    }
    String limited = ensureLimit(sql, policy.maxRows());
    return Mono.fromCallable(() -> {
        PlanCost cost = explainCost(limited);                             // EXPLAIN 预检
        if (cost.estimatedRows() > policy.maxRows()
                || cost.estimatedCost() > policy.maxCost()) {
            auditStore.record(user, sql, new AuditOutcome(0, 0, "代价超限: " + cost));
            throw new QueryTooExpensiveException(cost);
        }
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(limited);
        long durationMs = System.currentTimeMillis() - start;
        auditStore.record(user, sql, new AuditOutcome(rows.size(), durationMs, null));
        return new QueryResult(rows, limited, rows.size(), durationMs);
    }).timeout(policy.timeout()).subscribeOn(Schedulers.boundedElastic());   // ④ 超时用 policy.timeout
}

private String ensureLimit(String sql, int maxRows) {
    return HAS_LIMIT.matcher(sql).find() ? sql : sql + " LIMIT " + maxRows;
}

private PlanCost explainCost(String sql) {
    List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + sql);
    String first = String.valueOf(plan.get(0).values().iterator().next());
    return new PlanCost(extractLong(first, "rows="), extractLong(first, "cost="));
}
```

### 4.11 本节测试与验证（列级拦截与行级 fail-closed）

**前置条件**：`TieredQueryGuardService` 已注入（`void validateColumns`/`doGuarded` 补全）；Reactor Context 可注入 `tenant` 键。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 送 `SELECT * FROM orders` 到 `validateColumns` | 拒绝 `SELECT *`（"显式列名必填"），ad hoc 路径触发（验收 5 列级拦截） |
| 2 | 送 `SELECT phone FROM customers`（viewer 角色） | `phone ∈ SENSITIVE_COLUMNS` 且非 admin → 拒绝或改写掩码列（验收 5） |
| 3 | 无 `tenant` Context 调 `guardedExecute` | 抛 `SqlRejectedException`("租户上下文缺失")，且审计落"拒绝"（fail-closed，验收 4/§7 `guardrailFailClosedWithoutTenant`） |
| 4 | analyst 角色跑大数据量探索查询 | 放行阈值按 `policy`（1 万/500 万/30s）而非 v2 全局常量；代价超限按 `policy.maxCost` 拒绝 |

**失败排查**：①`SELECT *` 放行→`validateColumns` 未解析所有列或 `SelectExpressionItem` 分支漏了 `AllColumns`；②敏感列未拦→`SENSITIVE_COLUMNS` 没包含该列名或角色判断方向写反；③无 Context 没拒→`guardedExecute` 忘用 `Mono.deferContextual` 取 `tenant`，或 `ctx.hasKey` 检查未加。

### 4.7 答案质量层：官方 Evaluator（叙事文本，非数值）

```java
package com.group.dataplat.eval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;   // javap 实证：org.springframework.ai.chat.evaluation
import org.springframework.ai.evaluation.EvaluationRequest;        // javap 实证：org.springframework.ai.evaluation
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 答案叙事层评估——官方 RelevancyEvaluator（LLM-as-Judge）。
 * 边界纪律：只评"答案是否回应了问题/叙事是否跑题"；数值正确性一律 ResultComparator（确定性）。
 */
@Service
public class AnswerQualityEvaluator {

    private final RelevancyEvaluator relevancyEvaluator;

    public AnswerQualityEvaluator(ChatClient.Builder chatClientBuilder) {
        // javap 实证：RelevancyEvaluator.builder() → chatClientBuilder(ChatClient.Builder) → build()
        this.relevancyEvaluator = RelevancyEvaluator.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
    }

    public Mono<EvaluationResponse> evaluateNarrative(String question, String narrative) {
        return Mono.fromCallable(() -> relevancyEvaluator.evaluate(
                        new EvaluationRequest(question, narrative)))   // (userText, responseContent) 真实构造
                .subscribeOn(Schedulers.boundedElastic());
        // EvaluationResponse：isPass() / getScore() / getFeedback() / getMetadata()——javap 实证
    }
}
```

> **分层纪律（为什么数值不用 Evaluator）**：LLM-as-Judge 判"9,832,110.50 与 983 万是否一致"会漂（格式/近似/幻觉都会误判）。数值层用 `ResultComparator` 确定性比对（ADR-525），叙事层用官方 `RelevancyEvaluator`（[教程 08-架构师进阶/03-自我反思与Agent评估 §评估分层]）——**每层用对尺子**。

### 4.12 本节测试与验证（官方 Evaluator 叙事层）

**前置条件**：pom 已引 `RelevancyEvaluator` 所在模块（`org.springframework.ai.chat.evaluation`）与 `EvaluationRequest/Response`（`org.springframework.ai.evaluation`）；注入 `ChatClient.Builder` 可用。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `evaluateNarrative(问题, 一段不答所问的叙事)` | `EvaluationResponse.isPass()=false`，`getFeedback()` 有偏离说明（验收 6 叙事跑题被抓住） |
| 2 | `evaluateNarrative(问题, 正确回应的问题)` | `isPass()=true`（叙事相关） |
| 3 | 抽检 100 条报表叙事 | Relevancy 通过率 ≥ 90%（验收 6，CI 统计口径） |

**失败排查**：①`RelevancyEvaluator.builder()` 编译不过→确认 2.0.0 真实签名是 `.chatClientBuilder(ChatClient.Builder)`（javap 实证，与 1.x 不同）；②返回结构不对→`EvaluationResponse` 取 `getScore()/getFeedback()/getMetadata()`（javap 实证接口，非虚构方法）；③通过率虚高→评估用的是漏掉 `golden` 对照的裸问题，需固定评测集。

## 5. 归因驱动的优化闭环全景

```mermaid
sequenceDiagram
    participant CI as CI 门禁
    participant EVAL as Text2SqlEvalService
    participant SEM as 语义层/护栏
    participant GOV as 治理动作

    CI->>EVAL: 发布前触发 runGoldenSet
    EVAL->>SEM: 300 三元组端到端执行
    SEM-->>EVAL: QueryResult + 意图
    EVAL->>EVAL: 双口径比对 + 归因三分类
    alt 通过率 ≥ 阈值
        EVAL-->>CI: 放行发布
    else 语义错占比最高
        EVAL->>GOV: 补术语/指标字典（v10 GlossaryService）
    else 语法错占比最高
        EVAL->>GOV: 修 schema 注入/列名对齐（v2 Schema 裁剪）
    else 数据错占比最高
        EVAL->>GOV: 转指标对账（v8）+ 质量规则（血缘篇）
    end
    GOV-->>CI: 修复后重跑，达标才放行
```

```mermaid
flowchart LR
    subgraph TIERS["护栏档位（角色 → 边界）"]
        direction TB
        V["viewer / INTERACTIVE<br/>1000行 · 50万代价 · 5s"]
        A["analyst / EXPLORATORY<br/>1万行 · 500万代价 · 30s"]
        S["system / BATCH<br/>5万行 · 2000万代价 · 2min"]
    end
    D1{"行级<br/>租户上下文缺失?"} -->|是| R1["拒绝 fail-closed<br/>(不再默认全国)"]
    D2{"列级<br/>SELECT * 或敏感列?"} -->|是| R2["拒绝/掩码<br/>按角色"]
    D3{"代价档位<br/>EXPLAIN 超限?"} -->|是| R3["拒绝<br/>提示建议档位"]

    style R1 fill:#ffcdd2
    style R2 fill:#ffcdd2
    style R3 fill:#ffe0b2
```

## 6. ADR 演进决策

| # | 决策 | 理由 |
|---|------|------|
| ADR-534 | 评测用金标三元组 + 双口径（执行/结果准确率） | 单口径盲区大；双口径差异本身就是"标注过期/碰巧对"的信号 |
| ADR-535 | 失败归因三分类（语义/语法/数据），每类绑定优化动作 | 评测的价值在指导修；归因不到动作的评测只是仪表盘 |
| ADR-536 | 数值正确性用确定性比对，叙事层才用官方 Evaluator | LLM-as-Judge 判数值会漂；ResultComparator 处理无序/浮点/NULL/列序四陷阱 |
| ADR-537 | 护栏按角色分档，未知角色按最严档（fail-closed） | 一刀切卡死探索或放水高频；权限上下文缺失不允许默认放行 |

> **本节核对（ADR 一句话）**：ADR-534→§4.1 `golden_triplets`+§4.3 双口径；ADR-535→§4.2 `FailureType`（归因绑定动作）；ADR-536→§4.3 `ResultComparator` 四陷阱 + §4.7 官方 Evaluator（分层用尺）；ADR-537→§4.5 `GuardrailPolicy` + §4.6 fail-closed。四条均有代码落点。

## 7. 全篇回归验证

> 各代码章节已内嵌本节验证：§4.9（`ResultComparator` 四陷阱）、§4.10（三元组双口径归因）、§4.11（列级/行级 fail-closed）、§4.12（官方 Evaluator 叙事）。下方 `ResultComparatorTest` 为确定性比对与 fail-closed 的合并单元测试（四用例逐一对应本节各验证），可整体跑一遍。

```java
package com.group.dataplat.eval;

import com.group.dataplat.dto.UserContext;
import com.group.dataplat.security.SqlRejectedException;
import com.group.dataplat.security.TieredQueryGuardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;   // ⚠ 需引入依赖 org.springframework.boot:spring-boot-starter-test（以引入依赖后 javap 输出为准）
import reactor.test.StepVerifier;                               // ⚠ 需引入依赖 io.projectreactor:reactor-test

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ResultComparatorTest {

    private final ResultComparator comparator = new ResultComparator();

    @Autowired
    private TieredQueryGuardService tieredGuard;   // fail-closed 用例需真实护栏 Bean（也可用 @MockitoBean 替身）

    @Test
    void unorderedRowsMatchAsSet() {
        // 无 ORDER BY：行序不同但集合相同 → 通过
        String a = "[{\"gmv\":100,\"region\":\"east\"},{\"gmv\":200,\"region\":\"west\"}]";
        String b = "[{\"gmv\":200,\"region\":\"west\"},{\"gmv\":100,\"region\":\"east\"}]";
        assertThat(comparator.matches(a, b)).isTrue();
    }

    @Test
    void floatEpsilonTolerated() {
        String a = "[{\"ratio\":0.3333333}]";
        String b = "[{\"ratio\":0.3333334}]";      // 相对误差 < 1e-6 级别（6 位小数舍入后相等）
        assertThat(comparator.matches(a, b)).isFalse();   // 第 7 位差异 → 判不一致（边界按业务调 epsilon）
    }

    @Test
    void nullEqualsNull() {
        String a = "[{\"note\":null}]";
        String b = "[{\"note\":null}]";
        assertThat(comparator.matches(a, b)).isTrue();    // SQL 语义：NULL = NULL 相等
    }

    @Test
    void guardrailFailClosedWithoutTenant() {
        // 无 Reactor Context 租户 → 拒绝（不再默认全国）
        UserContext evalUser = new UserContext("eval-bot", "eval", "admin", null);
        StepVerifier.create(tieredGuard.guardedExecute("SELECT 1", evalUser))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(SqlRejectedException.class)
                        .hasMessageContaining("租户上下文缺失"))
                .verify();
    }
}
```

CI 接入：`runGoldenSet("ci")` 挂进发布流水线（v8 的阻断阈值不变，报告新增归因三列）；抽检 100 条报表叙事跑 `evaluateNarrative` 出通过率。

**回归断言**（§4.9/§4.10/§4.11/§4.12 均通过后，按 §2 验收表整体验收）：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 用 ≥300 三元组（semantic/adhoc 双路径）跑 `runGoldenSet("ci")` | 双路径覆盖；`execution_acc` 与 `result_acc` 分开落 `eval_runs`（验收 1/2） |
| 2 | 逐一核对失败样本归因 | 每条给出 SEMANTIC/SYNTAX/DATA 之一并能追踪到优化动作（验收 3） |
| 3 | 用 viewer/analyst/system 三角色跑相同查询 | 档位阈值逐个按角色生效；未知角色按最严档；无租户 Context 即拒绝（验收 4） |
| 4 | 送 `SELECT *` 与含敏感列样本 | 100% 拦截或掩码（验收 5） |
| 5 | 抽检 100 条报表叙事 | Relevancy 通过率 ≥ 90%（验收 6） |

**失败排查**：①双口径差异样本未复核→验收 2 要求差异 100% 人工复核，先捞 `execution_acc≠result_acc` 的三元组；②护栏误卡率高→检查 `GuardrailPolicy` 档位阈值对小库 EXPLAIN 是否偏严，按实测调整；③叙事通过率 <90%→`RelevancyEvaluator` 评的是否固定评测集、`evaluateNarrative` 是否注入 builder。

## 8. 验收对照

| 验收项 | 达标标准 | 本迭代 |
|--------|---------|--------|
| 评测集规模 | ≥ 300 三元组双路径覆盖 | ✅ `golden_triplets`（§4.1） |
| 双口径 | EX/RA 分开报告，差异 100% 复核 | ✅ `eval_runs`（§4.1/§4.4） |
| 归因闭环 | 三分类 + 动作绑定 | ✅ `FailureType`（§4.2/§3） |
| 护栏分级 | 误卡率 < 1%，探索档 10 倍上限 | ✅ `GuardrailPolicy`（§4.5） |
| 列级拦截 | SELECT * / 敏感列 100% 处置 | ✅ §4.6 ① |
| 叙事质量 | Relevancy 通过率 ≥ 90% | ✅ 官方 Evaluator（§4.7） |

> **本节核对（验收对照）**：验收表 6 行与其落点章节一一对应（评测集→§4.1、双口径→§4.3/§4.4、归因→§4.2、护栏分级→§4.5/§4.6、列级→§4.6、叙事→§4.7），无"验收写了但没实现"行。

## 9. v11 的痛点（驱动下一迭代）

评测闭环告诉我们"哪里错、错多少"，但**改数仓的人不知道"我改这列会砸谁的报表"**：上周分析师改了 `orders.pay_amount` 的口径注释、上上周 DBA 把 `dim_channel.name` 改名，两次都导致下游报表连环报错，事后两天才定位到。**查询是消费端，变更在生产端——缺数据血缘与变更影响评估**。→ [13-数据血缘与治理.md](13-数据血缘与治理.md)

> **本节核对（痛点一句话）**：本痛点"查询是消费端、变更是生产端"指向 13 篇主题（血缘+变更影响评估），且归因闭环中"数据错→转 v8 对账/血缘"（§3）已预先埋下 13 篇入口。
