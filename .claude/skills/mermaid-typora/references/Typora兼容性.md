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
| **1.13** | **11.13.0** | ishikawa；Mermaid 11 全量稳定图 |

## 支持矩阵（Typora 1.13）

✅ 稳定支持：`flowchart`、`sequenceDiagram`、`classDiagram`、`stateDiagram-v2`、`erDiagram`、`gantt`、`pie`、`mindmap`、`timeline`、`journey`、`gitGraph`、`packet-beta`、`sankey-beta`、`xychart-beta`、`quadrantChart`、`kanban`、`architecture-beta`、`ishikawa`

⚠️ 不保证 / 避免：
- `radar-beta`、`treemap-beta`、`block-beta` —— 实验特性，API 会变
- `flowchart-elk` / `graph-elk` —— Typora 未内置 ELK 布局引擎，写了渲染为空
- 单代码块多图 `&&` 分隔 —— Typora 支持不稳定，一张图一个块
- ~~`venn`~~ —— **Mermaid 11.13.0 实测不识别此图类型**（`No diagram type detected`），别写；集合关系用文本或表格表达

## 在 Typora 里的工作方式

- 语言标识必须写 `mermaid`，Typora 在预览模式直接渲染。
- `%%{init: {...}}%%` 配置指令可用，最小化使用（如 `%%{init: {"theme": "default"}}%%`）。
- `click 节点 "url"` 链接可用；**回调形式已废弃**，别写。
- 渲染失败的代码块在 Typora 里显示为源码或红字报错，不会自动降级。

## 渲染失败排查清单（12 条，按序）

1. 代码块语言标识是不是 `mermaid`（不是 `mermaid2` / `Mermaid` / 别的）。
2. 图类型关键字拼写：`flowchart`（非 `graph`）、`sequenceDiagram`、`classDiagram`、`stateDiagram-v2`（带 `-v2`）、`erDiagram`、`packet-beta` 等。
3. 图类型是否真的存在于 11.13.0：`venn` 等未内置类型直接报 "No diagram type detected"。
4. 引号是否成对；含 `( ) [ ] { } : #` 的标签是否都加了双引号。
5. 大括号 / 中括号 / 圆括号是否成对闭合（属性块、复合状态、组合块最常见）。
6. 缩进敏感的图（mindmap / timeline / kanban / journey / 复合状态 / packet-beta）层级是否一致。
7. 是否一个代码块塞了多张图（用了 `&&`）。
8. 是否用了实验图（`block-beta` / `radar-beta` / `treemap-beta`）或 `flowchart-elk`。
9. ID 是否重复或含空格/标点（`participant a b as 名字` 报错）。
10. 特殊图类型的语言限制：`sankey-beta` 数据行**只接受 ASCII**（中文标签实测解析失败，改用英文标签）；`architecture-beta` 的 `[标签]` **只接受 ASCII**（中文标签报 lexer 错，改用英文标签或省略）；`xychart-beta` / `quadrantChart` 的轴标签、坐标点中文**必须加双引号**（实测不加引号报 lexical error）。
11. 旧版箭头：时序图勿用 `->`，统一 `->>` / `-->>`。
12. `%%{init}%%` 指令是否放在第一行（放中间会被当正文解析）。

排查完仍失败 → 用下面的本地校验定位到具体行，最小化复现：抽一小段渲染测试。

## 本地批量校验方法（Node + mermaid@11.13.0）

**为什么锁定 11.13.0**：npm 最新版解析更宽松，未加引号的括号标签等会"假通过"，回到 Typora 仍渲染失败。校验必须用与 Typora 同款内核。

一次性环境准备（任意临时目录）：

```bash
mkdir mermaid-check && cd mermaid-check
npm init -y && npm install mermaid@11.13.0 jsdom
```

校验脚本 `validate.mjs`：

```javascript
import { JSDOM } from 'jsdom';
import { readFileSync } from 'fs';

// mermaid.parse 依赖 DOM 全局，headless 环境 shim 一个最小 DOM
const dom = new JSDOM('<!DOCTYPE html><body></body>');
global.window = dom.window;
global.document = dom.window.document;

const { default: mermaid } = await import('mermaid');

let failures = 0;
for (const file of process.argv.slice(2)) {
  const blocks = [...readFileSync(file, 'utf8')
    .matchAll(/````\s*mermaid\n([\s\S]*?)````/g)].map(m => m[1]);
  for (let i = 0; i < blocks.length; i++) {
    try {
      await mermaid.parse(blocks[i].trim());
      console.log(`OK    ${file} [block ${i}]`);
    } catch (e) {
      failures++;
      console.log(`FAIL  ${file} [block ${i}]: ${String(e.message || e).split('\n')[0]}`);
    }
  }
}
process.exit(failures ? 1 : 0);
```

注意：提取正则用四反引号 ```` ```` ```` 作围栏——skill 的 reference 文档里嵌套了三反引号代码块，用三反引号匹配会提前截断。

运行：

```bash
node validate.mjs 需要校验的文档.md   # 可传多个文件
```

全部 `OK` 才交付；有 `FAIL` 按报错行号修完重跑。该方法只验**语法可解析**，不验视觉效果——布局美观仍需人工在 Typora 里确认。
