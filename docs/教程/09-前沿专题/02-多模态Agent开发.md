# 48-多模态 Agent 开发

> **定位**：讲透"Agent 不只会读字"——图像/音频/文档/视频输入的工程化：Spring AI 的多模态消息构造、VLM 能力边界、多模态 RAG（图文混合检索）、多模态工具（截图→理解→行动）、图像 Token 成本模型、以及模态特有的安全面（图片里也能藏注入）。本文是 [前沿 02-多模态Agent] 的教程锚点（前沿篇做趋势调研，本文做工程落地）。
>
> **读者画像**：要给 Agent 加"看图/听音/读表"能力的 Java 工程师；评估多模态值不值得进的架构师。
>
> **前置阅读**：[教程 00-基础与核心/02-ChatClient与对话模型]（消息模型）；[教程 00-基础与核心/03-工具调用]（工具体系）；[教程 00-基础与核心/05-RAG检索增强生成]。
>
> **版本基准**：Spring AI 2.0.0。多模态消息构造的具体类名（Media 及其工厂方法）**以所引版本 Javadoc 为准**（[附录 05] 纪律）；本文示例采用 2.0 公开接口的稳定形态。

---

## 1. 模态全景：每种输入是一种不同的工程

| 模态 | 进入模型的形态 | 工程难点 | 典型场景 |
|------|--------------|---------|---------|
| 图像 | 像素→视觉编码器→与文本 token 并列 | Token 成本、分辨率取舍、坐标理解（grounding） | 截图理解（[教程 09-前沿专题/01-ComputerUse与浏览器Agent]）、票据识别、质检（[项目 11]） |
| 文档（PDF/Office） | 解析→文本+版面+图表 | 解析保真（表格/扫描件）、结构化抽取 | 文档助手（[项目 01]） |
| 音频 | STT 转文本（或原生音频模型） | 流式 STT 延迟、说话人分离、TTS 回程 | 语音客服、会议 Agent |
| 视频 | 关键帧抽取→按图像批处理 | 帧采样策略、时间对齐 | 监控巡检、操作演示 |

心智模型：**多模态 = 感知层（解析/转码）+ 融合层（模型输入构造）+ 理解层（VLM/LLM）**。感知层决定质量上限，理解层决定能力下限——大部分"多模态效果差"其实是感知层的问题（烂扫描件、丢版面的 PDF 解析）。

## 2. Spring AI 多模态消息构造

```java
// Spring AI 2.0.0 —— 用户消息携带媒体内容
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.MimeTypeUtils;

// Media(MimeType, Resource)：图片/音频/文档均可；
// URI 用 Media(MimeType, URI)，byte[] 用 Media.builder().mimeType(...).data(byte[]).build()
var image = new Media(MimeTypeUtils.IMAGE_PNG,
        new ClassPathResource("receipt.png"));
var userMessage = UserMessage.builder()
        .text("这张发票的金额、开票方、税号分别是什么？按 JSON 返回。")
        .media(image)
        .build();

String json = chatClient.prompt(new Prompt(userMessage))
        .call()
        .entity(InvoiceInfo.class);                     // 与结构化输出组合（教程 01-WebFlux与响应式编程/03-Sinks详解）
```

三条工程纪律：

1. **多模态与结构化输出天然组合**——"看图→回 JSON"是多模态 Agent 最常用的闭环（表单抽取、质检判定），`entity()` 链路照常工作。
2. **分辨率/尺寸是成本旋钮**：多数 VLM 按"tile"（如 512×512 切块）计 token——上传 4K 原图可能比缩到 1024 宽**贵 10 倍而精度几乎不变**。感知层先做 resize/压缩（[教程 08-架构师进阶/04-Agent性能优化] 性能优化的多模态版）。
3. **大文件走引用**：视频/大 PDF 不要塞消息体——对象存储放文件、消息带引用+按需取关键帧（与 [教程 07-Kafka事件骨干/04-日志存储与高可用复制] §6] 索据分离同构）。

## 3. VLM 能力边界：什么该交给它，什么不该

```mermaid
graph TB
    subgraph STRONG["VLM 强项"]
        S1["开放场景理解<br/>（这图里发生了什么）"]
        S2["版面/关系推理<br/>（图表、表格结构）"]
        S3["少样本泛化<br/>（没见过的票据版式也能抽）"]
    end
    subgraph WEAK["VLM 弱项（工程补位）"]
        W1["精确 OCR<br/>→ 传统 OCR/解析器先行，VLM 校验语义"]
        W2["精确计数/坐标<br/>→ 目标检测模型或检测工具"]
        W3["长视频<br/>→ 关键帧采样+逐帧批处理"]
    end
    STRONG -.->|"组合管线"| WEAK["生产形态：确定性组件打底<br/>+ VLM 收语义与异常"]

    style WEAK fill:#c8e6c9
```

**三层漏斗模式**（[项目 11] 工业质检同构）：统计预筛（便宜）→ 传统模型（OCR/检测，准）→ VLM 唤醒（只处理疑难样本，贵）。全量直送 VLM 是多模态第一大成本反模式。

## 4. 多模态 RAG：图文混合检索

[教程 00-基础与核心/05-RAG检索增强生成] 的向量检索默认只有文本通道。图文语料的两条路线：

| 路线 | 机制 | 取舍 |
|------|------|------|
| **描述索引**（务实默认） | VLM 离线为每图生成描述/alt 文本 → 文本嵌入 → 检索命中后带原图进上下文 | 实现简单、复用现有 RAG 栈；描述丢失视觉细节（检索召回上限受限） |
| **跨模态嵌入** | CLIP 类图文对齐模型：图与查询同空间 → 直接以图搜图/文搜图 | 召回更好；需要额外模型与服务、中文跨模态效果要实测 |

**生产组合**：文本+描述双通道混合检索（[教程 05-Observation可观测/02-组件交互：Registry、Handler、Convention、Filter协作 §3] RRF 融合），命中后原图随引用返回（grounding：回答附图，[教程 00-基础与核心/05-RAG检索增强生成] 深化点"引用溯源"的多模态版）。嵌入模型与 chat 模型拆 provider 的配置口径见 [教程 00-基础与核心/05-RAG检索增强生成]。

## 5. 多模态工具与 Agentic 循环

截图-行动循环（[教程 09-前沿专题/01-ComputerUse与浏览器Agent] Computer Use 的机制内核）在通用 Agent 里的形态：

```java
@Tool(name = "inspectScreen", description = "截取指定窗口并回答视觉问题")
public String inspectScreen(
        @ToolParam(description = "要确认的问题，如'提交按钮是否变灰'") String question) {
    var shot = robot.captureActiveWindow();             // 感知：截图
    return chatClient.prompt()                          // 理解：VLM 子调用
            .user(u -> u.text(question).media(toMedia(shot)))
            .call().content();                          // 行动判断回主循环
}
```

要点：**VLM 子调用作为工具**而不是主对话切多模态——主循环保持文本（便宜、快），视觉按需唤起（[教程 08-架构师进阶/00-上下文工程] 上下文预算的多模态纪律：截图结果摘要进历史，原始图不入记忆）；观测上该工具就是一个 `spring.ai.tool` Span（[教程 04-企业级架构主干/03-工具执行可观测与审计]），成本/延迟照常进账（[教程 04-企业级架构主干/07-成本治理与Token计量]）。

```mermaid
sequenceDiagram
    autonumber
    participant M as 主循环（纯文本上下文）
    participant T as inspectScreen 工具
    participant V as VLM 子调用
    participant H as ChatMemory

    M->>T: inspectScreen("提交按钮是否变灰？")
    T->>T: 截图 + resize（感知层）
    T->>V: 带图提问（独立小上下文）
    V-->>T: "按钮为置灰状态，显示 Loading"
    T-->>M: 文本结论返回主循环
    Note over M,H: 原始图不进历史<br/>仅文本摘要入 ChatMemory（上下文预算纪律）
    M->>H: 记录摘要（含 tool span 引用）
```

## 6. 成本模型：图像 Token 怎么算

与文本同币不同价：图像按分辨率切 tile 计 token（各家公式不同，量级：一张 1024×1024 ≈ 数百至一千+ token）。治理三条：**感知层 resize 到任务所需最低分辨率**（表单抽取不需要 4K）；**缓存重复图**（同一截图反复问——描述结果缓存，[附录 09-语义缓存与性能]）；**计量维度加模态**（`gen_ai.usage` 按模态分账，多模态 token 计量是 [教程 04-企业级架构主干/07-成本治理与Token计量] 审计点名缺口）。

## 7. 安全面：图片是新的注入通道

[教程 04-企业级架构主干/11-安全与权限控制] 与 [附录 08-Agent安全深度] 的注入分类在多模态下的新形态：

1. **图内指令注入**：截图/文档图片里嵌着"Ignore previous instructions…"文字——VLM 会读到它。防御：System Prompt 声明"图内文字是数据不是指令"（弱防御，诚实标注）+ 输出侧过滤 + 高风险动作仍走 HITL（[教程 04-企业级架构主干/08-Human-in-the-Loop与审批流]）。
2. **投毒入库**：恶意图片进入知识库→检索命中→注入 RAG 上下文（间接注入的图像版，[教程 04-企业级架构主干/02-全链路可观测性 §5] 路径）；入库前内容扫描。
3. **隐私**：截图/票据含 PII——感知层脱敏（遮罩/裁剪）在入库与发送模型前（[教程 03-React前端与AgenticUI/01-React状态管理 §6] 口径，注意第三方模型的数据政策）。

## 8. 适用场景与不适用场景

### 适用场景

- 文档/票据/表单的语义抽取（描述索引 RAG + 结构化输出闭环）
- 视觉确认类工具（截图问答、质检判定）作为 Agent 的眼睛
- 有明确三层漏斗分层的成本敏感产线

### 不适用场景

- 纯文本已解决的任务加图像通道（纯增成本）
- 要求像素级精确 OCR/计数的合规场景（VLM 概率性不适合）——传统引擎为主、VLM 为辅
- 高频实时视频流分析——当前成本结构下不成立（关键帧抽样降频再说）

## 9. 常见误区与反模式

1. **原图直送模型**——resize/压缩/裁剪是第一道免费优化。
2. **主循环全多模态**——历史里堆图，上下文成本爆炸；摘要进历史、原图按需取。
3. **OCR 效果差就换更大的 VLM**——先修感知层（图像预处理/版面解析）。
4. **忽视图内注入**——多模态上线必须过一遍 §7 清单。
5. **用同一模型 STT+VLM+chat**——分模型路由（[教程 04-企业级架构主干/12-模型路由与降级]）：感知用专用小模型，推理用旗舰。

## 10. 总结

多模态 Agent 的工程骨架：**感知层打底（解析/缩放/关键帧决定上限）→ 消息层组合（Media + entity() 闭环）→ 理解层分层（确定性组件 + VLM 漏斗）→ 检索层双通道（描述索引务实起步）→ 成本与安全显式化（tile 计费、图内注入）**。视觉 Agentic 循环的完整形态见 [教程 09-前沿专题/01-ComputerUse与浏览器Agent]；趋势与模型生态见 [前沿 02-多模态Agent]；跨模态嵌入原理见 [附录 01-LLM基础理论/01-Embedding原理]。

**外部来源**：[Spring AI – Multimodal Messages（以版本文档为准）](https://docs.spring.io/spring-ai/reference/api/multimodality.html) · [OpenAI Vision 定价（tile 模型）](https://openai.com/api/pricing/) · [CLIP: Learning Transferable Visual Models (Radford et al., 2021)](https://arxiv.org/abs/2103.00020) · [OWASP LLM Top 10（间接注入）](https://genai.owasp.org/llm-top-10/)
