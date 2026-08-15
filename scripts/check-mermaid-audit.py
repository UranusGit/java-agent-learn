#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check-mermaid-audit.py — 文档 Mermaid 质量门禁（零依赖）

扫描 docs/ 下所有 mermaid 块，检查三类高信号问题（零误报设计）：
  1. 【光杆子】flowchart 拓扑纯链且无语义边——一条线串到底、无 { } 判断、无子图、
     无边标签、无双向/虚线/并行箭头。= "列表换个框"，按 CLAUDE.md 硬性规则 13 禁止。
     （带产物流标签边 -->|x|、双向 <--> 的图自动豁免——边承载"然后"之外的语义）
  2. 【子图菱形】subgraph id{...}——Mermaid 11.13.0 解析失败，应为 subgraph id[...]
  3. 【引号未闭合】单行引号数为奇数——标签引号未成对闭合
  4. 【行内三反引号】mermaid 块内标签含字面 ```——会把代码围栏提前截断（Typora 渲染破块）

完整语法校验（定界符不匹配、T5("[x]"] 这类手误）交给 mermaid@11.13.0 解析：
  参考 skill mermaid-typora 的 Typora兼容性.md「本地批量校验方法」，
  注意 mermaid.parse 顺序复用实例会跨块状态污染，须每块独立进程。

用法：
  python3 scripts/check-mermaid-audit.py              # 扫全库 docs/
  python3 scripts/check-mermaid-audit.py 文件1.md ... # 只扫指定文件

退出码：0 = 全部通过；1 = 发现问题
"""
import re
import sys
import os

BLOCK_RE = re.compile(r'```mermaid\n([\s\S]*?)```')
# 语义边：带标签/双向/虚线/并行 → 豁免杆子判定
SEMANTIC_EDGE = re.compile(r'-->\||<-->|==>|--x|--o|-\.->|~~~|--[^>\n]*?-->')


def find_edges(line):
    """提取一条 flowchart 行的 (src, dst) 边。标签无关、链安全。"""
    line = re.sub(r'\|[^|]*\|', '', line)
    line = re.sub(r'--[^>]*?-->', '-->', line)
    line = re.sub(r'-\.[^.|]*?\.\s*-?\>', '-.->', line)
    for op in ['<-->', '--->', '-.->', '-->', '==>', '--x', '--o', '~~~', '---']:
        line = line.replace(op, ' ▸ ')
    prev, edges = [], []
    for seg in line.split('▸'):
        cur = [m.group(1) for atom in seg.split('&')
               for m in [re.match(r'\s*([A-Za-z_][A-Za-z0-9_]*)', atom)] if m]
        if prev and cur:
            for p in prev:
                for c in cur:
                    edges.append((p, c))
        if cur:
            prev = cur
    return edges


def node_decl(line):
    m = re.match(r'\s*([A-Za-z_][A-Za-z0-9_]*)\s*(\[\[|\{\{|\(\(|\[\(|\[|\{|\(|/)', line)
    return (m.group(1), m.group(2)) if m else None


def analyze(src):
    """解析 flowchart 块，返回节点出入度、边、菱形、子图。"""
    nodes, edges, diamond, subgraph = {}, [], 0, 0
    for raw in src.strip().splitlines():
        line = raw.strip()
        if not line or line.startswith(('%%', 'end', 'direction', 'style', 'classDef',
                                        'linkStyle', 'click', 'class ', 'accTitle', 'accDescr')):
            continue
        if line.startswith('subgraph'):
            subgraph += 1
            continue
        if re.search(r'[A-Za-z_]\s*\{', line):
            diamond += 1
        for a, b in find_edges(line):
            for n in (a, b):
                nodes.setdefault(n, {'in': 0, 'out': 0})
            nodes[b]['in'] += 1
            nodes[a]['out'] += 1
            edges.append((a, b))
        d = node_decl(line)
        if d:
            nodes.setdefault(d[0], {'in': 0, 'out': 0})
    return nodes, edges, diamond, subgraph


def connected(nodes, edges):
    if not nodes:
        return False
    adj = {n: [] for n in nodes}
    for a, b in edges:
        adj.setdefault(a, [])
        adj.setdefault(b, [])
        adj[a].append(b)
        adj[b].append(a)
    seen, st = set(), [next(iter(nodes))]
    while st:
        x = st.pop()
        if x in seen:
            continue
        seen.add(x)
        st.extend(adj[x])
    return len(seen) == len(nodes)


def is_bare_pole(src):
    """拓扑纯链 + 无边标签/双向/虚线 = 光杆子。"""
    if not src.strip().startswith('flowchart'):
        return False
    nodes, edges, diamond, subgraph = analyze(src)
    if diamond or subgraph or len(nodes) < 3 or not connected(nodes, edges):
        return False
    if all(v['in'] <= 1 and v['out'] <= 1 for v in nodes.values()) and \
       sum(1 for v in nodes.values() if v['in'] != v['out']) == 2:
        # 纯链成立，但带产物流标签/双向等语义边的豁免
        if SEMANTIC_EDGE.search(src):
            return False
        return True
    return False


def subgraph_diamond_issues(src):
    issues = []
    for i, raw in enumerate(src.strip().splitlines(), 1):
        if re.search(r'subgraph\s+\S+\s*\{', raw):
            issues.append((i, 'subgraph 标题用了菱形 `{`，应为方括号 `["标题"]`'))
    return issues


def unclosed_quote_issues(src):
    issues = []
    for i, raw in enumerate(src.strip().splitlines(), 1):
        if raw.count('"') % 2 == 1:
            issues.append((i, f'引号未成对闭合：{raw.strip()[:70]}'))
    return issues


def inline_backtick_issues(text):
    """mermaid 块内标签含字面 ``` —— 围栏被提前截断。需扫原始文本。"""
    issues = []
    in_mermaid = False
    for i, raw in enumerate(text.splitlines(), 1):
        s = raw.strip()
        if s.startswith('```mermaid'):
            in_mermaid = True
            continue
        if s.startswith('```'):
            if in_mermaid:
                in_mermaid = False
            continue
        if in_mermaid and '```' in s:
            issues.append((i, f'块内标签含字面 ```（会截断代码围栏）：{s[:60]}'))
    return issues


def audit_file(path):
    text = open(path, encoding='utf-8').read()
    findings = []
    for m in BLOCK_RE.finditer(text):
        src = m.group(1)
        kind = src.strip().splitlines()[0].strip() if src.strip() else '?'
        if is_bare_pole(src):
            findings.append((m.start(), f'光杆子 flowchart（{kind}）→ 改表格/列表/timeline/状态机'))
        for ln, msg in subgraph_diamond_issues(src):
            findings.append((m.start(), f'子图菱形 第{ln}行：{msg}'))
        for ln, msg in unclosed_quote_issues(src):
            findings.append((m.start(), f'语法 第{ln}行：{msg}'))
    for ln, msg in inline_backtick_issues(text):
        findings.append((0, f'行内三反引号 第{ln}行：{msg}'))
    findings.sort(key=lambda x: x[0])
    return findings


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if args:
        files = [a if os.path.isabs(a) else os.path.join(os.getcwd(), a) for a in args]
    else:
        root = os.path.join(os.getcwd(), 'docs')
        files = [os.path.join(dp, fn)
                 for dp, _, fns in os.walk(root)
                 for fn in sorted(fns) if fn.endswith('.md')]

    total_issues = 0
    for fp in files:
        try:
            findings = audit_file(fp)
        except FileNotFoundError:
            print(f'!! 文件不存在：{fp}')
            total_issues += 1
            continue
        if findings:
            total_issues += len(findings)
            rel = os.path.relpath(fp, os.getcwd())
            print(f'\n{rel}')
            for _, msg in findings:
                print(f'  - {msg}')
    if total_issues:
        print(f'\n共 {total_issues} 处问题。修复后重跑，0 发现才交付。')
        sys.exit(1)
    print(f'通过：{len(files)} 个文件，未发现光杆子/子图菱形/引号未闭合。')
    sys.exit(0)


if __name__ == '__main__':
    main()
