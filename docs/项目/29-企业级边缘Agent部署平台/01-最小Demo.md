# 01-最小 Demo：单边缘节点 + 本地推理

> **定位**：用最小骨架跑通"**边缘节点本地推理 + 云端注册**"：① 节点启动带身份注册到 edge-hub ② 本地 Ollama 承接推理（OpenAI 兼容）③ 心跳上报。验证三件事：本地能推理、云能看见节点、断云仍可用。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 55 §3](../../教程/55-边缘Agent与端云协同部署.md)。
>
> **铁律 0**：本地推理=实证 OpenAI 兼容接入（base-url 指向 Ollama）；节点注册/心跳自研「概念代码」。

---

## 一、四问

| 问 | 答 |
|----|----|
| **新增了什么需求** | 最小骨架：①节点身份+注册 ②本地 ChatClient（base-url→Ollama）③心跳循环 |
| **影响了哪些模块** | 单体 EdgeRuntime + 云端 edge-hub 最小版 |
| **架构如何演进** | 从无到有：云管面的"看见"与边缘的"自治"双底线 |
| **上一版痛点** | 无（起点） |

**本迭代验收**：①本地提问本地模型回答（不依赖云）②云端节点列表可见+心跳 ③断云后本地推理照常。

## 二、最小节点

```java
// 概念代码：边缘节点最小运行时
@Component
public class EdgeRuntime {
    private final ChatClient localChat;      // base-url=http://localhost:11434 (Ollama,实证模式)

    @PostConstruct void register() {
        hub.register(NodeInfo.of(nodeId(), hardware(), modelSet()));   // 云注册
        Flux.interval(Duration.ofSeconds(30))
            .subscribe(i -> hub.heartbeat(nodeId(), healthSnapshot())); // 心跳
    }
    public String ask(String q) { return localChat.prompt().user(q).call().content(); }  // 断云也可用
}
```

## 三、验收

| 测试 | 期望 |
|------|------|
| 本地提问 | 本地模型回答 |
| 云端 | 节点在线+心跳 |
| 断云 30min | 本地推理/心跳重连 |

> **下一步**：单节点能活了，但**模型更新**还靠人。02 迭代先做**节点纳管与分组**（先把节点管起来、分好组），再到 03 迭代做**模型分发**——分层/断点续传/灰度回滚。
