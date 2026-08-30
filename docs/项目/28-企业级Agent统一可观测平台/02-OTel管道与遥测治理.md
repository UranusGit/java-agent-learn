# 02-OTel 管道与遥测治理

> **定位**：落地 [附录 21](../../附录/18-可观测平台实践/00-OTel管道与gen_ai语义.md) 的管道工程：**① Collector 三段（接收/处理/导出）② 遥测治理（脱敏/尾部采样/租户标签增补）③ 多路导出（指标/Trace/LLM 专用）**。前置阅读：[01-最小Demo](01-最小Demo.md)。
>
> **铁律 0**：OTel Collector 为业界标准组件（部署配置）；治理策略自研配置「概念代码」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | ①Collector 部署（receivers/processors/exporters）②redaction 脱敏（Prompt 敏感内容）③tail_sampling（错误全留+正常采样）④attributes 增补（tenant/bizLine 统一打标） |
| **影响了哪些模块** | 应用直连存储 → 统一走管道；新增 Collector 配置 |
| **架构如何演进** | 从"各服务直发"演进为"统一管道治理" |
| **上一版痛点** | Prompt 明文落遥测存储；全量留存成本高；缺租户标无法聚合 |

**本迭代验收**：①敏感字段入存储前脱敏 100%（00 验收⑤）②错误 trace 全留、正常 5% 采样 ③所有 Span 带 tenant/bizLine ④三路导出（Prometheus/Trace/LLM 平台）。

### 一.1 本节核对（四问与迭代验收）

- [ ] 四问与"各服务直发 → 统一管道治理"的演进一致
- [ ] 四条验收（脱敏 100% / 错误全留正常 5% / 全 Span 带 tenant·bizLine / 三路导出）能指出由 `## 二` 的哪个 processor/exporter 实现

## 二、管道配置（核心治理三件套）

```yaml
# 概念配置：Agent 遥测管道（OTel Collector 风格）
receivers:
  otlp: { protocols: { grpc: {}, http: {} } }
processors:
  attributes/tenant:        # 统一打租户/业务线标(从 header 提取)
    actions: [{ key: tenant.id, from_attribute: http.header.tenant, action: upsert }]
  redaction/sensitive:      # 敏感内容脱敏(PII/密钥正则+词表)
    rules: [phone, id_card, api_key]
  tail_sampling:            # 错误/慢全留, 正常采样
    policies: [ { name: errors, type: status_code, status_code: {status_codes: [ERROR]} },
                { name: slow, type: latency, latency: {threshold_ms: 3000} },
                { name: baseline, type: probabilistic, probabilistic: {sampling_percentage: 5}} ]
  batch: {}
exporters:
  prometheus: { endpoint: "0.0.0.0:8889" }        # 指标(usage 不采样)
  otlp/tempo: { endpoint: "tempo:4317" }           # Trace
  otlp/llm: { endpoint: "langfuse:4317" }          # LLM 专用
```

### 二.1 本节测试与验证（管道治理三件套）

**前置条件**：Collector 已按上述三段（receivers/processors/exporters）上线；应用遥测统一走该管道。

**材料——核对手段**：向管道发送含明文敏感字段（手机号/身份证/api_key）与正常/错误两条特征的 trace，再从三个后端存储侧抽查。

```bash
# 从 Prometheus 侧核对指标导出正常（Prometheus 自身标准端口 9090，非应用端口）
curl "http://prometheus:9090/api/v1/query?query=up"
# 从 Trace 后端按 traceId 抽查采样与脱敏后字段
```

预期输出示例（`up=1` 表示 agent-metrics 指标通道存活，Collector 8889 导出正常）：

```json
{
  "status": "success",
  "data": {
    "resultType": "vector",
    "result": [
      { "metric": { "instance": "otel-collector:8889", "job": "agent-metrics" }, "value": [ 1756500000, "1" ] }
    ]
  }
}
```

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 注入含手机号/身份证的 Prompt trace | 存储侧对应字段已脱敏（正则+词表 `phone/id_card/api_key` 命中），明文不落原始存储 |
| 2 | 制造一个错误 trace（HTTP/状态 ERROR） | tail_sampling errors 策略 → 100% 留存 |
| 3 | 制造一批正常快调用 | baseline probabilistic 5% → 存储量约 5% 留存 |
| 4 | 带 `http.header.tenant` 的调用 | Span 上增补 `tenant.id/bizLine`，可按租户切片聚合 |
| 5 | 抽查三路导出 | Prometheus 指标 / Tempo Trace / LLM 平台各有数据到达 |

**失败排查**：①明文仍落库 → redaction rules 正则/词表未命中或 processor 顺序在导出前未生效；②错误 trace 丢 → tail_sampling 策略名/status_code 取值不匹配 status 来源；③正常调用留存量远大于 5% → probabilistic 采样未命中或分桶偏差，核对 sampling_percentage 与流量分布；④缺租户标 → attributes/tenant 的 from_attribute 字段名与请求头不一致。

## 三、全篇回归验证

**回归断言**（`## 二.1` 本节测试通过后整体验收）：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 混合发一轮（敏感 + 错误 + 正常快调用各若干） | 脱敏 100%、错误 100% 留存、正常约 5%、全 Span 带租户标、三路均有数到达 |

**失败排查**：任一项不达标 → 回到 `## 二.1` 对应行单独复核，避免多策略叠加掩盖单点。

> **下一步**：管道通了，但**用户还是要在三个系统看**。03 迭代做**单一玻璃板三视图**——一块屏看全。
