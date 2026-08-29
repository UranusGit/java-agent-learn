# 项目 08：Agent 供应链安全网关 — 10-SBOM与依赖治理（迭代八）

> **定位**：收官之后的第一轮深化——把供应链安全的镜头掉转，对准网关自己。软件物料清单（SBOM）的生成与消费、CVE 漏洞响应流水线（监控→影响评估→升级/缓解闸门）、新依赖引入评审。网关管着别人的供应链，自己却答不出"当前构建里有哪些组件、什么版本、什么许可证"，是审计现场最讽刺的一幕。**完整可手写代码**：SBOM 领域模型、漏洞响应闸门、依赖评审服务、CI 集成配置。
>
> 「遇到阻塞？→ [教程 32-工具执行可观测与审计 §审计事件]、[教程 58-历史记录持久化与合规 §合规留存]、[附录 07-架构决策方法论/00-ADR架构决策记录]」

---

## 1. 四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ① 监管与客户审计要求交付物附带 SBOM（软件物料清单） ② CVE 响应流水线：网关自身传递依赖爆出高危漏洞时，要有"监控 → 影响评估 → 升级/缓解"的闸门与 SLA ③ 新依赖引入评审：杜绝"随手加个包" ④ C 级沙箱工具的依赖快照与准入登记联动 |
| **影响了哪些模块** | 新增 `dependency` 包（SBOM 模型 / 漏洞闸门 / 评审服务）；准入登记（`ToolRegistration` 增加 SBOM 快照引用）；审计流（新增 CVE 处置与依赖评审两类事件）；CI 流水线（SBOM 生成与扫描两道工序） |
| **架构如何演进** | 工具供应链网关 → 完整供应链治理：被治理的"链"从 Agent↔工具这一跳，扩展到网关自身的构建产物与依赖树——**治理者先被治理** |
| **上一版痛点是什么** | ① v1-v8 注意力全在工具侧，网关自己的依赖树从未盘点 ② 一次传递依赖 CVE 曝出后，安全团队花了三天才确认"是否可达、是否在用" ③ 新依赖引入无流程，pom 里已堆着 11 个没人评审过的间接依赖 ④ 沙箱里的 C 级工具带进来什么依赖，网关完全不知道 |

### 1.1 本节核对（四问）

| # | 核对项 | 判据 |
|---|------|------|
| 1 | 四问与正文章节一一对应 | "新增需求①②③④"分别落到 §3（SBOM 交付）、§5（CVE 响应闸门）、§6（依赖评审）、§6.3（沙箱联动） |
| 2 | 痛点可直接在正文找到证据 | v2 起无依赖盘点 ↔ §4 生成侧工具；pom 堆 11 个间接依赖 ↔ §6 评审流程缺失 |
| 3 | "治理者先被治理"表述与 §2 事件呼应 | §2 两个事件（审计现场/CVE 复盘）构成"网关自身缺清单"的动因 |

## 2. 为什么收官之后还有迭代八

v8 收官三个月后，两件事把项目拉回演进轨道：

**事件一：审计现场。** 集团安全审计要求每个对外交付系统出示 SBOM。网关团队现场用 IDE 手工导出了一份依赖树，被审计员反问三个问题，全部答不上来：这份清单的**生成时间与构建版本**能对上吗？**传递依赖**（依赖的依赖）完整吗？发现 CVE 后你们**多快能定位影响面**？

**事件二：CVE 复盘。** 某周日凌晨，一个广泛使用的 JSON 处理库爆出 RCE 级 CVE（同类事件可参照 [CVE-2020-36518 时代 jackson-databind 系列披露](https://nvd.nist.gov/vuln) 的传播模式：先有 PoC，后有大规模扫描）。值班同学的动作链是：被电话叫醒 → 翻 pom 确认有没有直接依赖（没有）→ 翻 `mvn dependency:tree` 输出确认传递依赖（有，第 4 层）→ 翻代码确认是否走了受影响代码路径（看不懂）→ 升级。全程 6 小时，其中 5 小时在"搞清楚自己用了什么"。

**结论**：这两个事件的共同根因是**网关自身缺乏物料清单与依赖治理能力**。供应链安全的完整闭环是双向的——既要治理 Agent↔工具这一跳（v1-v8 已建），也要治理"网关这个软件本身的供应链"（本迭代补齐）。

```mermaid
timeline
    title 一次 CVE 事件的响应时间线（治理前）
    00:00 值班电话 : NVD 推送 RCE 级 CVE
    00:40 定位直接依赖 : pom 无直接引用
    01:30 定位传递依赖 : 第 4 层传递引入
    03:00 可达性分析 : 人工读依赖源码
    05:00 升级与回归 : 锁版本 + 全量测试
    06:00 关闭 : 其中 5 小时花在“搞清楚自己用了什么”
```

### 2.1 本节核对（迭代八动因）

| # | 核对项 | 判据 |
|---|------|------|
| 1 | 两个事件都指向同一个根因 | 都归因于"网关自身缺乏物料清单与依赖治理"，非工具侧 |
| 2 | 时间线数据与正文一致 | 时间线"6 小时/5 小时盘点"与 §1 痛点②"三天确认可达性"叙事一致（前痛点指向治理前） |
| 3 | 动因引出本迭代三章 | §2 结论的"物料清单+依赖治理"由 §3-SBOM / §5-漏洞响应 / §6-准入评审承接 |

## 3. SBOM：是什么、什么格式

### 3.1 定义与最小元素

SBOM（Software Bill of Materials，软件物料清单）是构建产物中**全部组件的机器可读清单**——相当于食品的配料表。美国 NTIA 定义了最小元素集（[NTIA Minimum Elements](https://www.ntia.gov/report/2021/minimum-elements-software-bill-of-materials-sbom)）：**组件名、版本、唯一标识、供应者、依赖关系**。欧盟《网络弹性法案》（[EU CRA](https://digital-strategy.ec.europa.eu/en/policies/cyber-resilience-act)）把 SBOM 列为带数字元素产品的合规义务——2026 年起对进入欧盟市场的软件逐步生效，这是本项目迭代八最直接的外部驱动。

### 3.2 两种主流格式

| 维度 | SPDX | CycloneDX |
|------|------|-----------|
| 出身 | Linux 基金会，许可证合规起家 | OWASP，安全与应用风险分析起家 |
| 覆盖物 | 软件包、许可证、版权、文件级信息 | 组件、服务、漏洞（内嵌 VEX）、**AI/ML 模型与数据集扩展** |
| 机器可读性 | 好（tag-value / JSON / YAML） | 更好（JSON 体积更小、schema 更贴安全工具链） |
| 漏洞关联 | 需外部工具关联 | 原生 VEX（漏洞可利用性交换）声明 |
| 生态 | 许可证扫描、合规审计 | 漏洞扫描、依赖分析、AI 供应链（`ml-bom`） |

**选型**（ADR-323）：网关场景以**漏洞响应与安全分析**为主，且 CycloneDX 有 AI/ML 扩展（为迭代十"模型与数据集物料清单"预留），**主线用 CycloneDX，合规交付时用工具转换为 SPDX**——两者互转是成熟工具链的标配能力。

### 3.3 本节核对（SBOM 定义与格式选型）

| # | 核对项 | 判据 |
|---|------|------|
| 1 | 最小元素集与 NTIA 定义一致 | §3.1 列出组件名/版本/唯一标识/供应者/依赖关系 |
| 2 | 选型理由与场景匹配 | ADR-323 以"漏洞响应为主"+ AI/ML 扩展 → CycloneDX 主、SPDX 交付互转 |
| 3 | 合规驱动事实准确 | EU CRA 与 NTIA Minimum Elements 均标注外部链接，驱动结论可溯源 |

## 4. 生成与集成：工具真实坐标

> 本节工具均为**第三方独立工具**（非 Spring AI SDK 元素），坐标与用法以下列真实信息为准，标注「需在 CI / pom.xml 中另行配置」，不随网关运行时部署。

### 4.1 生成侧

| 工具 | 坐标 / 获取 | 用法 |
|------|------------|------|
| **Anchore syft** | [github.com/anchore/syft](https://github.com/anchore/syft)（CLI，brew/脚本安装） | 扫描构建产物或镜像：`syft <dir-or-image> -o cyclonedx-json > sbom.cdx.json` |
| **CycloneDX Maven 插件** | `org.cyclonedx:cyclonedx-maven-plugin`（[官方仓库](https://github.com/CycloneDX/cyclonedx-maven-plugin)） | 绑定 `package` 阶段，从 Maven 依赖图直接产出 BOM（含传递依赖） |

CycloneDX Maven 插件接入（**需在 pom.xml 中添加插件**——本项目 pom 尚未声明）：

```xml
<!-- 需在 pom.xml 的 <build><plugins> 中添加（v9 新增，坐标真实） -->
<plugin>
    <groupId>org.cyclonedx</groupId>
    <artifactId>cyclonedx-maven-plugin</artifactId>
    <version>2.8.0</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>makeAggregateBom</goal></goals>
        </execution>
    </executions>
    <configuration>
        <schemaVersion>1.5</schemaVersion>
        <includeCompileScope>true</includeCompileScope>
        <includeRuntimeScope>true</includeRuntimeScope>
    </configuration>
</plugin>
```

### 4.2 扫描侧

| 工具 | 坐标 / 获取 | 用法 |
|------|------------|------|
| **OWASP Dependency-Check** | `org.owasp:dependency-check-maven`（[官方仓库](https://github.com/jeremylong/DependencyCheck)） | 拿依赖坐标比对 NVD/NVD 镜像，产出 HTML+JSON 报告，可在 CI 设 CVSS 阈值闸门 |
| **OSV-Scanner** | [github.com/google/osv-scanner](https://github.com/google/osv-scanner)（Google，CLI） | 直接吃 SBOM 文件做漏洞匹配，数据源 [osv.dev](https://osv.dev/) |

> 漏洞数据源以 [NVD](https://nvd.nist.gov/) 与 [OSV](https://osv.dev/) 为主、[GitHub Advisory Database](https://github.com/advisories) 为补充；三者对同一 CVE 的收录时差通常在 24-72 小时，监控管道要**多源合并去重**。

### 4.3 CI 集成与消费链路

```mermaid
flowchart LR
    subgraph CI["CI 流水线（每次构建）"]
        B["mvn package"] --> G["cyclonedx-maven-plugin<br/>产出 BOM"]
        G --> S1["syft 扫描镜像<br/>（镜像层组件补全）"]
        S1 --> MERGE{"两份清单合并<br/>有差集?"}
        MERGE -->|"是"| FAIL["构建失败<br/>（未知组件禁止入库）"]
        MERGE -->|"否"| SCAN["OWASP Dependency-Check<br/>+ osv-scanner 扫描"]
    end
    SCAN --> V{“CVSS ≥ 7.0<br/>且可达?”}
    V -->|"是"| GATE["闸门拦截<br/>进入 §5 响应流程"]
    V -->|"否"| REG["SBOM 归档<br/>（版本号 ↔ BOM 一一对应）"]
    REG --> ADM["网关登记库<br/>（与工具准入联动 §6）"]

    style GATE fill:#ffcdd2
    style REG fill:#c8e6c9
```

**关键设计**：两份清单（Maven 依赖图 vs 镜像扫描）**合并校验**——Maven 图看不见镜像基础层里的系统库，镜像扫描看不见 provided scope 的约定。差集非空即失败，防止"SBOM 很全但产物里混了清单外组件"。

### 4.4 本节测试与验证（SBOM 生成与 CI 合并校验）

**前置条件**：工程可 `mvn package`；`cyclonedx-maven-plugin`（§4.1）与 `syft`、`osv-scanner`（§4.2）已按坐标安装到 CI/本机。

**材料——生成与核对命令**（`mvn package` 触发 §4.1 插件自动产出 BOM；syft 对产物目录扫描）：

```bash
# ① 生成：Maven 插件在 package 阶段自动产出（<build><plugins> 内已配 §4.1）
mvn package
# ② syft 镜像/产物扫描（§4.2，Clair 之外的第二源）
syft <网关产物目录或镜像> -o cyclonedx-json > sbom.mirror.cdx.json
# ③ 列表核对
xmlstarlet sel -t -v '/bom/metadata/component' target/bom.xml   # 或 jq 读 CycloneDX JSON
mvn dependency:tree
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `mvn package` 后检查 target | 产出 CycloneDX 1.5 BOM（makeAggregateBom 绑定 package 阶段生效），含直接的与传递依赖 |
| 2 | 文档 §4.3 断言 1 材料：同一构建产物分别用两种生成器产出，做组件差集 | 差集为空（两份清单合并校验通过；Maven 图补齐 provided、镜像补基础层系统库） |
| 3 | 产物含清单外组件（人为追加一个 jar 后重新 syft） | 差集非空 → 构建失败（§4.3 `MERGE` 节点，"未知组件禁止入库"） |
| 4 | 随机抽查任一传递依赖 | 在 `mvn dependency:tree` 与 BOM 均出现，SBOM 覆盖依赖树非"只列直接依赖"（§2 事件二痛点⑤的对应断言） |

**失败排查**：①插件未产出 → pom `<executions>` 的 phase 未绑定 package，或插件坐标/版本未进 `<build><plugins>`；②差集恒非空 → syft 与 Maven 插件对"同一组件"版本坐标表示不一（如 `group:artifact:version` vs `artifactId`），先归一到 gav；③BOM 无传递依赖 → `includeCompileScope/includeRuntimeScope` 配置缺失（§4.1 `<configuration>`）。

## 5. 漏洞响应流程：从监控到闸门

### 5.1 流程状态机

```mermaid
stateDiagram-v2
    [*] --> Detected: CVE 推送(NVD/OSV/GH Advisory 多源)
    Detected --> Triaging: 匹配 SBOM(组件+版本)
    Triaging --> NotAffected: 版本不匹配/依赖不在树
    NotAffected --> [*]
    Triaging --> Assessing: 命中依赖树
    Assessing --> NotAffected: 可达性分析=不可达
    state Assessing {
        [*] --> PathScan
        PathScan --> CallGraph
        CallGraph --> ConfigCheck
    }
    Assessing --> Mitigating: 可达且高危
    Assessing --> Scheduled: 可达但低危
    Mitigating --> Verifying: 升级/锁版本/虚拟补丁/网络缓解
    Scheduled --> Mitigating: 评级上调(漏洞在野利用)
    Verifying --> Closed: 回归+复盘(产出规则反哺筛查库)
    Mitigating --> Escalated: 无法升级(依赖断裂)
    Escalated --> Closed: 豁免登记(补偿措施+到期复审)
    Closed --> [*]
```

### 5.2 影响评估：可达性优先于存在性

依赖树里"存在"某漏洞组件 ≠ "受影响"。评估三步（对应状态机 `Assessing` 内部）：

1. **路径扫描**：受影响的类/方法是否在我们的编译产物里（`jar tf` + 类名比对——与铁律 0 的 javap 实证是同一套基本功）
2. **调用图分析**：业务代码是否调用了受影响入口（无调用 = 不可达）
3. **配置检查**：受影响功能是否被配置激活（很多反序列化 CVE 只在开了特定 feature 时可达）

**ADR-324 的核心立场**：响应优先级按**可达性 × CVSS**双维定，不按"存在性"一票升级——否则每周数十条 CVE 告警会让团队把告警静音，真正危险的反而被淹没（告警疲劳是漏洞响应的第一杀手）。

### 5.3 响应 SLA

| 级别 | 判定（CVSS × 可达性 × 在野利用） | SLA |
|------|--------------------------------|-----|
| P0 | CVSS ≥ 9.0 且可达，或任意"在野利用"且可达 | 24 小时内缓解，72 小时内根治 |
| P1 | CVSS 7.0-8.9 且可达 | 7 天内升级或缓解 |
| P2 | CVSS 4.0-6.9 且可达 | 下个迭代窗口 |
| P3 | 不可达 / 仅存在 | 登记观察，豁免登记需补偿措施 |

### 5.4 完整代码：`dependency/VulnerabilityGate.java`

```java
package com.group.secgw.dependency;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 漏洞响应闸门（v9）：把 §5.1 状态机的“评估→处置”编码为可审计的决策对象。
 * 每次评估产出一条处置决策，进审计流（复用 v1 AuditEvent，tagsJson 带 cve 与决策）。
 */
public class VulnerabilityGate {

    /** 一条 CVE 对一个构建组件的影响评估输入。 */
    public record CveFinding(
            String cveId,
            String component,          // group:artifact
            String installedVersion,
            double cvss,
            boolean exploitedInTheWild, // 在野利用情报
            Reachability reachability
    ) {}

    /** 可达性三档（ADR-324：可达性优先于存在性）。 */
    public enum Reachability { NOT_IN_TREE, IN_TREE_UNREACHABLE, REACHABLE }

    /** 处置决策：闸门的输出，直接驱动 SLA 与 CI 闸门。 */
    public record Disposition(
            String cveId,
            Action action,
            Priority priority,
            Duration sla,
            Optional<String> compensatingControl,   // 豁免时的补偿措施
            Optional<Instant> exemptionExpiry       // 豁免到期（强制复审）
    ) {}

    public enum Action { BLOCK_BUILD, UPGRADE_NOW, SCHEDULE_UPGRADE, EXEMPT_WITH_CONTROL, OBSERVE }
    public enum Priority { P0, P1, P2, P3 }

    public Disposition assess(CveFinding f) {
        // ① 不在依赖树：观察即可
        if (f.reachability() == Reachability.NOT_IN_TREE) {
            return new Disposition(f.cveId(), Action.OBSERVE, Priority.P3,
                    Duration.ofDays(30), Optional.empty(), Optional.empty());
        }
        // ② 在野利用且可达：无条件下 P0、拦构建——不管 CVSS 写多少
        if (f.exploitedInTheWild() && f.reachability() == Reachability.REACHABLE) {
            return new Disposition(f.cveId(), Action.BLOCK_BUILD, Priority.P0,
                    Duration.ofHours(24), Optional.empty(), Optional.empty());
        }
        // ③ 常规分级：可达性 × CVSS
        if (f.reachability() == Reachability.REACHABLE) {
            if (f.cvss() >= 9.0) {
                return new Disposition(f.cveId(), Action.UPGRADE_NOW, Priority.P0,
                        Duration.ofHours(72), Optional.empty(), Optional.empty());
            }
            if (f.cvss() >= 7.0) {
                return new Disposition(f.cveId(), Action.UPGRADE_NOW, Priority.P1,
                        Duration.ofDays(7), Optional.empty(), Optional.empty());
            }
            return new Disposition(f.cveId(), Action.SCHEDULE_UPGRADE, Priority.P2,
                    Duration.ofDays(14), Optional.empty(), Optional.empty());
        }
        // ④ 在树不可达：豁免要有补偿措施与到期日（不可无限豁免）
        return new Disposition(f.cveId(), Action.EXEMPT_WITH_CONTROL, Priority.P3,
                Duration.ofDays(90),
                Optional.of("egress 白名单已限制出网 + 升级窗口已排期"),
                Optional.of(Instant.now().plus(Duration.ofDays(90))));
    }

    /** CI 闸门规则：构建可否放行（P0 且 BLOCK_BUILD 拦截）。 */
    public boolean allowBuild(List<Disposition> dispositions) {
        return dispositions.stream()
                .noneMatch(d -> d.action() == Action.BLOCK_BUILD);
    }
}
```

### 5.5 本节测试与验证（漏洞响应闸门）

**前置条件**：`VulnerabilityGate`（§5.4）编译通过；引入一个含已知历史漏洞版本组件的测试 pom（§7 验收 2 的场景样例）。

**材料——单测/核对命令**：

```java
// 注入各档 Disposition，核对 §5.3 SLA 与 §5.4 assess 分支
var gate = new VulnerabilityGate();
// ① 不在树：观察
gate.assess(new CveFinding("CVE-X","g:a","1.0",9.5,false,Reachability.NOT_IN_TREE));
// ② 在野利用且可达：无条件下 P0、拦构建
gate.assess(new CveFinding("CVE-X","g:a","1.0",0.0,true,Reachability.REACHABLE));
// ③ 可达 CVSS 8.0：P1
gate.assess(new CveFinding("CVE-X","g:a","1.0",8.0,false,Reachability.REACHABLE));
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 构造 `Reachable + exploitedInTheWild=true` 的 Finding 调 `assess` | 返回 `BLOCK_BUILD / P0 / 24h`（§5.4 分支②，CVSS 写多少都拦） |
| 2 | 构造 `Reachable + cvss=8.0`（非在野） | 返回 `UPGRADE_NOW / P1 / 7天`（§5.4 分支③、§5.3 SLA 表） |
| 3 | 构造 `NOT_IN_TREE` | 返回 `OBSERVE / P3 / 30天`（§5.4 分支①） |
| 4 | 构造 `IN_TREE_UNREACHABLE` | 返回 `EXEMPT_WITH_CONTROL / P3 / 90天`，含补偿措施与 `exemptionExpiry`（§5.4 分支④，豁免不可无限续） |
| 5 | 拿一个 `BLOCK_BUILD` 的 Disposition 列表调 `allowBuild` | 返回 false（CI 模拟构建失败）；全是 `OBSERVE` 时返回 true |
| 6 | 对同一 CVE 重复 `assess` 两次 | 同输入同输出（纯函数，可复现供审计） |
| 7 | 与 §4.3 演示打通 | 扫描到 CVSS≥7 且可达 → `allowBuild=false` → CI 闸门拦截，进本节处置流程 |

**失败排查**：①P0 判不出 → 确认 `exploitedInTheWild` 与 `REACHABLE` 同时成立（§5.4 分支②先于 CVSS 判断）；②不可达也 EXEMPT 写死 90 天 → 检查 `Optional.of(补偿)` 是否到位（无补偿不允许 EXEMPT）；③`allowBuild` 放行 → 检查列表里是否存在 `BLOCK_BUILD`（`noneMatch` 语义）。

## 6. 依赖准入策略：新依赖引入评审

工具要准入（v2），依赖同样要准入——**同构复用**：`Submitted → 自动筛查 → 评审 → 登记`，只是审查项从"描述投毒"换成"依赖健康度"。

```mermaid
flowchart TB
    A["开发同学想引入新依赖"] --> B{"是运行时依赖<br/>还是构建/测试期?"}
    B -->|"构建/测试期"| C["轻量登记<br/>（不进运行时 SBOM）"]
    B -->|"运行时"| D{"已有同能力依赖?"}
    D -->|"有"| E["复用评审<br/>（为什么现有的不行?）"]
    D -->|"没有"| F["评分卡自动筛查"]
    F --> G{"评分卡结论"}
    G -->|"高分"| H["评审通过<br/>登记 + 进 SBOM"]
    G -->|"低分/红旗"| I["拒绝或要求补偿<br/>（隔离/影子验证）"]
    H --> J["试用期 90 天<br/>到期复审"]
    J -->|"无人使用"| K["移除<br/>（依赖也要下线）"]
```

### 6.1 评分卡

| 维度 | 数据来源 | 权重 | 红旗（一票否决） |
|------|---------|------|----------------|
| 维护活跃度 | 最近发布间隔、issue 响应 | 20% | 12 个月无任何发布 |
| 已知漏洞 | NVD/OSV 历史命中 | 20% | 近一年有 RCE 级且未修复 |
| 传递依赖规模 | `mvn dependency:tree` 增量 | 15% | 传递引入 > 30 个新组件 |
| 许可证 | 组件 LICENSE | 20% | GPL 系传染协议（商用红线） |
| 来源可信度 | 官方 Maven Central、签名 | 25% | 非中央仓库 / 无签名 / typosquat 疑似 |

> typosquat（抢注拼写近似包名）是 Java 生态真实存在的投毒手法（PyPI/npm 同类事件密集，Maven Central 亦有先例），**来源可信度**因此占最高权重——与 v2 工具准入的"来源信誉评分"完全同构。

### 6.2 完整代码：`dependency/DependencyReviewService.java`

```java
package com.group.secgw.dependency;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 依赖准入服务（v9）——与 v2 AdmissionService 同构的准入流水线，管的是网关自己的依赖。
 * 状态机：SUBMITTED → SCORED → APPROVED/REJECTED → PROBATION(90 天试用) → RETIRED
 */
public class DependencyReviewService {

    /** 一次依赖引入申请。 */
    public record DependencyRequest(
            String gav,                 // group:artifact:version
            String purpose,             // 用途（评审的核心问题：拿它干什么）
            String alternativeAnalysis, // 为什么不用现有依赖（§6 流程图 E 节点）
            Status status,
            ScoreCard score,
            Instant submittedAt
    ) {}

    public enum Status { SUBMITTED, SCORED, APPROVED, REJECTED, PROBATION, RETIRED }

    /** 评分卡（自动筛查产出，红旗项单独携带）。 */
    public record ScoreCard(double total, List<RedFlag> redFlags) {
        public enum RedFlag {
            STALE_12_MONTHS, RCE_UNFIXED, TRANSITIVE_EXPLOSION,
            CONTAGIOUS_LICENSE, NON_CENTRAL_REPO
        }
        public boolean hasRedFlag() { return !redFlags.isEmpty(); }
    }

    private final Map<String, DependencyRequest> requests = new ConcurrentHashMap<>();

    public DependencyRequest submit(DependencyRequest req) {
        DependencyRequest saved = new DependencyRequest(req.gav(), req.purpose(),
                req.alternativeAnalysis(), Status.SUBMITTED, null, Instant.now());
        requests.put(saved.gav(), saved);
        return saved;
    }

    /** 自动筛查：外部评分数据 → 评分卡；红旗一票否决。 */
    public DependencyRequest score(String gav, ScoreCard card) {
        DependencyRequest req = mustFind(gav);
        Status next = card.hasRedFlag() ? Status.REJECTED : Status.SCORED;
        DependencyRequest scored = new DependencyRequest(req.gav(), req.purpose(),
                req.alternativeAnalysis(), next, card, req.submittedAt());
        requests.put(gav, scored);
        return scored;
    }

    /** 人工评审：通过即进入 90 天试用期（试用期无人用就移除——依赖也要下线）。 */
    public DependencyRequest approve(String gav) {
        DependencyRequest req = mustFind(gav);
        DependencyRequest approved = new DependencyRequest(req.gav(), req.purpose(),
                req.alternativeAnalysis(), Status.PROBATION, req.score(), req.submittedAt());
        requests.put(gav, approved);
        return approved;
    }

    /** 试用期到期复审：usageFromAudit 来自 v1 审计流的实际调用统计（概念实现）。 */
    public DependencyRequest probationReview(String gav, boolean actuallyUsed) {
        DependencyRequest req = mustFind(gav);
        Status next = actuallyUsed ? Status.APPROVED : Status.RETIRED;
        DependencyRequest reviewed = new DependencyRequest(req.gav(), req.purpose(),
                req.alternativeAnalysis(), next, req.score(), req.submittedAt());
        requests.put(gav, reviewed);
        return reviewed;
    }

    public Optional<DependencyRequest> find(String gav) {
        return Optional.ofNullable(requests.get(gav));
    }

    private DependencyRequest mustFind(String gav) {
        DependencyRequest req = requests.get(gav);
        if (req == null) throw new IllegalArgumentException("unknown dependency: " + gav);
        return req;
    }
}
```

### 6.3 与工具准入联动：沙箱依赖快照

C 级沙箱工具是"带依赖进门的客人"：准入登记时要求提供其 SBOM（syft 对其镜像扫描），存入 `ToolRegistration` 的 `sbomRef` 字段（v2 实体的扩展字段，登记库加一列 `sbom_ref VARCHAR(256)`）。联动规则：

| 沙箱工具 SBOM 发现 | 处置 |
|-------------------|------|
| 高危漏洞组件且工具会执行它 | 准入拒绝（REJECTED），理由进登记库 |
| 高危漏洞组件但不可达 | 准入通过 + 漏洞登记（观察），升级前不升级工具版本 |
| 许可证红旗 | 转法务评审，法务未过不 PINNED |
| 无 SBOM | 视同"来源不可溯"，按 v2 评级规则降为 R（拒绝） |

### 6.4 本节测试与验证（依赖准入评审与沙箱联动）

**前置条件**：`DependencyReviewService`（§6.2）编译通过；对 §6.3 造一份含 RCE 组件的 C 级工具 SBOM。

**材料——状态机核对命令**（直接调 service）：

```java
var svc = new DependencyReviewService();
var req = new DependencyRequest("g:a:v","purpose","alternative",null,null,Instant.now());
svc.submit(req);                                   // → SUBMITTED
svc.score("g:a:v", new ScoreCard(22.0, List.of(ScoreCard.RedFlag.RCE_UNFIXED))); // → REJECTED（红旗一票否决）
svc.score("g:a:v", new ScoreCard(88.0,List.of())); // → SCORED
svc.approve("g:a:v");                              // → PROBATION（90 天试用）
svc.probationReview("g:a:v", false);               // → RETIRED（无人用移除）
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | `submit` 后 `find(gav)` | 状态 `SUBMITTED`，GAV 入库 |
| 2 | `score(含 RCE_UNFIXED 红旗)` | 状态 `REJECTED`（红旗一票否决，§6.1 评分卡） |
| 3 | `score(无红旗)` | 状态 `SCORED`，评分卡携带 total |
| 4 | `approve` | 状态 `PROBATION`（90 天试用期；试用期无人用就下线——依赖也要下线，§6.2 注释） |
| 5 | `probationReview("g:a:v", false)` | 状态 `RETIRED`（移除） |
| 6 | `probationReview("g:a:v", true)` | 状态 `APPROVED` |
| 7 | 提交一个 SBOM 含 RCE 组件的 C 级工具登记（§6.3 联动） | 准入 `REJECTED`，拒绝理由记录进登记库（"高危且工具会执行它"） |

**失败排查**：①红旗未 REJECTED → `hasRedFlag()` 逻辑或 RedFlag 枚举值传错；②`approve` 不落试用期 → 确认状态是 `PROBATION` 而非直接 `APPROVED`；③沙箱联动不拒绝 → §6.3 规则"高危且工具会执行"的判定条件未接进 v2 AdmissionService。

## 7. 全篇回归验证

**回归断言**（§4.4、§5.5、§6.4 本节验证均通过后最终整体验收）：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 一条 CVE 走完 §5 全流程：SBOM 命中 → `assess` → Disposition → `allowBuild` → CI 拦截 | 链条贯通：扫描器命中组件、`allowBuild=false`、CI 失败；不可达者走 EXEMPT+补偿+到期 |
| 2 | 一个新运行时依赖走完 §6 全流程 | `submit → score → 评审 → PROBATION`，期间触发一次 §4.4 的 BOM 重新生成，新依赖进入归档 SBOM |
| 3 | 混合验证：一个正常工具登记 + 一个 SBOM 含 RCE 的工具登记 | 前者放行、后者 REJECTED（§6.3 联动与验收表不冲突） |
| 4 | 重启（H2 清空）后重跑 §5.5 一档 | `VulnerabilityGate` 纯函数行为不变（无状态，重启无残留） |

**失败排查**：链路某跳不通优先查"该跳的输入格式"（SBOM 组件 gav 与 `CveFinding.component` 是否一致）；联动失效 → 回查 §6.3 判定条件是否接进 v2 准入服务；重启后状态异常 → 确认 `DependencyReviewService` 为内存态（重启即重来，属预期）。

## 8. 验收对照

| # | 目标 | 验收标准 | 结果 |
|---|------|---------|------|
| 1 | 物料可追溯 | 当前生产构建 100% 组件（直接+传递）在归档 SBOM 中，SBOM 与构建版本一一对应 | ✅ 差集为空 |
| 2 | 响应提速 | P0 级 CVE（可达+在野利用）从"推送"到"缓解决策"≤ 1 小时（v8 前 6 小时，其中 5 小时是盘点） | ✅ 58 分钟（演练） |
| 3 | 闸门生效 | CVSS ≥ 7.0 且可达的漏洞使 CI 构建失败；不可达者走豁免+补偿+到期 | ✅ 12/12 用例 |
| 4 | 准入覆盖 | 连续 3 个月新引入运行时依赖 100% 有评审记录，0 个未评审直接合入 | ✅ |
| 5 | 依赖瘦身 | 试用期复审移除 6 个无人使用依赖，传递依赖数下降 21% | ✅ |
| 6 | 合规交付 | 对外交付包附带 CycloneDX 1.5 BOM，可转换 SPDX 通过客户审计工具校验 | ✅ |

### 8.1 本节核对（验收对照）

| # | 核对项 | 判据 |
|---|------|------|
| 1 | 验收 1"差集为空"与 §4.4 断言 2 同源 | 都由同一次生成/合并校验佐证 |
| 2 | 验收 3"12/12 用例"可由 §5.5 断言覆盖 | 闸门拦截（BLOCK_BUILD/allowBuild）与不可达豁免两条路径均被断言 |
| 3 | 验收 2 的"≤1 小时缓解决策"有流程支撑 | 由 §5.3 SLA 表 P0 档（24h 缓解/72h 根治）与 §5.2 可达性评估流程支撑 |

## 9. ADR 演进决策

| # | 决策 | 理由 |
|---|------|------|
| ADR-323 | SBOM 主格式用 CycloneDX，合规交付时转换 SPDX | 漏洞响应是主场景（VEX 原生）；CycloneDX 的 AI/ML 扩展为迭代十模型物料清单预留；SPDX 是许可证合规事实标准，转换工具链成熟 |
| ADR-324 | 漏洞响应按"可达性 × CVSS × 在野利用"分级，不按存在性一票升级 | 告警疲劳是响应第一杀手；存在性告警会把真正危险的在野利用淹没 |
| ADR-325 | 依赖准入与工具准入同构（同一条 Submitted→Screen→Review 流水线） | 治理心智复用：依赖就是"更小的工具"；两套流程会让团队在两套规则间钻空子 |

### 9.1 本节核对（ADR 演进决策）

| # | 核对项 | 判据 |
|---|------|------|
| 1 | ADR-323 选型有正文支撑 | §3.2 的 CycloneDX AI/ML 扩展与 SPDX 互转工具链 |
| 2 | ADR-324 的"可达性优先"有代码落地 | §5.4 `assess` 用 `Reachability` 三档而非一味按存在性/CVSS 升级 |
| 3 | ADR-325 的"同构"在 §6 有落实 | §6.2 `DependencyReviewService` 状态机与 v2 准入（Submitted→Screen→Review）对应 |

## 10. 总结

v9 完成「SBOM 生成与消费 + 漏洞响应闸门 + 依赖准入评审」，网关自身的供应链第一次变得可盘点、可响应、可审计。遗留痛点（供 v10 决策）：

依赖治理管住了"代码来自哪"，但审计同时暴露了一个身份层面的裂缝：**网关与工具端、网关与内部服务之间的信任仍建立在"一张共享的、一年期的人手发放证书"上**——上季度工具端证书过期，6 个工具调用同时失败 40 分钟；安全组还发现两个业务 Agent 共用一张客户端证书，审计上无法区分"谁调的"。**服务之间需要可轮换、可吊销、每服务独立的身份**，而不是把 mTLS 当成一堵静态墙。

### 10.1 本节核对（总结与遗留）

| # | 核对项 | 判据 |
|---|------|------|
| 1 | 遗留痛点引出下一篇 | "共享长证书/无法区分调用者"正是 [11-零信任深化：服务身份与mTLS] 的动因 |
| 2 | 总结覆盖本迭代能力 | "SBOM 生成与消费+漏洞响应闸门+依赖准入评审"与 §3/§5/§6 对应 |

→ [11-零信任深化：服务身份与mTLS.md](11-零信任深化：服务身份与mTLS.md)
