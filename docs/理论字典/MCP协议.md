# 理论字典：MCP 协议

| 概念 | 一句话解释 |
|------|---------|
| **MCP** | Model Context Protocol——工具/资源/Prompt 跨框架统一暴露的协议 |
| **MCP Server** | 暴露工具/资源的进程（任何框架/语言可消费） |
| **MCP Client** | 消费 MCP Server 的 Agent（Spring AI 内置） |
| **三类能力** | Tools（执行操作）/ Resources（提供数据）/ Prompts（模板） |
| **传输方式** | stdio（本地）/ SSE（远程流式）/ HTTP |

## MCP vs @Tool
```
@Tool：本进程内使用，换框架/语言要重写
MCP：一次实现，任何支持 MCP 的 Agent 都能消费
```

## 相关文档
- 协议入门：`阶段3-Agent工程化/05-MCP协议入门.md`
- Server 开发：`阶段3-Agent工程化/06-MCPServer开发.md`
- Hub 与工具市场：`阶段6-前沿/04-MCPHub与工具市场.md`
