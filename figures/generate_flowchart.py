#!/usr/bin/env python3
"""生成 AEA 论文插图：系统架构、双循环、AIG 阶段权重（无图题）。"""
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch
import numpy as np

plt.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "DejaVu Serif"],
    "font.size": 9,
    "axes.unicode_minus": False,
    "savefig.dpi": 300,
    "savefig.bbox": "tight",
    "savefig.pad_inches": 0.08,
})

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)))


def _rounded_box(ax, cx, cy, w, h, label, fc, ec, lw=1.2, fontsize=8, bold=False):
    x, y = cx - w / 2, cy - h / 2
    ax.add_patch(FancyBboxPatch(
        (x, y), w, h, boxstyle="round,pad=0.12,rounding_size=0.08",
        fc=fc, ec=ec, lw=lw, zorder=2))
    ax.text(cx, cy, label, ha="center", va="center", fontsize=fontsize,
            fontweight="bold" if bold else "normal", zorder=3)


def _arrow(ax, x1, y1, x2, y2, color="#555", lw=1.4, style="-|>", zorder=1):
    ax.annotate("", xy=(x2, y2), xytext=(x1, y1),
                arrowprops=dict(arrowstyle=style, color=color, lw=lw,
                                shrinkA=2, shrinkB=2), zorder=zorder)


def _polyline_arrow(ax, points, color, lw=1.3):
    for i in range(len(points) - 2):
        x1, y1 = points[i]
        x2, y2 = points[i + 1]
        ax.plot([x1, x2], [y1, y2], color=color, lw=lw, solid_capstyle="round", zorder=1)
    x1, y1 = points[-2]
    x2, y2 = points[-1]
    _arrow(ax, x1, y1, x2, y2, color=color, lw=lw)


def fig_system_architecture():
    """系统总体架构（无顶栏图题）"""
    fig, ax = plt.subplots(figsize=(11, 7.2))
    ax.set_xlim(0, 11)
    ax.set_ylim(0, 7.2)
    ax.axis("off")

    _rounded_box(ax, 2.2, 6.15, 1.6, 0.55, "User", "#e8f4fd", "#2c7bb6", fontsize=10, bold=True)
    _rounded_box(ax, 8.8, 6.15, 2.4, 0.7, "LLM Service\n(Ollama / OpenAI)", "#e8f4fd", "#2c7bb6", fontsize=8)
    _arrow(ax, 3.0, 6.15, 7.6, 6.15, "#2c7bb6", lw=1.0, style="<|-|>")

    frame = FancyBboxPatch((0.55, 2.35), 9.9, 3.35, boxstyle="round,pad=0.08,rounding_size=0.15",
                           fc="#fafafa", ec="#888888", lw=1.0, ls="--", zorder=0)
    ax.add_patch(frame)

    modules = [
        (2.0, 4.85, 2.1, 0.95, "Exploration\nBlackboard", "#d5e8d4", "#5a8a5a"),
        (5.5, 4.85, 2.1, 0.95, "Exploration\nOrchestrator", "#dae8fc", "#2c7bb6"),
        (9.0, 4.85, 2.1, 0.95, "Hypothesis\nManager", "#fff2cc", "#d6a010"),
        (2.0, 3.15, 2.1, 0.95, "Candidate Query\nGenerator", "#e1d5e7", "#7b5a8a"),
        (5.5, 3.15, 2.3, 0.95, "InfoGain / AIG\n/ EHIG Calculator", "#f8cecc", "#b85450"),
        (9.0, 3.15, 2.1, 0.95, "Query\nGenerator", "#d5e8d4", "#5a8a5a"),
    ]
    for cx, cy, w, h, label, fc, ec in modules:
        _rounded_box(ax, cx, cy, w, h, label, fc, ec, fontsize=7.5)

    flow = [
        ((3.05, 4.85), (4.45, 4.85)),
        ((6.55, 4.85), (7.95, 4.85)),
        ((5.5, 4.37), (5.5, 3.62)),
        ((3.05, 3.15), (4.35, 3.15)),
        ((6.65, 3.15), (7.95, 3.15)),
        ((2.0, 4.37), (2.0, 3.62)),
        ((9.0, 4.37), (9.0, 3.62)),
    ]
    for (x1, y1), (x2, y2) in flow:
        _arrow(ax, x1, y1, x2, y2, "#666666", lw=1.1)

    _arrow(ax, 2.2, 5.87, 5.5, 5.35, "#2c7bb6", lw=1.0)
    ax.text(3.6, 5.72, "API", fontsize=7, color="#2c7bb6", ha="center")
    _arrow(ax, 8.8, 5.78, 9.0, 5.32, "#2c7bb6", lw=0.9)
    _arrow(ax, 8.8, 5.78, 9.0, 3.62, "#2c7bb6", lw=0.9, style="-|>")
    ax.text(9.55, 4.7, "NL→SQL\nHypothesis", fontsize=6.5, color="#2c7bb6", ha="left")

    ax.plot([0.6, 10.4], [2.15, 2.15], color="#cccccc", lw=0.8, ls="--")

    datas = [
        (2.2, 1.05, "ClickHouse\n(Analytics)"),
        (5.5, 1.05, "Lineage\nGraph"),
        (8.8, 1.05, "MySQL\n(Metadata)"),
    ]
    for cx, cy, label in datas:
        _rounded_box(ax, cx, cy, 2.0, 0.75, label, "#f0f0f0", "#999999", fontsize=8)

    _arrow(ax, 5.5, 2.67, 2.2, 1.45, "#888888", lw=0.9)
    _arrow(ax, 5.5, 2.67, 5.5, 1.45, "#888888", lw=0.9)
    _arrow(ax, 5.5, 2.67, 8.8, 1.45, "#888888", lw=0.9)
    ax.text(3.0, 2.0, "SQL", fontsize=7, color="#666666")
    ax.text(5.5, 1.75, "LCE filter", fontsize=7, color="#666666", ha="center")
    ax.text(8.0, 2.0, "schema", fontsize=7, color="#666666", ha="right")

    fig.savefig(os.path.join(OUT, "system_architecture.png"))
    fig.savefig(os.path.join(OUT, "system_architecture.pdf"))
    plt.close(fig)
    print("  system_architecture.png/pdf")


def fig_dual_loop():
    """双循环架构 — 论文级设计：左侧循环标签 + 水平流水线 + 黑板底座"""
    fig, ax = plt.subplots(figsize=(11, 7.0))
    ax.set_xlim(0, 11)
    ax.set_ylim(0, 7.0)
    ax.axis("off")

    BLUE   = "#2c7bb6"
    ORANGE = "#e67e22"
    GREEN  = "#27ae60"
    GRAY   = "#888888"

    # ── 左侧：循环标签 ──
    tag_style = dict(boxstyle="round,pad=0.25,rounding_size=0.15",
                     fc="white", ec=BLUE, lw=1.5)
    ax.text(0.5, 5.20, "Outer\nLoop", ha="center", va="center",
            fontsize=9, fontweight="bold", color=BLUE, rotation=0,
            bbox=dict(boxstyle="round,pad=0.25,rounding_size=0.15", fc="#eaf2f8", ec=BLUE, lw=1.5))
    ax.text(0.5, 2.15, "Inner\nLoop", ha="center", va="center",
            fontsize=9, fontweight="bold", color=ORANGE,
            bbox=dict(boxstyle="round,pad=0.25,rounding_size=0.15", fc="#fef5e7", ec=ORANGE, lw=1.5))

    # ── 外循环（水平流水线 + 步号）──
    outer_nodes = [
        ("1", "Candidate\nGeneration", "4 strategies:\ndrill-down, neighbor,\nunexplored, verify"),
        ("2", "Lineage Filter\n+ Cost Estimate", "EXPLAIN ESTIMATE\nlineage reachable check"),
        ("3", "Score & Select\n(AIG / EHIG)", "phase-adaptive weights\nor entropy-driven"),
        ("4", "Execute SQL\n+ Observe Result", "ClickHouse query\nsurprise estimation"),
    ]
    ox_start = 1.2; oy = 5.20; ow = 2.15; oh = 1.50; ogap = 0.15
    for i, (num, title, desc) in enumerate(outer_nodes):
        cx = ox_start + i * (ow + ogap)
        # 主体框
        ax.add_patch(FancyBboxPatch((cx, oy - oh/2), ow, oh,
                     boxstyle="round,pad=0.12,rounding_size=0.10",
                     fc="white", ec=BLUE, lw=1.3, zorder=2))
        # 步号圆圈
        circ = plt.Circle((cx + 0.35, oy + oh/2 - 0.2), 0.18, fc=BLUE, ec="white", lw=0.8, zorder=3)
        ax.add_patch(circ)
        ax.text(cx + 0.35, oy + oh/2 - 0.2, num, ha="center", va="center",
                fontsize=8, fontweight="bold", color="white", zorder=4)
        # 标题
        ax.text(cx + ow/2, oy + 0.08, title, ha="center", va="center",
                fontsize=9, fontweight="bold", color="#222222", zorder=3)
        # 描述
        ax.text(cx + ow/2, oy - 0.42, desc, ha="center", va="center",
                fontsize=6.5, color="#666666", zorder=3, linespacing=1.2)

        # 步间箭头
        if i < len(outer_nodes) - 1:
            ax.annotate("", xy=(cx + ow + 0.05, oy), xytext=(cx + ow - 0.18, oy),
                       arrowprops=dict(arrowstyle="->", color=BLUE, lw=1.8, shrinkA=0, shrinkB=0))

    # 外循环回环（第4步 → 第1步）
    last_cx = ox_start + 3 * (ow + ogap)
    first_cx = ox_start
    loop_y_bot = 3.55
    _polyline_arrow(ax, [
        (last_cx + ow, oy),
        (last_cx + ow + 0.25, oy),
        (last_cx + ow + 0.25, loop_y_bot),
        (first_cx, loop_y_bot),
        (first_cx, oy - oh/2),
    ], BLUE, lw=1.3)
    ax.text(ox_start + 4.15, loop_y_bot - 0.22, "next round (until converged)",
            fontsize=7, color=BLUE, ha="center", style="italic")

    # ── 黑板（连接两层循环）──
    bb_y = 2.85; bb_h = 0.75
    ax.add_patch(FancyBboxPatch((2.5, bb_y - bb_h/2), 6.5, bb_h,
                 boxstyle="round,pad=0.15,rounding_size=0.12",
                 fc="#d5f5e3", ec=GREEN, lw=1.4, zorder=2))
    ax.text(5.75, bb_y, "Shared Blackboard", ha="center", va="center",
            fontsize=10, fontweight="bold", color="#1e6e3e", zorder=3)
    ax.text(5.75, bb_y - 0.22, "$S_{explored}$  |  $H_{pending}$  |  $H_{verified}$  |  $H_{active}$  |  trajectory $T$",
            ha="center", va="center", fontsize=7.5, color="#444444", zorder=3)

    # ── 内循环（水平流水线 + 步号）──
    inner_nodes = [
        ("1", "Hypothesis\nGeneration", "LLM: 5 perspectives\nanomaly / correlation /\ntrend / segment / counterfactual"),
        ("2", "Evidence\nEvaluation", "query result consistency\nlineage path matching"),
        ("3", "Bayesian\nPosterior Update", "Beta-Binomial conjugate\nP(H|E) posterior"),
        ("4", "Decision:\nAccept / Reject", "accept if P >= 0.60\nreject if stale >= 3 rounds\nkeep pending otherwise"),
    ]
    ix_start = 1.2; iy = 1.30; iw = 2.15; ih = 1.55
    for i, (num, title, desc) in enumerate(inner_nodes):
        cx = ix_start + i * (iw + ogap)
        ax.add_patch(FancyBboxPatch((cx, iy - ih/2), iw, ih,
                     boxstyle="round,pad=0.12,rounding_size=0.10",
                     fc="white", ec=ORANGE, lw=1.3, zorder=2))
        circ = plt.Circle((cx + 0.35, iy + ih/2 - 0.2), 0.18, fc=ORANGE, ec="white", lw=0.8, zorder=3)
        ax.add_patch(circ)
        ax.text(cx + 0.35, iy + ih/2 - 0.2, num, ha="center", va="center",
                fontsize=8, fontweight="bold", color="white", zorder=4)
        ax.text(cx + iw/2, iy + 0.12, title, ha="center", va="center",
                fontsize=8, fontweight="bold", color="#222222", zorder=3)
        ax.text(cx + iw/2, iy - 0.55, desc, ha="center", va="center",
                fontsize=5.8, color="#666666", zorder=3, linespacing=1.1)
        if i < len(inner_nodes) - 1:
            ax.annotate("", xy=(cx + iw + 0.05, iy), xytext=(cx + iw - 0.18, iy),
                       arrowprops=dict(arrowstyle="->", color=ORANGE, lw=1.8, shrinkA=0, shrinkB=0))

    # ── 层间连接线 ──
    # 外循环第4步 → 黑板
    ax.annotate("", xy=(last_cx + ow/2, bb_y + bb_h/2),
               xytext=(last_cx + ow/2, oy - oh/2),
               arrowprops=dict(arrowstyle="->", color=GREEN, lw=1.3, shrinkA=0, shrinkB=2))
    ax.text(last_cx + ow/2 + 0.3, oy - 1.0, "update\nstate", fontsize=6.5, color=GREEN, ha="left")

    # 黑板 → 内循环第1步
    ax.annotate("", xy=(ix_start + iw/2, iy + ih/2),
               xytext=(ix_start + iw/2, bb_y - bb_h/2),
               arrowprops=dict(arrowstyle="->", color=GREEN, lw=1.3, shrinkA=2, shrinkB=2))
    ax.text(ix_start + iw/2 + 0.3, bb_y - 1.0, "read\nstate", fontsize=6.5, color=GREEN, ha="left")

    # 内循环第4步 → 黑板（写回）
    last_icx = ix_start + 3 * (iw + ogap)
    _polyline_arrow(ax, [
        (last_icx + iw/2, iy - ih/2),
        (last_icx + iw/2, bb_y + bb_h/2),
    ], GREEN, lw=1.3)
    ax.text(last_icx + iw/2 + 0.3, iy - 0.1, "write back\nverified/rejected",
            fontsize=6.5, color=GREEN, ha="left")

    fig.savefig(os.path.join(OUT, "dual_loop_architecture.png"))
    fig.savefig(os.path.join(OUT, "dual_loop_architecture.pdf"))
    plt.close(fig)
    print("  dual_loop_architecture.png/pdf")


def fig_aig_phases_detail():
    """AIG 三阶段权重（原版柱状图，无总图题）"""
    fig, axes = plt.subplots(1, 3, figsize=(10.5, 3.6))
    fig.subplots_adjust(top=0.82, wspace=0.32)

    phases = [
        ("BROAD_SWEEP", [0.50, 0.15, 0.15, 0.20], "#41b6c4",
         "Coverage-dominant · Broad exploration"),
        ("DEEP_DRILL", [0.10, 0.35, 0.40, 0.15], "#fdae61",
         "Lineage-dominant · Deep root-cause tracing"),
        ("VERIFICATION", [0.05, 0.25, 0.50, 0.20], "#2c7bb6",
         "Lineage-max · Targeted verification"),
    ]
    labels = ["Coverage", "Surprise", "Lineage", "Cost"]

    for ax, (name, weights, color, desc) in zip(axes, phases):
        shades = [_lighten(color, 0.25 + 0.18 * i) for i in range(4)]
        x = np.arange(len(labels))
        bars = ax.bar(x, weights, width=0.62, color=shades,
                      edgecolor="white", linewidth=0.8)
        ax.set_xticks(x)
        ax.set_xticklabels(labels, fontsize=8)
        ax.set_ylim(0, 0.58)
        ax.set_ylabel("Weight", fontsize=8)
        ax.spines["top"].set_visible(False)
        ax.spines["right"].set_visible(False)
        ax.set_title(name, fontsize=10, fontweight="bold", color=color, pad=18)
        ax.text(0.5, 1.06, desc, transform=ax.transAxes, ha="center", fontsize=7,
                color="#555555", clip_on=False)
        for bar, w in zip(bars, weights):
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.015,
                    f"{w:.2f}", ha="center", fontsize=8, fontweight="bold", color="#333333")
        ax.axhline(0, color="#cccccc", lw=0.6)

    fig.savefig(os.path.join(OUT, "aig_phase_weights.png"))
    fig.savefig(os.path.join(OUT, "aig_phase_weights.pdf"))
    plt.close(fig)
    print("  aig_phase_weights.png/pdf")


def fig_aig_phase_transition():
    """AIG 阶段切换（原版横向流程，无底部注释）"""
    fig, ax = plt.subplots(figsize=(9, 2.8))
    ax.set_xlim(0, 9)
    ax.set_ylim(0, 2.8)
    ax.axis("off")

    nodes = [
        (1.2, "BROAD_SWEEP\n(coverage ↑)", "#41b6c4"),
        (4.5, "DEEP_DRILL\n(lineage ↑)", "#fdae61"),
        (7.8, "VERIFICATION\n(lineage max)", "#2c7bb6"),
    ]
    for cx, label, color in nodes:
        _rounded_box(ax, cx, 1.4, 2.0, 0.9, label, _lighten(color, 0.55), color, fontsize=8, bold=True)

    _arrow(ax, 2.2, 1.4, 3.5, 1.4, "#666666", lw=1.3)
    _arrow(ax, 5.5, 1.4, 6.8, 1.4, "#666666", lw=1.3)
    # redraw arrows on top of boxes
    for cx, _, _ in nodes:
        _arrow(ax, cx+1.0, 1.4, cx+2.3, 1.4, "#666666", lw=0)
    # manually redraw visible arrows at z=5
    ax.annotate("", xy=(3.5, 1.4), xytext=(2.2, 1.4),
                arrowprops=dict(arrowstyle="->", color="#555555", lw=1.8, shrinkA=8, shrinkB=8), zorder=10)
    ax.annotate("", xy=(6.8, 1.4), xytext=(5.5, 1.4),
                arrowprops=dict(arrowstyle="->", color="#555555", lw=1.8, shrinkA=8, shrinkB=8), zorder=10)

    ax.text(2.85, 1.75, "verified ≥ 3\n& budget > 33%", ha="center", fontsize=6.5, color="#555555")
    ax.text(6.15, 1.75, "budget > 80%", ha="center", fontsize=6.5, color="#555555")

    fig.savefig(os.path.join(OUT, "aig_phase_transition.png"))
    fig.savefig(os.path.join(OUT, "aig_phase_transition.pdf"))
    plt.close(fig)
    print("  aig_phase_transition.png/pdf")


def _lighten(hex_color, factor=0.4):
    import matplotlib.colors as mc
    rgb = mc.to_rgb(hex_color)
    return tuple(1.0 - factor * (1.0 - c) for c in rgb)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    print("Generating figures ...\n")
    # fig_system_architecture()  # removed per user request
    fig_dual_loop()
    fig_aig_phases_detail()
    fig_aig_phase_transition()
    print(f"\nDone -> {OUT}")
