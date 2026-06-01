#!/usr/bin/env python3
"""中文 AEA 论文 → IEEE Letter 格式 Word 文档 v3。
- 公式: Word 原生 OMML 方程对象
- 中文: 宋体(SimSun) | 英文/数字: Times New Roman
- 特殊字符: 彻底清理
"""
from docx import Document
from docx.shared import Pt, Cm, Inches, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn, nsdecls
from docx.oxml import parse_xml
from pathlib import Path
import re, unicodedata

BASE    = Path("d:/zhuomian/data analysis platform")
FIG_DIR = BASE / "figures"
EXP_DIR = BASE / "src/yubi-backend-master/experiments/out/figures"
MD_PATH = BASE / "AEA_paper_draft.md"
OUT     = BASE / "基于血缘约束与自适应信息增益的自主数据分析Agent.docx"
OUT_EN  = BASE / "AEA_Paper_Draft_EN.docx"

FIG_DEF = {
    "dual_loop_architecture":("图 1. 双循环架构。", 3.3),
    "aig_phase_weights":     ("图 2. AIG 三阶段自适应权重配置。", 3.3),
    "aig_phase_transition":  ("图 3. AIG 阶段切换条件与触发机制。", 3.3),
    "heatmap_overview":      ("图 4. 八种模式六指标热力图总览。", 3.3),
    "cumulative_discovery":  ("图 5. 累积发现曲线。", 3.3),
    "aig_phase_timeline":    ("图 6. AIG 阶段转换时间线。", 3.3),
    "lollipop_ablation":     ("图 7. 消融实验：查询节省数 vs FULL 基线。", 3.3),
    "efficiency_frontier":   ("图 8. 效率前沿：成本-质量 Pareto 权衡。", 3.3),
    "ig_trajectory":         ("图 9. IG 衰减轨迹。", 3.3),
}

MATH_NS = "http://schemas.openxmlformats.org/officeDocument/2006/math"

# ══════════════════════════════════════════════════════════
# 字体
# ══════════════════════════════════════════════════════════
def cn(run, size=10, bold=False):
    """设置中英文字体: 中文=SimSun, 英文=Times New Roman"""
    run.font.name = "Times New Roman"
    run.font.size = Pt(size)
    run.font.bold = bold
    rPr = run._element.get_or_add_rPr()
    rf = rPr.find(qn('w:rFonts'))
    if rf is None:
        rf = parse_xml(f'<w:rFonts {nsdecls("w")}/>')
        rPr.insert(0, rf)
    rf.set(qn('w:eastAsia'), "SimSun")
    rf.set(qn('w:ascii'), "Times New Roman")
    rf.set(qn('w:hAnsi'), "Times New Roman")

# ══════════════════════════════════════════════════════════
# 文本清理
# ══════════════════════════════════════════════════════════
def clean_text(s):
    """彻底清理特殊字符。"""
    repl = {
        '—': '-', '–': '-', '‘': "'", '’': "'",
        '“': '"', '”': '"', '…': '...',
        '∈': ' in ', '∧': ' and ', '∨': ' or ',
        '≥': '>=', '≤': '<=', '→': '->',
        'α': 'alpha', 'β': 'beta', 'γ': 'gamma',
        'δ': 'delta', 'ε': 'epsilon', 'θ': 'theta',
        'τ': 'tau', 'π': 'pi',
        '×': 'x', '∞': 'inf',
        '（': '(', '）': ')', '，': ',',
        '：': ':', '；': ';',
        ' ': ' ', '​': '', '﻿': '',
    }
    for k, v in repl.items(): s = s.replace(k, v)
    # 去掉 markdown 标记
    s = s.replace('**', '')
    # 清除独立短线：行首/行尾/空白间的短线
    s = re.sub(r'\s*---\s*', ' ', s)
    s = re.sub(r'(?<!\w)-(?!\w)', ' ', s)  # 非单词间的孤立短线
    s = re.sub(r'-{2,}', ' ', s)  # 连续短线
    s = re.sub(r' {2,}', ' ', s).strip()
    return s

# ══════════════════════════════════════════════════════════
# Word 方程 (OMML)
# ══════════════════════════════════════════════════════════
def _math_run(text, size=10):
    """OMML math text run"""
    return f'<m:r xmlns:m="{MATH_NS}"><m:rPr><m:nor/></m:rPr><m:t>{text}</m:t></m:r>'

_eq_num = [0]  # mutable counter

def add_equation(doc, latex_str):
    """渲染公式为居中斜体文本，右侧加编号 (1)(2)..."""
    s = latex_str.strip()
    s = re.sub(r'\\(?:text|mathbf|mathit|mathrm)\{([^}]*)\}', r'\1', s)
    s = s.replace('\\cdot', ' * ').replace('\\times', ' x ')
    s = s.replace('\\geq', '>=').replace('\\leq', '<=')
    s = s.replace('\\rightarrow', '->').replace('\\land', ' and ')
    s = s.replace('\\lor', ' or ').replace('\\ldots', '...')
    s = s.replace('\\_', '_').replace('{', '').replace('}', '')
    greek = {'\\alpha':'alpha','\\beta':'beta','\\gamma':'gamma','\\delta':'delta',
             '\\varepsilon':'eps','\\epsilon':'eps','\\theta':'theta',
             '\\tau':'tau','\\pi':'pi','\\sigma':'sigma','\\omega':'omega'}
    for k, v in greek.items(): s = s.replace(k, v)
    s = s.replace('\\mathcal{H}', 'H').replace('\\mathcal{Q}', 'Q')
    s = s.replace('\\mathcal{M}', 'M').replace('\\mathcal{D}', 'D')
    s = s.replace('\\mathcal{G}', 'G').replace('\\mathcal{T}', 'T')
    s = s.replace('\\mathbb{E}', 'E').replace('\\mathbb{I}', 'I')
    s = s.replace('\\text', '').replace('\\begin{cases}', '').replace('\\end{cases}', '')
    s = re.sub(r'\\([a-zA-Z]+)', r'\1', s).strip()
    if not s: return

    _eq_num[0] += 1
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    p.paragraph_format.space_before = Pt(3)
    p.paragraph_format.space_after = Pt(3)
    # 公式内容（居中）
    r = p.add_run(s)
    r.font.italic = True; cn(r, 8)
    # 右侧编号
    tab = p.add_run('  ')
    rn = p.add_run(f'({_eq_num[0]})')
    rn.font.size = Pt(8); rn.font.name = 'Times New Roman'
    # 用右对齐 tab
    from docx.oxml import OxmlElement
    pPr = p._element.get_or_add_pPr()
    tabs = OxmlElement('w:tabs')
    tab_stop = OxmlElement('w:tab')
    tab_stop.set(qn('w:val'), 'right')
    tab_stop.set(qn('w:pos'), '9072')  # ~16cm right margin for Letter
    tabs.append(tab_stop)
    pPr.append(tabs)

# ══════════════════════════════════════════════════════════
# 构建
# ══════════════════════════════════════════════════════════
def find_fig(stem):
    for d in [FIG_DIR, EXP_DIR]:
        p = d / f"{stem}.png"
        if p.exists(): return str(p)
    return None

def add_fig(doc, stem):
    cap, w = FIG_DEF.get(stem, ("", 5.0))
    path = find_fig(stem)
    if not path: return
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(8)
    p.paragraph_format.space_after = Pt(2)
    p.add_run().add_picture(path, width=Inches(w))
    pc = doc.add_paragraph()
    pc.alignment = WD_ALIGN_PARAGRAPH.CENTER
    pc.paragraph_format.space_after = Pt(10)
    r = pc.add_run(cap); cn(r, 9)
    r.font.color.rgb = RGBColor(0x33, 0x33, 0x33)

def add_para(doc, text, size=10, bold=False, align=WD_ALIGN_PARAGRAPH.JUSTIFY, sb=0, sa=4):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(sb)
    p.paragraph_format.space_after = Pt(sa)
    p.paragraph_format.line_spacing = 1.15
    p.alignment = align
    if not text: return p
    text = clean_text(text)
    # 处理行内公式 $...$ -> 斜体
    parts = re.split(r'(\$[^$]+\$)', text)
    for part in parts:
        if part.startswith('$') and part.endswith('$'):
            inner = part[1:-1]
            inner = re.sub(r'\\(?:text|mathcal|mathbb)\{([^}]*)\}', r'\1', inner)
            inner = re.sub(r'\\[a-zA-Z]+', lambda m: m.group(0)[1:], inner)
            inner = inner.replace('{','').replace('}','')
            r = p.add_run(inner)
            r.font.italic = True; cn(r, size, bold)
        elif part.strip():
            r = p.add_run(part); cn(r, size, bold)
    return p

def add_table_block(doc, headers, rows, caption):
    pc = doc.add_paragraph()
    pc.alignment = WD_ALIGN_PARAGRAPH.CENTER
    pc.paragraph_format.space_before = Pt(8)
    pc.paragraph_format.space_after = Pt(2)
    r = pc.add_run(caption); cn(r, 8, bold=True)
    ncols = len(headers)
    nrows = len(rows) + 1
    table = doc.add_table(rows=nrows, cols=ncols)
    table.style = "Light Grid Accent 1"
    # 自动适应窗口宽度
    table.autofit = True
    for j in range(ncols):
        for i in range(nrows):
            c = table.rows[i].cells[j]
            for pp in c.paragraphs:
                pp.alignment = WD_ALIGN_PARAGRAPH.CENTER
                pp.paragraph_format.space_before = Pt(1)
                pp.paragraph_format.space_after = Pt(1)
                pp.paragraph_format.line_spacing = 1.0
    for j, h in enumerate(headers):
        table.rows[0].cells[j].text = ""
        r = table.rows[0].cells[j].paragraphs[0].add_run(h)
        cn(r, 7, bold=True)
    for i, row in enumerate(rows):
        for j, val in enumerate(row):
            table.rows[i+1].cells[j].text = ""
            r = table.rows[i+1].cells[j].paragraphs[0].add_run(val)
            cn(r, 7, bold=(i==0))

def _setup_section(sec, num_cols=1, continuous=False):
    """设置 Letter 页面 + N 栏布局。continuous=True 表示连续分节（不分页）。"""
    sec.page_width = Cm(21.59); sec.page_height = Cm(27.94)
    sec.top_margin = Cm(1.9); sec.bottom_margin = Cm(2.54)
    sec.left_margin = Cm(1.57); sec.right_margin = Cm(1.57)
    space = "18pt" if num_cols == 2 else "36pt"
    cols_xml = f'<w:cols {nsdecls("w")} w:num="{num_cols}" w:space="{space}"/>'
    sec._sectPr.append(parse_xml(cols_xml))
    if continuous:
        type_el = parse_xml(f'<w:type {nsdecls("w")} w:val="continuous"/>')
        sec._sectPr.append(type_el)

def build():
    doc = Document()
    _setup_section(doc.sections[0], num_cols=1)  # 首节：单栏（标题+摘要）

    lines = MD_PATH.read_text(encoding="utf-8").split("\n")
    fig_count = 0
    i = 0
    ROMAN = {1:"I",2:"II",3:"III",4:"IV",5:"V",6:"VI",7:"VII"}
    SUBSEC = "ABCDEFGHIJ"
    sec_num = 0
    sub_num = 0
    in_table = False; table_buf = []
    in_abstract = False; abstract_lines = []
    in_keywords = False; kw_lines = []
    in_refs = False; ref_lines = []
    in_math_block = False; math_buf = []
    skip = True

    # ── 先输出大标题（单栏）──
    title_done = False
    author_done = False

    while i < len(lines):
        s = lines[i].strip()

        # 大标题（单栏居中）
        if s.startswith("# ") and not s.startswith("## ") and not title_done:
            add_para(doc, clean_text(s[2:].strip()), size=16, bold=True,
                     align=WD_ALIGN_PARAGRAPH.CENTER, sa=8)
            title_done = True; i += 1; continue

        # 跳过直到 ## 摘要 —— 输出标题后立即加作者、切双栏
        if not author_done and title_done:
            if s.startswith("## 摘要"):
                # ── 作者信息（两作者横向并列）──
                author_sec = doc.add_section()
                _setup_section(author_sec, num_cols=2, continuous=True)
                authors = [
                    ("Ruijie Li", "Panzhihua University, Sichuan Panzhihua, 617000, China\n1107697373@qq.com"),
                    ("Bin Liu*", "Panzhihua University, Sichuan Panzhihua, 617000, China\n36637345@qq.com"),
                ]
                for name, aff in authors:
                    add_para(doc, name, size=10, align=WD_ALIGN_PARAGRAPH.CENTER, sa=1)
                    add_para(doc, aff, size=7, align=WD_ALIGN_PARAGRAPH.CENTER, sa=2)
                # ── 切换到双栏（摘要+正文）──
                new_sec = doc.add_section()
                _setup_section(new_sec, num_cols=2, continuous=True)
                author_done = True; skip = False; in_abstract = True
                i += 1; continue
            i += 1; continue

        if skip and not author_done:
            i += 1; continue

        # ── 数学块 $$...$$ ──
        if s.startswith("$$"):
            if s.endswith("$$") and len(s) > 4:
                add_equation(doc, s[2:-2])   # 单行
            elif not in_math_block:
                in_math_block = True; math_buf = [s[2:]]  # 多行开始
            elif in_math_block:
                add_equation(doc, " ".join(math_buf))  # 多行结束
                in_math_block = False; math_buf = []
            i += 1; continue
        if in_math_block:
            math_buf.append(s); i += 1; continue

        # ── 摘要 ──
        if in_abstract:
            if s.startswith("**关键词**"):
                in_abstract = False; in_keywords = True; kw_lines.append(s); i += 1; continue
            if s and not s.startswith("#"): abstract_lines.append(s)
            i += 1; continue

        # ── 关键词 ──
        if in_keywords:
            if s.startswith("## ") or s.startswith("---"):
                in_keywords = False
                abs_text = clean_text(" ".join(abstract_lines))
                p = doc.add_paragraph(); p.paragraph_format.space_after = Pt(6); p.paragraph_format.line_spacing = 1.1
                r = p.add_run("摘要: "); cn(r, 9, bold=True)
                r2 = p.add_run(abs_text); cn(r2, 9)

                kw_text = clean_text(" ".join(kw_lines).replace("**关键词**：", "").replace("**关键词**", ""))
                pk = doc.add_paragraph(); pk.paragraph_format.space_after = Pt(14)
                r = pk.add_run("关键词: "); cn(r, 9, bold=True)
                r2 = pk.add_run(kw_text); cn(r2, 9); r2.font.italic = True
                continue
            if s: kw_lines.append(s)
            i += 1; continue

        # ── 参考文献 ──
        if in_refs:
            if s.startswith("## "): in_refs = False
            elif s.startswith("[") and "]" in s: ref_lines.append(clean_text(s))
            i += 1; continue

        # ── 参考文献 ──
        if s.startswith("## 参考文献"):
            in_refs = True; i += 1; continue
        if s.startswith("## "):
            sec_num += 1; sub_num = 0
            label = f"{ROMAN.get(sec_num, str(sec_num))}.  {clean_text(s[3:].strip())}"
            add_para(doc, label, size=10, bold=True, sb=14, sa=4)
            i += 1; continue
        if s.startswith("### "):
            sub_letter = SUBSEC[sub_num] if sub_num < len(SUBSEC) else str(sub_num)
            sub_num += 1
            label = f"{sub_letter}. {clean_text(s[4:].strip())}"
            add_para(doc, label, size=10, sb=8, sa=2)
            i += 1; continue

        # ── 表格 ──
        if s.startswith("|") and not in_table:
            in_table = True; table_buf = [s]; i += 1; continue
        if in_table:
            if s.startswith("|"):
                table_buf.append(s); i += 1; continue
            else:
                if len(table_buf) >= 3:
                    hdr = [clean_text(c.strip()) for c in table_buf[0].split("|")[1:-1]]
                    rows = []
                    for tl in table_buf[2:]:
                        cells = [clean_text(c.strip()) for c in tl.split("|")[1:-1]]
                        if cells: rows.append(cells)
                    if hdr and rows:
                        add_table_block(doc, hdr, rows, "Table 1.  Comparison of All Eight Exploration Modes.")
                table_buf = []; in_table = False; continue

        # ── 图标记 ──
        m = re.match(r'^\[FIGURE:(\w+)\]$', s)
        if m:
            add_fig(doc, m.group(1)); fig_count += 1; i += 1; continue

        # ── 分隔线、空行 ──
        if s == "---" or s == "": i += 1; continue

        # ── 普通文本 ──
        clean = clean_text(s)
        clean = re.sub(r'\*\*(.+?)\*\*', r'\1', clean)
        if clean: add_para(doc, clean, size=10)
        i += 1

    # ── 参考文献（紧接正文，不分页）──
    if ref_lines:
        add_para(doc, "", size=6, sa=4)  # 小间距
        add_para(doc, "参考文献", size=10, bold=True, sa=6)
        for rl in ref_lines:
            p = doc.add_paragraph()
            p.paragraph_format.space_after = Pt(2)
            p.paragraph_format.left_indent = Cm(0.6)
            p.paragraph_format.first_line_indent = Cm(-0.6)
            r = p.add_run(rl); cn(r, 8)

    doc.save(str(OUT))
    print(f"Saved: {OUT.name}  ({OUT.stat().st_size/1024:.0f} KB)  Figures: {fig_count}/{len(FIG_DEF)}")

def build_en():
    """生成英文版（复用所有修复）。"""
    doc = Document()
    _setup_section(doc.sections[0], num_cols=1)  # 首节：单栏

    # Title (single column)
    add_para(doc, "Autonomous Data Exploration Agent with\nLineage-Constrained and Adaptive Information Gain",
             size=16, bold=True, align=WD_ALIGN_PARAGRAPH.CENTER, sa=8)

    # ── 两作者横向并列 ──
    author_sec = doc.add_section()
    _setup_section(author_sec, num_cols=2, continuous=True)
    en_authors = [
        ("Ruijie Li", "Panzhihua University, Sichuan Panzhihua, 617000, China\n1107697373@qq.com"),
        ("Bin Liu*", "Panzhihua University, Sichuan Panzhihua, 617000, China\n36637345@qq.com"),
    ]
    for name, aff in en_authors:
        add_para(doc, name, size=10, align=WD_ALIGN_PARAGRAPH.CENTER, sa=1)
        add_para(doc, aff, size=7, align=WD_ALIGN_PARAGRAPH.CENTER, sa=2)
    # ── 切换到双栏（摘要+正文）──
    new_sec = doc.add_section()
    _setup_section(new_sec, num_cols=2, continuous=True)

    # Abstract
    abs_text = (
        "Traditional Business Intelligence (BI) platforms follow a passive request-response paradigm. "
        "This paper proposes AEA (Autonomous Exploration Agent), integrating lineage-constrained exploration "
        "with adaptive information gain. AEA comprises five innovations: Lineage-Constrained Exploration (LCE), "
        "a Dual-Loop Architecture (BHC), Adaptive Phase-Aware Information Gain (AIG), Expected Hypothesis "
        "Information Gain (EHIG), and Posterior Staleness Convergence (PSC). "
        "Ablation experiments across eight modes demonstrate that AIG+EHIG achieves convergence in 13 queries "
        "(18.8% fewer than baseline), improving insight confidence from 0.75 to 0.82."
    )
    p = doc.add_paragraph(); p.paragraph_format.space_after = Pt(6); p.paragraph_format.line_spacing = 1.1
    r = p.add_run("Abstract: "); cn(r, 9, bold=True)
    r2 = p.add_run(abs_text); cn(r2, 9)

    kw = "Autonomous data analysis, intelligent agent, information gain, data lineage, Bayesian inference, exploration-exploitation."
    pk = doc.add_paragraph(); pk.paragraph_format.space_after = Pt(14)
    r = pk.add_run("Keywords: "); cn(r, 9, bold=True)
    r2 = pk.add_run(kw); cn(r2, 9); r2.font.italic = True

    # English sections
    en_sec = [
        ("I. INTRODUCTION", [
            "Data-driven decision-making has become a core competitive advantage. Current AI BI platforms "
            "follow a passive paradigm: users articulate analytical intents, and the system returns results. "
            "This paper proposes AEA, an autonomous exploration agent that actively explores data spaces. "
            "Key contributions: (1) LCE constrains exploration to lineage-reachable subspaces; "
            "(2) BHC decouples exploration breadth from verification depth; "
            "(3) AIG provides phase-adaptive weighting; (4) EHIG unifies exploration and verification "
            "via Bayesian experimental design; (5) PSC enables natural convergence based on posterior stagnation.",
        ]),
        ("II. RELATED WORK", [
            "A. From Passive BI to Agent Analytics. Traditional BI tools lack intelligent analysis capabilities. "
            "AI BI platforms introduce NL interfaces but remain reactive. In 2025-2026, Agent Analytics emerged: "
            "AIDA[1] formulates BI as MDP, DataSTORM[2] uses thesis-driven verification, Co-Scientist[3] "
            "employs Elo ranking. Salemi et al.[6] propose blackboard-based multi-agent discovery.",
            "B. Information Gain and Active Learning. Information gain originates from decision tree learning "
            "and active learning[7]. We transfer this concept to data analysis: IG of a candidate SQL query "
            "equals expected reduction in uncertainty about insight belief distributions.",
            "C. Data Lineage. Data lineage describes computational paths from source to metrics. "
            "Li et al.[8] trace LLM training data lineage. We establish the first connection between "
            "lineage graphs and autonomous exploration guidance.",
        ]),
        ("III. METHODOLOGY", [
            "A. System Overview. AEA comprises four components: Exploration Blackboard (shared state), "
            "Candidate Query Generator (4 strategies), Exploration Orchestrator (lineage filter + scoring), "
            "Hypothesis Manager (5-perspective LLM generation + Bayesian updating).",
            "[FIGURE:dual_loop_architecture]",
            "B. Dual-Loop Architecture (BHC). The outer exploration loop handles candidate generation, "
            "scoring, SQL execution, and blackboard updates. The inner Bayesian hypothesis loop handles "
            "LLM generation, evidence evaluation, posterior updating, and accept/reject decisions.",
            "C. Lineage-Constrained Exploration (LCE). Given lineage graph G=(V,E) and root metric m_root, "
            "only reachable fields are explored. Lineage depth serves as a positive factor in IG scoring.",
            "D. Adaptive Phase-Aware Information Gain (AIG). The base IG formula uses fixed weights "
            "(0.30, 0.35, 0.20, 0.15). AIG detects three phases: BROAD_SWEEP (coverage=0.50), "
            "DEEP_DRILL (lineage=0.40), VERIFICATION (lineage=0.50). Phase detection is automatic.",
            "[FIGURE:aig_phase_weights]",
            "[FIGURE:aig_phase_transition]",
            "E. Expected Hypothesis Information Gain (EHIG). EHIG reformulates query selection as Bayesian "
            "experimental design. Hypothesis belief is Beta(alpha,beta). Expected posterior variance and "
            "variance reduction drive query selection toward maximum uncertainty reduction.",
            "F. Posterior Staleness Convergence (PSC). PSC terminates exploration when all active hypotheses "
            "cease improving. Convergence when max IG < epsilon for c consecutive rounds.",
        ]),
        ("IV. EXPERIMENTS", [
            "A. Setup. Simulated retail BI dataset, 12 metrics, 3 dimensions, B=25, epsilon=0.04. "
            "Eight ablation modes: FULL, AIG, EHIG, AIG+EHIG, -BHC, -Lineage, -PSC, -IG.",
            "[FIGURE:heatmap_overview]",
            "B. Results. AIG+EHIG: 13 queries, 5 insights, confidence 0.82. FULL: 16 queries, 0.75. "
            "-PSC: 25 queries (56% waste). -BHC: 7 insights but confidence 0.48.",
            "[FIGURE:cumulative_discovery]",
            "C. Ablation Analysis.",
            "[FIGURE:lollipop_ablation]",
            "Lineage: -Lineage needs 20 queries (25% more). BHC: -BHC confidence drops to 0.48. "
            "AIG: phase transitions shown in Figure 6.",
            "[FIGURE:aig_phase_timeline]",
            "D. Efficiency Frontier.",
            "[FIGURE:efficiency_frontier]",
            "[FIGURE:ig_trajectory]",
            "AIG+EHIG is the unique Pareto-optimal solution.",
        ]),
        ("V. DISCUSSION", [
            "AIG works because different exploration phases have different information structures. "
            "EHIG's primary advantage is unifying exploration and verification subsystems, though "
            "computational overhead increases with hypothesis pool size. Lineage constraint effectiveness "
            "depends on graph quality. Future work: multi-domain datasets, human-in-the-loop steering, "
            "incremental exploration for live data.",
        ]),
        ("VI. CONCLUSION", [
            "AEA enables the shift from passive BI to proactive Agent Analytics through LCE, BHC, AIG, "
            "EHIG, and PSC. Experiments demonstrate 18.8% efficiency gain and 9.3% confidence improvement. "
            "Code and data are available on GitHub.",
        ]),
    ]

    for sec_title, paragraphs in en_sec:
        add_para(doc, sec_title, size=10, bold=True, sb=14, sa=4)
        for para_text in paragraphs:
            if para_text.startswith("[FIGURE:"):
                stem = para_text[8:-1]
                if stem in FIG_DEF:
                    cap, w = FIG_DEF[stem]
                    add_fig(doc, stem)
                continue
            add_para(doc, clean_text(para_text), size=10)

    # Table
    add_table_block(doc,
        ["Mode", "Queries", "Discovered", "Confidence", "Converged"],
        [["AIG+EHIG","13","5","0.82","Yes"],["AIG","14","5","0.80","Yes"],
         ["EHIG","15","5","0.82","Yes"],["FULL","16","5","0.75","Yes"],
         ["-BHC","16","7","0.48","Yes"],["-Lineage","20","4","0.68","Yes"],
         ["-PSC","25","5","0.75","No"],["-IG","23","3","0.60","Yes"]],
        "TABLE I. Comparison of All Eight Exploration Modes.")

    # References (no page break)
    add_para(doc, "", size=6, sa=6)
    add_para(doc, "REFERENCES", size=10, bold=True, sa=6)
    refs = [
        "[1] D. Wu et al., \"AIDA: Towards Autonomous BI,\" arXiv:2605.07202, 2026.",
        "[2] S. Liu et al., \"DataSTORM: Deep Research on DB using EDA,\" arXiv:2604.06474, 2026.",
        "[3] J. Gottweis et al., \"AI Co-Scientist,\" Nature, 2026.",
        "[4] InfoGain Strategy, Project Supervisor, 2025.",
        "[5] DeepAnalyze, \"Agentic LLMs for Autonomous Data Science,\" arXiv:2510.16872, 2025.",
        "[6] A. Salemi et al., \"LLM Blackboard System for Data Discovery,\" arXiv:2510.01285, 2025.",
        "[7] B. Settles, \"Active Learning Literature Survey,\" U. Wisconsin, 2009.",
        "[8] Y. Li et al., \"Tracing Data Lineage in LLMs,\" ACL, 2026.",
    ]
    for rl in refs:
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(2)
        p.paragraph_format.left_indent = Cm(0.6)
        p.paragraph_format.first_line_indent = Cm(-0.6)
        r = p.add_run(rl); cn(r, 8)

    doc.save(str(OUT_EN))
    print(f"Saved: {OUT_EN.name}  ({OUT_EN.stat().st_size/1024:.0f} KB)")

if __name__ == "__main__":
    build()
    build_en()
