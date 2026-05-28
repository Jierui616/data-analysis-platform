# InfoGain-EDA: Information Gain-Driven Exploratory Data Analysis Agent on Lineage-Constrained BI

> 设计文档 · 2026-05-28

## 1. 问题定义

### 1.1 核心问题

传统 BI 系统是被动问答式的：用户提出问题，系统返回答案。但现实中，最好的数据分析师不是被提问的——**他们是主动探索的**：拿到数据后自发产生假设、自行验证、发现值得关注的模式，最终产出洞察报告。

**研究问题**：能否设计一个 LLM Agent，使其在无用户目标和外部信号的情况下，自主探索 BI 数据空间，产出准确、深入、新颖的分析洞察？

### 1.2 四个研究子问题

| ID | 问题 |
|----|------|
| RQ1 | 如何在数据血缘图约束下，用信息增益驱动 Agent 的探索决策，在有限查询预算内最大化洞察发现量？ |
| RQ2 | Agent 如何同时在多维度生成候选假设，并基于 Bayesian 框架排序验证优先级？ |
| RQ3 | 在无外部目标信号时，Agent 如何自主判断"分析完成"——即剩余信息增益不足以支撑进一步探索？ |
| RQ4 | 整个探索轨迹如何被结构化记录，使得人类可以回溯"为什么做这个查询"、"这个洞察如何发现"？ |

### 1.3 与现有工作的关键差异化

| | AIDA (2026) | DeepAnalyze (2025) | HypoAgents (2025) | **InfoGain-EDA** |
|---|---|---|---|---|
| 领域 | 即时零售 | 通用数据科学 | 科研假设 | **连锁餐饮 BI** |
| 探索策略 | RL + Pareto | 课程训练 | Bayes + Entropy | **信息增益 + 血缘约束** |
| 利用血缘 | 否 | 否 | 否 | **是（独有优势）** |
| 收敛判据 | 固定步数 | 固定步数 | 迭代次数 | **自适应 IG 阈值** |
| 实验数据 | 200+ 指标 | 开源数据 | ICLR 论文 | **真实餐饮经营数据** |

## 2. 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│                      用户交互层                                │
│  "帮我分析这组数据" → Agent 自主探索 → 可交互分析报告           │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│                    InfoGain-EDA 核心引擎                       │
│                                                               │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐ │
│  │  模块一          │  │  模块二         │  │  模块三       │ │
│  │  探索策略模块     │  │  假设管理模块    │  │  报告合成模块  │ │
│  │                  │  │                  │  │               │ │
│  │ · 信息增益计算    │  │ · 多维假设生成   │  │ · 洞察排序    │ │
│  │ · 血缘图剪枝     │  │ · Bayesian 排序  │  │ · 证据链组装  │ │
│  │ · 下一查询决策   │  │ · 假设-验证循环  │  │ · 交互报告    │ │
│  └────────┬────────┘  └────────┬─────────┘  └───────┬───────┘ │
│           │                    │                     │         │
│  ┌────────▼────────────────────▼─────────────────────▼───────┐│
│  │              共享状态黑板 (Exploration Blackboard)          ││
│  │  S_explored · H_verified · H_pending · H_active · Trace T ││
│  └──────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│                     数据基础设施层                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────┐ │
│  │ Lineage  │  │ClickHouse│  │  MySQL   │  │   MinIO     │ │
│  │ Service  │  │  OLAP    │  │  元数据   │  │   CSV存储   │ │
│  └──────────┘  └──────────┘  └──────────┘  └─────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

## 3. 模块一：探索策略

### 3.1 分析空间形式化

```
分析空间 Ω = D_time × D_space × D_struct × D_metric

D_time   = {日, 周, 月, 季度, 同比, 环比}
D_space  = {大区, 城市, 门店}    ← 来自血缘图
D_struct = {品类, 渠道, 人群, 供应商}
D_metric = {营收, 利润, 客流, 周转, ROI, 毛利率, ...}
```

维护已探索区域集合 S_explored ⊂ Ω，初始为空。每执行一次 ClickHouse 查询，对应的分析粒度单元加入 S_explored。

### 3.2 血缘约束剪枝

数据血缘图 G = (V, E)，边表示字段间计算依赖（如 `毛利率 ← revenue, cost`）。

**剪枝规则**：候选查询 q 必须满足——q 中涉及的所有指标字段，在 G 上与当前关注的根指标之间存在可达路径。否则 q 被排除。

**效果**：候选空间从 O(n³) 降至 O(n·d)，d 为血缘图平均度数。

### 3.3 信息增益公式

```
IG(q) = w₁ × CoverageNew(q)      # 覆盖了多少未探索维度组合 (0~1)
      + w₂ × SurpriseScore(q)    # 邻近已探索区域的异常幅度 (归一化 Z-score)
      + w₃ × LineageDepth(q)     # 在血缘图中下钻的层数（鼓励深入）
      - w₄ × Cost(q)             # 查询代价（预估扫描行数归一化）
```

- **CoverageNew(q)**：q 涉及的分析单元中，`(S_explored ∩ cells(q)) / |cells(q)|` 的反比
- **SurpriseScore(q)**：q 的"邻居"已探索区域中，`|实际值 - 基线值| / σ` 的最大值
- **LineageDepth(q)**：q 沿血缘链路距根指标的跳数 / max_depth
- **Cost(q)**：ClickHouse EXPLAIN 预估扫描行数归一化

### 3.4 查询决策算法

```
function selectNextQuery(D, G, S, H_active):
    Ω ← buildAnalysisSpace(D, G)
    Q ← []

    // 策略 a: 沿血缘链路下钻
    for each metric in H_active 涉及的指标:
        for each child in G.children(metric):
            Q.add(composeQuery(child, unexploredDims(Ω, S)))

    // 策略 b: 异常区域周围横向扩展
    for each anomaly_region in H_active 中的异常假设:
        for each neighbor in adjacentDims(anomaly_region):
            Q.add(composeQuery(metric, neighbor))

    // 策略 c: 抽样未覆盖区域
    Q.addAll(sampleUnexplored(Ω, S, budget=5))

    // 血缘过滤 + IG 排序
    Q ← filterByLineageReachability(Q, G)
    q* ← argmax_q IG(q)
    return q*
```

### 3.5 收敛判据

当 `max(IG(Q_candidate)) < ε` 连续 k 轮时停止。ε 的物理含义：**剩余未探索区域中，预期新信息已不足以改变分析结论**。

## 4. 模块二：假设管理

### 4.1 假设的结构化表示

```json
{
  "id": "h_042",
  "type": "anomaly | correlation | trend | segment_diff",
  "statement": "华东区毛利率下降主要由杭州门店的食材成本上升驱动",
  "evidence_chain": [
    {"step": 1, "query_id": "q_07", "summary": "华东整体 -2.3%"},
    {"step": 2, "query_id": "q_12", "summary": "杭州 -5.1%, 其他城市平均 -0.8%"},
    {"step": 3, "query_id": "q_18", "summary": "食材成本 +12%, 人力 +3%"}
  ],
  "confidence": {"prior": 0.3, "posterior": 0.87},
  "source_queries": ["q_07", "q_12", "q_18"],
  "parent_hypothesis": "h_018",
  "lineage_path": ["毛利率→营收→成本→食材成本→供应商价格"]
}
```

### 4.2 五维度假设生成

每拿到新的查询结果，LLM 从五个维度即时生成候选假设：

| 维度 | 生成策略 | 示例 |
|------|---------|------|
| 时序对比 | 当前 vs 历史同期、环比、同比 | "Q2 毛利率低于去年同期，与原料涨价趋势一致" |
| 结构拆解 | 沿血缘图下钻，定位主要贡献子维度 | "杭州贡献了华东区毛利率降幅的 55%" |
| 异常检测 | Z-score > 2σ 的点 | "门店 A 客流在 6/15 异常下降 40%" |
| 相关性 | 血缘图中不同指标交叉关联 | "客单价上升但客流下降，可能由于提价策略" |
| 反事实试探 | "如果某因素不存在会怎样" | "若无杭州断货，华东毛利率应仅下降 1.1%" |

**血缘反幻觉机制**：每个假设必须绑定具体的血缘链路径，LLM 不允许生成不在血缘图中的字段关联假设。

### 4.3 Bayesian 排序

- **先验 P(h)**：基于假设类型基础概率和生成查询的相关性
- **似然 P(D|h)**：沿血缘链路逐层检验查询结果与假设预测方向的一致性，cᵢ ∈ [0,1]，likelihood = Πcᵢ
- **后验**：Beta-Binomial 共轭更新
- **决策**：后验 > θ_accept → 移入 H_verified；后验 < θ_reject 且 3 轮无增长 → 丢弃；否则留在 H_pending

### 4.4 假设-验证循环

```
function hypothesisCycle(r_new, G, H):
    H_new ← generateHypotheses(r_new, G)     // LLM: 绑定血缘链路
    H_all ← H.pending ∪ H_new

    for each h ∈ H_all:
        consistency ← evaluateLineageEvidence(h, G)  // ClickHouse 验证
        h.posterior ← bayesianUpdate(h.prior, consistency)

    // 分类
    H_verified  ← {h | h.posterior > θ_accept}
    H_discard   ← {h | h.posterior < θ_reject ∧ h.stale_rounds > 3}
    H_pending   ← H_all \ H_verified \ H_discard

    // 为 Top-K active 假设生成验证查询
    for each h ∈ topK(H_active, K):
        verification_queries ← generateVerificationQueries(h)
        pushToQueryPool(verification_queries)

    return (H_verified, H_pending, H_active)
```

## 5. 模块三：报告合成

### 5.1 洞察排序

```
InsightScore(h) = α × EvidenceStrength(h)       # 证据链完整度
                + β × BusinessImpact(h)        # LLM 评估业务影响 1-5
                + γ × Novelty(h)              # 与常识背离度 1-5
                - δ × Redundancy(h, H_report)  # 与已写入洞察的语义相似度
```

### 5.2 报告结构

```
1. Executive Summary — 3-5 条最重要洞察，每条+置信度
2. Anomalies & Alerts    — 异常指标 + 根因追溯 + 影响量化 + 可展开证据链
3. Deep Insights         — 相关性、趋势、结构分析 + 可执行建议
4. Open Leads            — 证据不足但值得关注的假设 + 补充数据建议
5. Exploration Audit     — 完整查询轨迹 + 每一步决策理由 + 血缘路径可视化
```

### 5.3 交互式追问

- 用户点击任一条洞察 → 展开完整证据链（查询→结果→血缘路径）
- 用户问"为什么置信度是 X%" → 展示 Bayesian 更新过程
- 用户选择"继续深挖这个方向" → 激活相关假设，追加查询预算

## 6. 实验设计

### 6.1 实验一：探索效率

**对比系统**：Random / Grid / AIDA-style RL / No-Lineage (消融) / InfoGain-EDA (完整)

**指标**：Insight Recall@K、Coverage Efficiency、Query Waste Ratio

**数据集**：store_daily_metric + 成本/排班数据，人工注入已知异常

### 6.2 实验二：洞察质量

**方式**：5-10 位经营分析人员盲评

**对比**：InfoGain-EDA vs GPT-4 直接分析同一份数据写报告

**维度**：准确性、深度、新颖性、证据链可信度、可执行性 (Likert 1-5)

### 6.3 实验三：消融实验

依次去掉：血缘约束 / SurpriseScore / Novelty 排序 / Bayesian 更新

### 6.4 实验四：外部基准

在 DDR-Bench 框架下评估，与已公开结果直接对比

## 7. 论文大纲

```
1. Introduction
2. Related Work
   - LLM Agents for Data Analysis
   - EDA Automation
   - Hypothesis Generation & Verification
   - Data Lineage & Causality
3. Method: InfoGain-EDA
   3.1 Problem Formalization
   3.2 Lineage-Constrained Exploration Space
   3.3 Information Gain-Driven Query Strategy
   3.4 Multi-Dimensional Hypothesis Management
   3.5 Insight Synthesis & Interactive Report
4. Experiments
   4.1 Setup
   4.2 Exploration Efficiency (RQ1)
   4.3 Insight Quality (RQ2)
   4.4 Ablation Study (RQ3)
   4.5 Case Study
5. Discussion & Limitations
6. Conclusion
```

## 8. 实现范围（第一阶段）

| 模块 | 后端新增 | 前端新增/改造 |
|------|---------|-------------|
| 探索策略引擎 | `ExplorationStrategyService` + `LineageGraphTraverser` | — |
| 假设管理器 | `HypothesisManager` + `HypothesisVerifyExecutor` | — |
| 报告合成 | `InsightReportService` + `EvidenceChainBuilder` | — |
| 共享黑板 | `ExplorationBlackboard` (Redis 或内存) | — |
| 新 Controller | `AutoEdaController` (`/api/auto-eda`) | 新建 `AutoEDA` 页面 |
| AI 调用 | 复用 `AiManager`，新增 5 个 Prompt 模板 | — |
| ClickHouse 查询 | `ClickHouseQueryService` (复用大数据栈) | — |
| 血缘图查询 | 复用 `LineageService`，增加 `LineageGraphQueryService` | — |
| 交互报告页面 | — | 新建 `InsightReport` 页面 |

## 9. 关键技术栈

- **后端**：Java 8+, Spring Boot 2.7, MyBatis-Plus
- **OLAP**：ClickHouse (已有)
- **AI**：阿里云百炼 DashScope (已有 AiManager)
- **血缘**：LineageService (已有)
- **缓存**：Redis (已有，用于黑板状态)
- **前端**：React 18 + Ant Design 5 + ECharts
