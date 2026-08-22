# 02-OTel 管道与遥测治理

> **定位**：落地 [附录 21](../../附录/21-可观测平台实践/00-OTel管道与gen_ai语义.md) 的管道工程：**① Collector 三段（接收/处理/导出）② 遥测治理（脱敏/尾部采样/租户标签增补）③ 多路导出（指标/Trace/LLM 专用）**。前置阅读：[01-最小Demo](01-最小Demo.md)。
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

## 三、验收

| 测试 | 期望 |
|------|------|
| Prompt 含手机号 | 存储里已脱敏 |
| 错误 trace | 100% 留存 |
| 正常快调用 | ~5% 采样 |
| Span 聚合 | 按租户切得动 |

> **下一步**：管道通了，但**用户还是要在三个系统看**。03 迭代做**单一玻璃板三视图**——一块屏看全。
