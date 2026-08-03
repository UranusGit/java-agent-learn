# Typora × Mermaid 兼容性

## 版本基线

Typora 1.13.x（当前稳定版）内置 **Mermaid 11.13.0**。本 skill 的所有语法模板都以"能在 Typora 渲染"为准。

| Typora 版本 | 内置 Mermaid | 说明 |
|---|---|---|
| ≤ 0.9.85 | 无 | 不支持 mermaid |
| 1.2 | 8.14.0 | flowchart/sequence/class/state/gantt/pie、基础 ER |
| 1.5 | 9.2 | 新增 mindmap |
| 1.9-1.10 | 10.x | sankey、quadrant、xychart、block |
| 1.11 | 11.9 | radar-beta、treemap-beta、packet、kanban |
| **1.13** | **11.13.0** | venn、ishikawa；Mermaid 11 全量稳定图 |

## 支持矩阵（Typora 1.13）

✅ 稳定支持：`flowchart`、`sequenceDiagram`、`classDiagram`、`stateDiagram-v2`、`erDiagram`、`gantt`、`pie`、`mindmap`、`timeline`、`journey`、`gitGraph`、`packet-beta`、`sankey-beta`、`xychart-beta`、`quadrantChart`、`kanban`、`architecture-beta`、`venn`、`ishikawa`

⚠️ 不保证 / 避免：
- `radar-beta`、`treemap-beta`、`block-beta` —— 实验特性，API 会变
- `flowchart-elk` / `graph-elk` —— Typora 未内置 ELK 布局引擎，写了渲染为空
- 单代码块多图 `&&` 分隔 —— Typora 支持不稳定，一张图一个块

## 在 Typora 里的工作方式

- 语言标识必须写 `mermaid`，Typora 在预览模式直接渲染。
- `%%{init: {...}}%%` 配置指令可用，最小化使用（如 `%%{init: {"theme": "default"}}%%`）。
- `click 节点 "url"` 链接可用；**回调形式已废弃**，别写。
- 渲染失败的代码块在 Typora 里显示为源码或红字报错，不会自动降级。

## 渲染失败排查清单

按顺序检查：

1. 代码块语言是不是 `mermaid`（不是 `mermaid2` / 别的）。
2. 图类型关键字拼写：`flowchart`、`sequenceDiagram`、`classDiagram`、`stateDiagram-v2`（带 `-v2`）、`erDiagram`、`packet-beta` 等。
3. 引号是否成对；含 `( ) [ ] { } : #` 的标签是否都加了双引号。
4. 大括号 / 中括号 / 圆括号是否成对闭合。
5. 缩进敏感的图（mindmap / timeline / kanban / 复合状态 / block）层级是否一致。
6. 是否一个代码块塞了多张图（用了 `&&`）。
7. 是否用了 `-beta` 实验图或 `flowchart-elk`。
8. ID 是否重复或含空格/标点。

如果排查完仍失败，最小化复现：抽一小段渲染测试，定位到具体某行语法。
