# 34 补充：Spring AI 2.0 ToolCalling 迭代限制——调研全过程的最终结论

> **调研日期**:2026-07-26 | **Spring AI 版本**:2.0.0 GA | **结论性质**:定论

---

## 最终结论

**Spring AI 2.0.0 GA 不提供限制工具调用迭代次数的 API。企业级方案不是计次,是三层防御:收敛规则 + timeout + 可观测。**

---

## 调研过程

| 步骤 | 尝试 | 结果 |
|------|------|------|
| 1 | 搜 `spring.ai.tool-calling.max-iterations` YAML 配置 | 不存在(GitHub #3333/#1004 确认为 known gap) |
| 2 | 试 `ToolCallingChatOptions.builder().internalToolExecutionMaxIterations(5)` | 编译报错(`javap` 确认方法不存在于 GA 版) |
| 3 | 写 Advisor,用 `request.context()` 存跨迭代计数器 | 每次"现在是:1"——Record 防御性拷贝导致写入被丢弃 |
| 4 | 反编译 ToolCallingAdvisor 字节码,分析循环机制 | order=-1 无效——内部用 `chain.copy(this)` |
| 5 | 试 `AdvisorParams.toolCallingAdvisorAutoRegister(false)` 手动循环 | `executeToolCalls` 后消息序列不完整,DeepSeek 报 400 |
| 6 | 试继承 ToolCallingAdvisor 覆盖 hook 方法 | 需要替换 Spring Boot 自动装配的默认 Bean,侵入性强 |

---

## 为什么计次在企业级不成立

**DeepSeek/ChatGPT/Claude 后台都不是按迭代次数限流的——是按 timeout。**

原因:LLM 推理时间不固定(搜一次可能 2 秒也可能 10 秒),计次不映射 SLA,timeout 映射。"30 秒内必须出结果"比"最多搜 5 次"更符合用户体验和成本管理。

## 企业级的三层防御

| 层 | 机制 | 代码 |
|----|------|------|
| 1 | System prompt 收敛规则 | `.system("资料足够后给出结果,不要反复搜索")` |
| 2 | `Flux.timeout(60s/180s)` | `.timeout(Duration.ofSeconds(60), Flux.just("超时"))` |
| 3 | ChatMemory 持久化上下文 | 超时后用户追问即可续传,不需重新搜索 |

**timeout 触发后,不抛异常,返回超时提示。** ChatMemory(第8章 PG 持久化)保留了已收集的所有 worker 结果——用户追问时,LLM 能读到完整上下文,不需要重新搜索。

## 开发教训

1. **先查 GitHub issues 确认 known gaps**——不要盲目翻配置
2. **`javap` 反编译 jar 是最终真相**——博客教程可能基于旧版本
3. **当"两条路径需要两种方案"时,停下来**——你还没找到真正的答案
4. **框架能力缺失 ≠ 你设计能力不行**——如实记录,找替代方案
5. **不要因为"应该有"就假设"有"**——Spring AI 2.0.0 就是不给这个口子

---

> **相关文档**:[34-研究Agent与知识库实战.md 第1.2.2节](./34-研究Agent与知识库实战.md)——生产环境的防死循环策略
