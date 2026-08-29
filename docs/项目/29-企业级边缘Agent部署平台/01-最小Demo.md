# 01-最小 Demo：单边缘节点 + 本地推理

> **定位**：用最小骨架跑通"**边缘节点本地推理 + 云端注册**"：① 节点启动带身份注册到 edge-hub ② 本地 Ollama 承接推理（OpenAI 兼容）③ 心跳上报。验证三件事：本地能推理、云能看见节点、断云仍可用。前置阅读：[00-需求分析与架构设计](00-需求分析与架构设计.md)、[教程 55 §3](../../教程/98-边缘Agent与端云协同部署.md)。
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

### 一.1 本节核对（四问与迭代验收）

| # | 核对项 | 判据 |
|---|--------|------|
| 1 | 四问口径齐全 | 新增需求/影响模块/架构演进/上一版痛点四行均有，无空答；"上一版痛点=无（起点）"自洽 |
| 2 | 本迭代验收可度量 | ①本地不依赖云 ②云端可见+心跳 ③断云照常——三项均可作 PASS 判据，且与 §二 的 `localChat`/`hub.register`/`heartbeat` 落点对应 |

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

### 二.1 本节测试与验证（最小节点：本地推理 + 云注册 + 心跳）

**前置条件**：节点本地已装 Ollama（11434 端口）；`localChat` 用 `base-url=http://localhost:11434`（实证 OpenAI 兼容接入）；edge-hub 可连通；`nodeId()` 稳定。

**材料——代码内含的旋钮**：心跳周期 `Flux.interval(Duration.ofSeconds(30))`；注册 `hub.register(NodeInfo.of(nodeId, hardware, modelSet))`；提问 `ask(q)` 走 `localChat.prompt()...call()`。

**步骤与断言**：

| # | 操作 | 预期（PASS 判据） |
|---|------|------------------|
| 1 | 启动节点，发起一条本地提问 | 本地 Ollama 返回模型回答（不依赖云，若云已断仍能答） |
| 2 | 打开 edge-hub 节点列表 | 新节点出现在列表中，身份、硬件、模型集字段与注册内容一致 |
| 3 | 等待 ≥30s | 云端能收到周期心跳（节点在线状态刷新） |
| 4 | 断云（停 hub/断专线）后再次本地提问 | 本地推理照常返回；无远端异常吞掉本地回答 |
| 5 | 恢复云后观察 | 心跳自动重连恢复上报，无需重启节点 |

**失败排查**：①本地提问报连接拒绝→Ollama 未监听 11434 或 `base-url` 未指向本地；②云端看不到节点→`register()` 未执行或 hub 地址/鉴权不对；③断云后 `ask()` 卡死→`localChat` 未带超时或本地栈依赖远端（应走本地推理）；④恢复后心跳不出现→`Flux.interval` 订阅被取消或 hub 断连未重连。

## 三、验收

| 测试 | 期望 |
|------|------|
| 本地提问 | 本地模型回答 |
| 云端 | 节点在线+心跳 |
| 断云 30min | 本地推理/心跳重连 |

### 三.1 本节核对（验收表）

- [ ] 验收表三行（本地提问/云端/断云 30min）与 §二.1 步骤 1/2-3/4-5 一一对应，无验收项落空
- [ ] 断云 30min 场景有明确断言（本地可推理 + 心跳重连），非空许愿

> **下一步**：单节点能活了，但**模型更新**还靠人。02 迭代先做**节点纳管与分组**（先把节点管起来、分好组），再到 03 迭代做**模型分发**——分层/断点续传/灰度回滚。
