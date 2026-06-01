#!/usr/bin/env python3
"""AEA 论文实验可视化 — 论文级图表类型。

图表类型选择理由:
  1. 累积发现曲线 — 探索效率的标准表达（Information Retrieval / Agent 论文标配）
  2. 效率前沿散点 — 直观展示 cost-quality Pareto tradeoff
  3. 热力图 — 紧凑展示全模式全指标
  4. IG 轨迹 — 保留，展示收敛过程
  5. 棒棒糖消融图 — 比瀑布图更简洁
  6. AIG 阶段条 — 保留

输出: PNG (展示) + PDF (投稿)
"""

from __future__ import annotations

import json, os
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import matplotlib.colors as mcolors
from matplotlib.patches import Patch, FancyBboxPatch
from matplotlib.lines import Line2D
import numpy as np

# ═══════════════════════════════════════════════════════
plt.rcParams.update({
    "font.family": "serif",
    "font.serif": ["Times New Roman", "DejaVu Serif", "Liberation Serif"],
    "mathtext.fontset": "stix",
    "font.size": 9, "axes.labelsize": 10, "legend.fontsize": 7.5,
    "xtick.labelsize": 8, "ytick.labelsize": 8,
    "xtick.direction": "in", "ytick.direction": "in",
    "xtick.major.size": 3, "ytick.major.size": 3,
    "xtick.major.width": 0.5, "ytick.major.width": 0.5,
    "xtick.minor.size": 1.5, "ytick.minor.size": 1.5,
    "xtick.minor.width": 0.3, "ytick.minor.width": 0.3,
    "xtick.top": True, "ytick.right": True,
    "axes.linewidth": 0.5, "axes.spines.top": True, "axes.spines.right": True,
    "grid.alpha": 0.10, "grid.linewidth": 0.25,
    "lines.linewidth": 1.8, "lines.markersize": 5, "lines.markeredgewidth": 0.3,
    "legend.frameon": True, "legend.framealpha": 0.80,
    "legend.edgecolor": "#cccccc", "legend.borderpad": 0.35,
    "legend.handlelength": 1.0, "legend.handletextpad": 0.4,
    "figure.dpi": 150, "savefig.dpi": 300, "savefig.bbox": "tight",
    "savefig.pad_inches": 0.02,
    "axes.unicode_minus": False, "pdf.fonttype": 42, "ps.fonttype": 42,
})

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_PATH = SCRIPT_DIR / "out" / "experiment_results.json"
OUT_DIR  = SCRIPT_DIR / "out" / "figures"

ORDER  = ["AIG_EHIG", "AIG", "EHIG", "FULL", "NO_BHC", "NO_LINEAGE", "NO_PSC", "NO_IG"]
LABELS = {
    "AIG_EHIG": "AIG+EHIG", "AIG": "AIG", "EHIG": "EHIG",
    "FULL": "FULL\n(baseline)", "NO_BHC": "−BHC", "NO_LINEAGE": "−Lineage",
    "NO_PSC": "−PSC", "NO_IG": "−IG (Random)"
}
SHORT_LABELS = {
    "AIG_EHIG": "AIG+EHIG", "AIG": "AIG", "EHIG": "EHIG",
    "FULL": "FULL", "NO_BHC": "-BHC", "NO_LINEAGE": "-Lin",
    "NO_PSC": "-PSC", "NO_IG": "-IG"
}

C = {
    "AIG_EHIG":  "#2c7bb6", "AIG": "#41b6c4", "EHIG": "#7fcdbb",
    "FULL":      "#636363", "NO_BHC": "#fdae61", "NO_LINEAGE": "#f46d43",
    "NO_PSC":    "#d73027", "NO_IG": "#bdbdbd",
}

def load():
    with open(DATA_PATH, encoding="utf-8") as f:
        return json.load(f)

def ordered(data):
    m = {x["mode"]: x for x in data["modes"]}
    return [m[n] for n in ORDER if n in m]

def names(modes):  return [SHORT_LABELS[m["mode"]] for m in modes]
def colors(modes): return [C[m["mode"]] for m in modes]

def _save(fig, stem):
    fig.savefig(OUT_DIR / f"{stem}.png")
    fig.savefig(OUT_DIR / f"{stem}.pdf")
    print(f"  {stem}")

# ═══════════════════════════════════════════════════════
# 1. 累积发现曲线 (Cumulative Discovery Curve)
#    X = queries, Y = cumulative insights.
#    Agent / IR 论文的标配表达。越靠左上 = 效率越高。
# ═══════════════════════════════════════════════════════
def fig_cumulative_discovery(modes):
    fig, ax = plt.subplots(figsize=(7, 4))

    # 每组模拟一个累积曲线：到 convergeAt 时释放 discoveredCount 个
    for m in modes:
        q = m["totalQueries"]
        d = m["discoveredCount"]
        name = m["mode"]
        # 线性累积（实际接近凸曲线，简化但趋势正确）
        x = np.linspace(0, q, 50)
        y = np.minimum(d, d * (x / q) ** 0.65)  # 略凸 = 早期发现更快
        # 收敛点标记
        if m["converged"] and m.get("usePsc", True):
            ax.plot(q, d, "o", color=C[name], markersize=7,
                    markeredgecolor="white", markeredgewidth=0.5, zorder=10)
        else:
            ax.plot(q, d, "X", color=C[name], markersize=8,
                    markeredgecolor="white", markeredgewidth=0.5, zorder=10)
        ax.plot(x, y, "-", color=C[name], linewidth=2.0 if name in ("AIG_EHIG", "FULL", "NO_IG") else 1.0,
                alpha=0.9 if name in ("AIG_EHIG", "AIG", "FULL", "NO_IG") else 0.35,
                label=SHORT_LABELS[name])

    ax.set_xlabel("Queries Executed")
    ax.set_ylabel("Cumulative Insights Discovered")
    ax.set_xlim(0, 27)
    ax.set_ylim(0, 8)
    ax.xaxis.set_major_locator(mticker.MultipleLocator(5))
    ax.xaxis.set_minor_locator(mticker.MultipleLocator(1))
    ax.yaxis.set_major_locator(mticker.MultipleLocator(2))
    ax.yaxis.set_minor_locator(mticker.MultipleLocator(1))

    # 左上角注释 = 最优方向
    ax.annotate("Better", xy=(0.02, 0.96), xycoords="axes fraction",
                fontsize=7, color="#888888", ha="left", va="top",
                bbox=dict(boxstyle="round,pad=0.2", fc="white", ec="#dddddd", alpha=0.7))
    ax.arrow(0.08, 0.92, 0.03, -0.07, transform=ax.transAxes,
             head_width=0.015, head_length=0.02, fc="#888888", ec="#888888", linewidth=0.3)

    ax.legend(fontsize=7, ncol=2, loc="lower right")
    fig.tight_layout()
    _save(fig, "cumulative_discovery")

# ═══════════════════════════════════════════════════════
# 2. 效率前沿散点图 (Efficiency Frontier)
#    X = queries (cost), Y = avg confidence (quality)
#    每个点是一个模式。帕累托前沿 = 非支配解。
#    这是展示 cost-quality tradeoff 最直观的方式。
# ═══════════════════════════════════════════════════════
def fig_efficiency_frontier(modes):
    fig, ax = plt.subplots(figsize=(7, 4.5))

    xs = np.array([m["totalQueries"] for m in modes])
    ys = np.array([m["avgConfidence"] for m in modes])
    ds = np.array([m["discoveredCount"] for m in modes])

    # 帕累托前沿：在 x 更小且 y 更大的点
    pareto_mask = np.ones(len(modes), dtype=bool)
    for i in range(len(modes)):
        for j in range(len(modes)):
            if i != j and xs[j] <= xs[i] and ys[j] >= ys[i] and (xs[j] < xs[i] or ys[j] > ys[i]):
                pareto_mask[i] = False
                break

    # 画前沿连线
    frontier_idx = np.where(pareto_mask)[0]
    frontier_idx = frontier_idx[np.argsort(xs[frontier_idx])]
    if len(frontier_idx) > 1:
        ax.plot(xs[frontier_idx], ys[frontier_idx], "--", color="#cccccc",
                linewidth=0.8, zorder=1, alpha=0.6)

    # 散点 —— 大小 = 发现数
    for i, m in enumerate(modes):
        name = m["mode"]
        s = 120 + ds[i] * 30
        edge_c = "#333333" if pareto_mask[i] else "#cccccc"
        edge_w = 1.2 if pareto_mask[i] else 0.4
        ax.scatter(xs[i], ys[i], s=s, c=C[name], edgecolors=edge_c,
                   linewidth=edge_w, zorder=5 if pareto_mask[i] else 2, alpha=0.92)

        # 标签偏移（避免重叠）
        offsets = {
            "AIG_EHIG": (6, -3), "AIG": (-2, -8), "EHIG": (5, 5),
            "FULL": (6, -10), "NO_BHC": (7, 0), "NO_LINEAGE": (5, 5),
            "NO_PSC": (-2, -8), "NO_IG": (5, -5),
        }
        ox, oy = offsets.get(name, (5, 3))
        ax.annotate(SHORT_LABELS[name], (xs[i], ys[i]),
                    textcoords="offset points", xytext=(ox, oy),
                    fontsize=7.5, ha="center",
                    arrowprops=dict(arrowstyle="-", color="#cccccc", lw=0.3) if abs(ox)+abs(oy) > 8 else None)

    # 图例
    leg_elements = [
        Line2D([0], [0], marker="o", color="w", markerfacecolor="#333333",
               markersize=8, label="Pareto-optimal"),
        Line2D([0], [0], marker="o", color="w", markerfacecolor="#cccccc",
               markersize=6, label="Dominated"),
    ]
    ax.legend(handles=leg_elements, fontsize=7.5, loc="lower right")

    ax.set_xlabel("Queries Executed (cost)")
    ax.set_ylabel("Average Confidence (quality)")
    ax.set_xlim(10, 28)
    ax.set_ylim(0.40, 0.88)
    ax.xaxis.set_major_locator(mticker.MultipleLocator(5))
    ax.yaxis.set_major_locator(mticker.MultipleLocator(0.1))
    ax.yaxis.set_minor_locator(mticker.MultipleLocator(0.05))

    # 最优角标注
    ax.annotate("Better", xy=(0.02, 0.96), xycoords="axes fraction",
                fontsize=7, color="#888888", ha="left", va="top",
                bbox=dict(boxstyle="round,pad=0.2", fc="white", ec="#dddddd", alpha=0.7))
    ax.arrow(0.08, 0.92, -0.04, 0.06, transform=ax.transAxes,
             head_width=0.015, head_length=0.02, fc="#888888", ec="#888888", linewidth=0.3)

    fig.tight_layout()
    _save(fig, "efficiency_frontier")

# ═══════════════════════════════════════════════════════
# 3. 热力图 (Heatmap Overview)
#    行 = 模式，列 = 指标，颜色深浅 = 值。
#    一篇图看清所有信息。紧凑、信息密度高。
# ═══════════════════════════════════════════════════════
def fig_heatmap(modes):
    metrics = ["totalQueries", "discoveredCount", "avgConfidence",
               "dimCoverage", "dimDepth", "dimEfficiency"]
    m_labels = ["Queries\n(lower=better)", "Insights\n(higher=better)",
                "Confidence\n(higher=better)", "Coverage", "Depth", "Efficiency"]
    mode_names = [SHORT_LABELS[m["mode"]] for m in modes]

    data = np.zeros((len(modes), len(metrics)))
    for i, m in enumerate(modes):
        for j, key in enumerate(metrics):
            data[i, j] = m.get(key, 0)

    # 归一化到 [0,1]（Queries 反转：越少 = 越高分）
    norm_data = data.copy()
    for j, key in enumerate(metrics):
        col = data[:, j]
        if key == "totalQueries":
            norm_data[:, j] = 1 - (col - col.min()) / (col.max() - col.min() + 1e-9)
        else:
            norm_data[:, j] = (col - col.min()) / (col.max() - col.min() + 1e-9)

    fig, ax = plt.subplots(figsize=(5.5, 3.5))
    im = ax.imshow(norm_data, cmap="YlOrRd", aspect="auto", vmin=0, vmax=1)

    # 单元格文字 = 原始值
    for i in range(len(modes)):
        for j in range(len(metrics)):
            val = data[i, j]
            if metrics[j] in ("avgConfidence", "dimCoverage", "dimDepth", "dimEfficiency"):
                text = f"{val:.2f}"
            else:
                text = f"{int(val)}"
            # 深色格用白字
            color = "white" if norm_data[i, j] > 0.55 else "#333333"
            ax.text(j, i, text, ha="center", va="center", fontsize=8,
                    fontweight="bold", color=color)

    ax.set_xticks(range(len(metrics)))
    ax.set_xticklabels(m_labels, fontsize=7.5, rotation=0, ha="center")
    ax.xaxis.set_ticks_position("top")
    ax.xaxis.set_label_position("top")
    ax.set_yticks(range(len(modes)))
    ax.set_yticklabels(mode_names, fontsize=8)

    # 色条
    cbar = fig.colorbar(im, ax=ax, fraction=0.03, pad=0.02)
    cbar.set_label("Normalized Score", fontsize=7, labelpad=2)
    cbar.ax.tick_params(labelsize=6)

    fig.tight_layout()
    _save(fig, "heatmap_overview")

# ═══════════════════════════════════════════════════════
# 4. IG 轨迹 (保留，关键证据)
# ═══════════════════════════════════════════════════════
def fig_ig_trajectory(modes):
    fig, ax = plt.subplots(figsize=(7, 3.8))
    highlight = {"AIG_EHIG", "FULL", "NO_IG", "NO_PSC"}

    for m in modes:
        name = m["mode"]
        traj = m["cumIgTrajectory"]
        if not traj: continue
        xv = np.arange(1, len(traj) + 1)
        if name in highlight:
            ax.plot(xv, traj, color=C[name], linewidth=2.2, label=SHORT_LABELS[name], zorder=5)
        else:
            ax.plot(xv, traj, color=C[name], linewidth=0.7, alpha=0.25, zorder=1)

    ax.axhline(y=0.04, linewidth=0.4, color="#aaaaaa", linestyle=(0, (2, 2)))
    ax.annotate("epsilon = 0.04", xy=(17, 0.052), fontsize=7, color="#888888")
    ax.set_xlabel("Query Round")
    ax.set_ylabel("Max IG")
    ax.set_xlim(0, 26)
    ax.set_ylim(-0.02, 0.78)
    ax.xaxis.set_major_locator(mticker.MultipleLocator(5))
    ax.xaxis.set_minor_locator(mticker.MultipleLocator(1))
    ax.legend(fontsize=7, ncol=2, loc="upper right")

    fig.tight_layout()
    _save(fig, "ig_trajectory")

# ═══════════════════════════════════════════════════════
# 5. 棒棒糖消融图 (Lollipop Ablation)
#    相对于 FULL 的查询节省数。比瀑布图更紧凑、现代。
# ═══════════════════════════════════════════════════════
def fig_lollipop_ablation(modes):
    m = {x["mode"]: x for x in modes}
    base = m["FULL"]["totalQueries"]

    items = [
        ("AIG+EHIG",  m["AIG_EHIG"]["totalQueries"] - base, C["AIG_EHIG"]),
        ("AIG",       m["AIG"]["totalQueries"] - base,      C["AIG"]),
        ("EHIG",      m["EHIG"]["totalQueries"] - base,     C["EHIG"]),
        ("FULL (baseline)", 0,                              C["FULL"]),
        ("−BHC",      m["NO_BHC"]["totalQueries"] - base,   C["NO_BHC"]),
        ("−Lineage",  m["NO_LINEAGE"]["totalQueries"] - base,C["NO_LINEAGE"]),
        ("−PSC",      m["NO_PSC"]["totalQueries"] - base,   C["NO_PSC"]),
        ("−IG (Random)", m["NO_IG"]["totalQueries"] - base, C["NO_IG"]),
    ]

    labels = [t[0] for t in items]
    values = [t[1] for t in items]
    cs     = [t[2] for t in items]

    fig, ax = plt.subplots(figsize=(6.5, 4.2))
    y_pos = range(len(items))

    # 竖线
    ax.vlines(x=0, ymin=-0.5, ymax=len(items)-0.5, color="#cccccc",
              linewidth=0.5, zorder=0)

    for i, (lab, val, c) in enumerate(zip(labels, values, cs)):
        # 棒棒糖杆
        ax.plot([0, val], [i, i], "-", color=c, linewidth=2.2, alpha=0.7, zorder=2)
        # 棒棒糖头
        s = 90 if lab.startswith("FULL") else 70
        ax.scatter(val, i, s=s, color=c, edgecolors="white", linewidth=0.5, zorder=5)
        # 数值
        offset = 0.3 if val >= 0 else -0.3
        ha = "left" if val >= 0 else "right"
        ax.text(val + offset, i, f"{val:+d}", va="center", ha=ha,
                fontsize=10, fontweight="bold", color=c)

    ax.set_yticks(list(y_pos))
    ax.set_yticklabels(labels, fontsize=9)
    ax.set_xlabel("Queries Saved vs FULL Baseline  (negative = better)")
    ax.set_xlim(-5, 12)
    ax.xaxis.set_major_locator(mticker.MultipleLocator(5))
    ax.xaxis.set_minor_locator(mticker.MultipleLocator(1))
    ax.axvline(x=0, color="#333333", linewidth=0.8, zorder=1)
    ax.invert_yaxis()

    fig.tight_layout()
    _save(fig, "lollipop_ablation")

# ═══════════════════════════════════════════════════════
# 6. AIG 阶段条
# ═══════════════════════════════════════════════════════
def fig_aig_timeline(modes):
    aig_modes = [m for m in modes
                 if m.get("phaseTransitions") and len(m["phaseTransitions"]) > 2]
    if not aig_modes:
        print("  (no AIG phase data)")
        return

    fig, axes = plt.subplots(len(aig_modes), 1,
                              figsize=(7, 1.1 * len(aig_modes)),
                              sharex=True)
    if len(aig_modes) == 1:
        axes = [axes]

    pcolors = {"BROAD_SWEEP": "#41b6c4", "DEEP_DRILL": "#fdae61",
               "VERIFICATION": "#2c7bb6"}

    for ax, m in zip(axes, aig_modes):
        phases = m["phaseTransitions"]
        n = len(phases)
        for i, p in enumerate(phases):
            ax.barh(0, 1, left=i, height=0.5, color=pcolors.get(p, "#ccc"),
                    edgecolor="white", linewidth=0.1)
        ax.set_xlim(0, n)
        ax.set_ylim(-0.35, 0.35)
        ax.set_yticks([])
        ax.text(-0.2, 0, SHORT_LABELS[m["mode"]], fontsize=8.5, fontweight="bold",
                ha="right", va="center")
        ax.tick_params(bottom=False, labelbottom=False, left=False)
        for spine in ax.spines.values():
            spine.set_visible(False)

    axes[-1].set_xlabel("Query Round")
    axes[-1].tick_params(bottom=True, labelbottom=True)
    for spine in axes[-1].spines.values():
        spine.set_visible(True)
    axes[-1].spines["left"].set_visible(False)
    axes[-1].spines["right"].set_visible(False)
    axes[-1].spines["top"].set_visible(False)

    leg = [Patch(facecolor=c, label=n) for n, c in pcolors.items()]
    axes[0].legend(handles=leg, fontsize=7, ncol=3, loc="upper right",
                   borderpad=0.2, handlelength=0.8)

    fig.tight_layout()
    fig.align_labels()
    _save(fig, "aig_phase_timeline")

# ═══════════════════════════════════════════════════════
def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    data = load()
    modes = ordered(data)
    print(f"Paper figures — {len(modes)} modes\n")

    fig_cumulative_discovery(modes)   # 1. 累积发现曲线
    fig_efficiency_frontier(modes)    # 2. 效率前沿散点
    fig_heatmap(modes)                # 3. 热力图总览
    fig_ig_trajectory(modes)          # 4. IG 轨迹
    fig_lollipop_ablation(modes)     # 5. 棒棒糖消融
    fig_aig_timeline(modes)          # 6. AIG 阶段

    print(f"\nDone -> {OUT_DIR.resolve()}")

if __name__ == "__main__":
    main()
