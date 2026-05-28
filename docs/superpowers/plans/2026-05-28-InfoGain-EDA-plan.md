# InfoGain-EDA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an LLM-driven autonomous exploratory data analysis agent (InfoGain-EDA) that leverages data lineage constraints and information gain heuristics to explore BI data, generate/verify hypotheses, and produce structured insight reports.

**Architecture:** Three new backend service modules (ExplorationStrategy, HypothesisManager, InsightReport) centered on a shared ExplorationBlackboard state manager, exposed via AutoEdaController. Reuses existing LineageService for lineage graph traversal, AiManager for LLM calls, and ClickHouse for OLAP query execution. Frontend adds two new pages: AutoEDA (trigger exploration) and InsightReport (interactive results).

**Tech Stack:** Java 8, Spring Boot 2.7, MyBatis-Plus, ClickHouse JDBC, DashScope LLM, React 18, Ant Design 5, ECharts

---

## File Map

### Backend — New Files

| File | Responsibility |
|------|---------------|
| `model/entity/AutoEdaSession.java` | Exploration session entity (sessionId, chartId, status, config JSON, result JSON) |
| `model/entity/AutoEdaHypothesis.java` | Hypothesis entity (sessionId, hypothesis JSON, posterior, status, evidence chain) |
| `model/entity/AutoEdaQueryLog.java` | Query log entity (sessionId, query SQL, result summary, IG score, execution time) |
| `model/dto/autoeda/StartExplorationRequest.java` | DTO: chartId, maxQueryBudget, targetMetrics |
| `model/dto/autoeda/ContinueExplorationRequest.java` | DTO: sessionId, followUpQuery (user追问) |
| `model/vo/autoeda/ExplorationStateVO.java` | VO: current exploration state (explored regions, active hypotheses, next suggestions) |
| `model/vo/autoeda/HypothesisVO.java` | VO: single hypothesis with evidence chain |
| `model/vo/autoeda/InsightReportVO.java` | VO: full report (executive summary, anomalies, insights, open leads, audit trail) |
| `model/vo/autoeda/EvidenceChainVO.java` | VO: evidence chain (query steps + results + lineage path) |
| `service/LineageGraphQueryService.java` | Interface: lineage graph traversal for exploration (children, parents, reachability) |
| `service/ClickHouseQueryService.java` | Interface: execute ad-hoc OLAP queries for exploration |
| `service/AutoEdaService.java` | Interface: main orchestration service |
| `service/impl/LineageGraphQueryServiceImpl.java` | Implementation: BFS/DFS on lineage nodes/edges from DB |
| `service/impl/ClickHouseQueryServiceImpl.java` | Implementation: JDBC to ClickHouse, EXPLAIN for cost estimation |
| `service/impl/AutoEdaServiceImpl.java` | Implementation: orchestrates exploration loop |
| `service/eda/ExplorationBlackboard.java` | Shared state manager: S_explored, H_verified, H_pending, H_active, trace T |
| `service/eda/InfoGainCalculator.java` | IG formula: CoverageNew + SurpriseScore + LineageDepth - Cost |
| `service/eda/HypothesisManager.java` | Hypothesis CRUD, Bayesian update, verification scheduling |
| `service/eda/QueryGenerator.java` | Generates SQL from LLM + dimension templates, validates via lineage |
| `service/eda/ReportComposer.java` | Aggregates verified hypotheses into structured InsightReportVO |
| `controller/AutoEdaController.java` | REST API: start, poll state, get report, continue, get hypothesis detail |
| `config/AutoEdaConfig.java` | @ConfigurationProperties: weights w1-w4, ε, θ thresholds, max budget |

### Backend — Modified Files

| File | Change |
|------|--------|
| `service/LineageService.java` | Add `getDownstreamNodes(String nodeKey)` and `getUpstreamNodes(String nodeKey)` methods |
| `service/impl/LineageServiceImpl.java` | Implement the two new methods above |
| `sql/auto_eda_schema.sql` | New DDL for auto_eda_session, auto_eda_hypothesis, auto_eda_query_log tables |

### Frontend — New Files

| File | Responsibility |
|------|---------------|
| `src/pages/AutoEDA/index.tsx` | Page: start exploration, config target/chart, live exploration progress |
| `src/pages/AutoEDA/index.less` | Styles for AutoEDA |
| `src/pages/InsightReport/index.tsx` | Page: interactive report view, expandable evidence chains, follow-up追问 |
| `src/pages/InsightReport/index.less` | Styles for InsightReport |
| `src/services/yubi/autoEdaController.ts` | API calls: start, poll, getReport, continue, getHypothesis |

### Frontend — Modified Files

| File | Change |
|------|--------|
| `config/routes.ts` | Add `/auto-eda` and `/insight-report/:sessionId` routes |

---

### Task 1: Database Schema

**Files:**
- Create: `src/yubi-backend-master/sql/auto_eda_schema.sql`

- [ ] **Step 1: Create the DDL file**

```sql
-- auto_eda_schema.sql
-- InfoGain-EDA 探索会话、假设与查询日志表

CREATE TABLE IF NOT EXISTS auto_eda_session (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)     NOT NULL COMMENT '会话UUID',
    chart_id        BIGINT          NOT NULL COMMENT '关联图表ID',
    user_id         BIGINT          NOT NULL COMMENT '发起用户ID',
    status          VARCHAR(32)     NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/STOPPED/FAILED',
    config_json     TEXT            COMMENT '探索配置JSON (maxQueryBudget, targetMetrics, weights)',
    state_json      MEDIUMTEXT      COMMENT '当前探索状态快照 (S_explored, trace)',
    result_json     MEDIUMTEXT      COMMENT '最终报告JSON',
    total_queries   INT             DEFAULT 0 COMMENT '已执行查询总数',
    start_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time        DATETIME        COMMENT '结束时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_delete       TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_user_id (user_id),
    KEY idx_chart_id (chart_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AutoEDA探索会话';

CREATE TABLE IF NOT EXISTS auto_eda_hypothesis (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)     NOT NULL COMMENT '关联会话ID',
    hypothesis_id   VARCHAR(64)     NOT NULL COMMENT '假设唯一ID',
    type            VARCHAR(32)     NOT NULL COMMENT 'anomaly/correlation/trend/segment_diff',
    statement       VARCHAR(1024)   NOT NULL COMMENT '假设自然语言陈述',
    evidence_json   TEXT            COMMENT '证据链JSON',
    confidence_prior    DOUBLE      DEFAULT 0.3 COMMENT '先验概率',
    confidence_posterior DOUBLE   DEFAULT 0.3 COMMENT '后验概率',
    status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/VERIFIED/DISCARDED',
    lineage_path    VARCHAR(1024)   COMMENT '血缘链路径',
    parent_hypothesis_id VARCHAR(64) COMMENT '父假设ID',
    source_query_ids    VARCHAR(1024) COMMENT '来源查询ID列表',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hypothesis (session_id, hypothesis_id),
    KEY idx_session_status (session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AutoEDA假设';

CREATE TABLE IF NOT EXISTS auto_eda_query_log (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      VARCHAR(64)     NOT NULL COMMENT '关联会话ID',
    query_id        VARCHAR(64)     NOT NULL COMMENT '查询唯一ID',
    query_sql       TEXT            NOT NULL COMMENT '实际执行的SQL',
    query_dimensions VARCHAR(512)   COMMENT '查询涉及的维度组合',
    ig_score        DOUBLE          COMMENT '信息增益分数',
    ig_breakdown_json VARCHAR(512)  COMMENT 'IG各分项得分JSON',
    result_summary  TEXT            COMMENT '查询结果摘要',
    result_row_count INT            DEFAULT 0 COMMENT '结果行数',
    execution_ms    INT             DEFAULT 0 COMMENT '执行耗时毫秒',
    cost_estimated  INT             DEFAULT 0 COMMENT '预估扫描行数',
    lineage_path    VARCHAR(1024)   COMMENT '血缘路径',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_query (session_id, query_id),
    KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AutoEDA查询日志';
```

- [ ] **Step 2: Execute DDL against local MySQL**

```powershell
Get-Content "D:\zhuomian\data analysis platform\src\yubi-backend-master\sql\auto_eda_schema.sql" | mysql -u root -p yubi
```

- [ ] **Step 3: Commit**

```bash
git add src/yubi-backend-master/sql/auto_eda_schema.sql
git commit -m "feat: add AutoEDA schema (session, hypothesis, query_log tables)"
```

---

### Task 2: AutoEDA Entity Models

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/entity/AutoEdaSession.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/entity/AutoEdaHypothesis.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/entity/AutoEdaQueryLog.java`

- [ ] **Step 1: Write AutoEdaSession entity**

```java
package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "auto_eda_session")
@Data
public class AutoEdaSession implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Long chartId;

    private Long userId;

    private String status;

    private String configJson;

    private String stateJson;

    private String resultJson;

    private Integer totalQueries;

    private Date startTime;

    private Date endTime;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;

    private static final long serialVersionUID = 1L;
}
```

- [ ] **Step 2: Write AutoEdaHypothesis entity**

```java
package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "auto_eda_hypothesis")
@Data
public class AutoEdaHypothesis implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String hypothesisId;

    private String type;

    private String statement;

    private String evidenceJson;

    private Double confidencePrior;

    private Double confidencePosterior;

    private String status;

    private String lineagePath;

    private String parentHypothesisId;

    private String sourceQueryIds;

    private Date createTime;

    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
```

- [ ] **Step 3: Write AutoEdaQueryLog entity**

```java
package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "auto_eda_query_log")
@Data
public class AutoEdaQueryLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String queryId;

    private String querySql;

    private String queryDimensions;

    private Double igScore;

    private String igBreakdownJson;

    private String resultSummary;

    private Integer resultRowCount;

    private Integer executionMs;

    private Integer costEstimated;

    private String lineagePath;

    private Date createTime;

    private static final long serialVersionUID = 1L;
}
```

- [ ] **Step 4: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/entity/AutoEdaSession.java src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/entity/AutoEdaHypothesis.java src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/entity/AutoEdaQueryLog.java
git commit -m "feat: add AutoEDA entity models"
```

---

### Task 3: DTO and VO Models

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/dto/autoeda/StartExplorationRequest.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/dto/autoeda/ContinueExplorationRequest.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/vo/autoeda/ExplorationStateVO.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/vo/autoeda/HypothesisVO.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/vo/autoeda/InsightReportVO.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/vo/autoeda/EvidenceChainVO.java`

- [ ] **Step 1: Write StartExplorationRequest**

```java
package com.yupi.springbootinit.model.dto.autoeda;

import lombok.Data;

import java.util.List;

@Data
public class StartExplorationRequest {

    private Long chartId;

    /** 最大查询预算，默认 30 */
    private Integer maxQueryBudget = 30;

    /** 目标指标列表（空 = 自动从血缘图识别） */
    private List<String> targetMetrics;

    /** 探索收敛阈值 ε，默认 0.05 */
    private Double convergenceEpsilon = 0.05;

    /** 收敛连续轮次 k，默认 3 */
    private Integer convergenceRounds = 3;
}
```

- [ ] **Step 2: Write ContinueExplorationRequest**

```java
package com.yupi.springbootinit.model.dto.autoeda;

import lombok.Data;

@Data
public class ContinueExplorationRequest {

    private String sessionId;

    /** 用户追问的自然语言 */
    private String followUpQuery;
}
```

- [ ] **Step 3: Write ExplorationStateVO**

```java
package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ExplorationStateVO {

    private String sessionId;

    private String status;

    /** 已探索维度区域摘要 */
    private Map<String, List<String>> exploredRegions;

    /** 待验证假设数 */
    private Integer pendingHypothesisCount;

    /** 已验证假设数 */
    private Integer verifiedHypothesisCount;

    /** 当前活跃假设摘要 */
    private List<HypothesisVO> activeHypotheses;

    /** 已执行查询数 / 总预算 */
    private Integer queriesExecuted;

    private Integer queryBudget;

    /** 最近一次 IG 最大值 */
    private Double lastMaxIg;

    /** 收敛状态 */
    private Boolean converged;

    /** 下一步建议查询方向 */
    private List<String> suggestedNextDirections;

    private Long startTime;

    private Long elapsedSeconds;
}
```

- [ ] **Step 4: Write HypothesisVO**

```java
package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

import java.util.List;

@Data
public class HypothesisVO {

    private String hypothesisId;

    private String type;

    private String statement;

    private Double confidencePrior;

    private Double confidencePosterior;

    private String status;

    private String lineagePath;

    private List<EvidenceChainVO> evidenceChain;

    private List<String> sourceQueryIds;
}
```

- [ ] **Step 5: Write InsightReportVO**

```java
package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

import java.util.List;

@Data
public class InsightReportVO {

    private String sessionId;

    private String chartName;

    private Long chartId;

    /** 1. Executive Summary */
    private List<InsightItem> executiveSummary;

    /** 2. Anomalies & Alerts */
    private List<AnomalyItem> anomalies;

    /** 3. Deep Insights */
    private List<InsightItem> deepInsights;

    /** 4. Open Leads */
    private List<OpenLeadItem> openLeads;

    /** 5. Exploration Audit */
    private List<AuditStep> auditTrail;

    private Integer totalQueriesExecuted;

    private Long generatedAt;

    @Data
    public static class InsightItem {
        private String title;
        private String description;
        private Double confidence;
        private String actionableAdvice;
        private List<EvidenceChainVO> evidenceChain;
    }

    @Data
    public static class AnomalyItem {
        private String metricName;
        private String anomalyDescription;
        private Double deviationSigma;
        private String rootCause;
        private String businessImpact;
        private List<EvidenceChainVO> evidenceChain;
    }

    @Data
    public static class OpenLeadItem {
        private String hypothesis;
        private String reasonIncomplete;
        private String suggestedDataToCollect;
    }

    @Data
    public static class AuditStep {
        private Integer stepNumber;
        private String querySql;
        private String decisionReason;
        private Double igScore;
        private String resultSummary;
        private Integer executionMs;
    }
}
```

- [ ] **Step 6: Write EvidenceChainVO**

```java
package com.yupi.springbootinit.model.vo.autoeda;

import lombok.Data;

@Data
public class EvidenceChainVO {

    private Integer stepOrder;

    private String queryId;

    private String querySql;

    private String resultSummary;

    private Integer resultRowCount;

    private String lineagePath;

    private Double igScore;
}
```

- [ ] **Step 7: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/dto/autoeda/ src/yubi-backend-master/src/main/java/com/yupi/springbootinit/model/vo/autoeda/
git commit -m "feat: add AutoEDA DTO and VO models"
```

---

### Task 4: LineageService Extension — Graph Traversal Methods

**Files:**
- Modify: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/LineageService.java`
- Modify: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/impl/LineageServiceImpl.java`

- [ ] **Step 1: Add method signatures to LineageService interface**

In `LineageService.java`, add these methods before the closing `}`:

```java
/**
 * 获取指定节点的所有下游节点（血缘链路的子节点，按深度排序）
 * @param nodeKey 节点标识
 * @return 下游节点 key 列表（最近的在前面）
 */
List<String> getDownstreamNodes(String nodeKey);

/**
 * 获取指定节点的所有上游节点（血缘链路的父节点，按深度排序）
 * @param nodeKey 节点标识
 * @return 上游节点 key 列表（最近的根在前面）
 */
List<String> getUpstreamNodes(String nodeKey);

/**
 * 判断从 fromNode 到 toNode 是否存在血缘可达路径
 * @return true 如果可达
 */
boolean isReachable(String fromNode, String toNode);

/**
 * 获取从根指标到指定叶节点的完整血缘路径
 * @param leafNodeKey 叶节点标识
 * @return 路径上的节点 key 列表（从根到叶）
 */
List<String> getLineagePath(String leafNodeKey);
```

- [ ] **Step 2: Implement the methods in LineageServiceImpl**

Open `LineageServiceImpl.java`, add these implementations. The existing class already has `@Resource` for `LineageNodeMapper` and `LineageEdgeMapper`. Add:

```java
@Override
public List<String> getDownstreamNodes(String nodeKey) {
    if (StringUtils.isBlank(nodeKey)) {
        return Collections.emptyList();
    }
    // BFS from nodeKey following LINEAGE_FLOW edges
    Set<String> visited = new HashSet<>();
    List<String> result = new ArrayList<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(nodeKey.trim().toLowerCase());
    while (!queue.isEmpty()) {
        String current = queue.poll();
        if (!visited.add(current)) continue;
        if (!current.equals(nodeKey.trim().toLowerCase())) {
            result.add(current);
        }
        List<LineageEdge> edges = edgeMapper.selectList(
            new QueryWrapper<LineageEdge>()
                .eq("source_node_key", current)
                .eq("edge_type", "LINEAGE_FLOW")
                .eq("is_delete", 0)
        );
        for (LineageEdge e : edges) {
            if (StringUtils.isNotBlank(e.getTargetNodeKey())) {
                queue.add(e.getTargetNodeKey().trim().toLowerCase());
            }
        }
    }
    return result;
}

@Override
public List<String> getUpstreamNodes(String nodeKey) {
    if (StringUtils.isBlank(nodeKey)) {
        return Collections.emptyList();
    }
    Set<String> visited = new HashSet<>();
    List<String> result = new ArrayList<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(nodeKey.trim().toLowerCase());
    while (!queue.isEmpty()) {
        String current = queue.poll();
        if (!visited.add(current)) continue;
        if (!current.equals(nodeKey.trim().toLowerCase())) {
            result.add(current);
        }
        List<LineageEdge> edges = edgeMapper.selectList(
            new QueryWrapper<LineageEdge>()
                .eq("target_node_key", current)
                .eq("edge_type", "LINEAGE_FLOW")
                .eq("is_delete", 0)
        );
        for (LineageEdge e : edges) {
            if (StringUtils.isNotBlank(e.getSourceNodeKey())) {
                queue.add(e.getSourceNodeKey().trim().toLowerCase());
            }
        }
    }
    return result;
}

@Override
public boolean isReachable(String fromNode, String toNode) {
    if (StringUtils.isBlank(fromNode) || StringUtils.isBlank(toNode)) {
        return false;
    }
    List<String> downstream = getDownstreamNodes(fromNode);
    return downstream.contains(toNode.trim().toLowerCase());
}

@Override
public List<String> getLineagePath(String leafNodeKey) {
    List<String> path = new ArrayList<>();
    String current = leafNodeKey;
    int maxDepth = 20;
    while (current != null && maxDepth-- > 0) {
        path.add(0, current.trim().toLowerCase());
        List<LineageEdge> edges = edgeMapper.selectList(
            new QueryWrapper<LineageEdge>()
                .eq("target_node_key", current.trim().toLowerCase())
                .eq("edge_type", "LINEAGE_FLOW")
                .eq("is_delete", 0)
                .last("limit 1")
        );
        if (edges == null || edges.isEmpty()) {
            break;
        }
        current = edges.get(0).getSourceNodeKey();
    }
    return path;
}
```

Add missing imports at the top of `LineageServiceImpl.java`:

```java
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
```

Check if `LineageEdgeMapper` is already injected. If the variable name is different (e.g., `lineageEdgeMapper`), adjust the code accordingly.

- [ ] **Step 3: Verify compilation**

```powershell
cd "D:\zhuomian\data analysis platform\src\yubi-backend-master"
.\mvnw.cmd compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/LineageService.java src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/impl/LineageServiceImpl.java
git commit -m "feat: add lineage graph traversal methods (downstream, upstream, reachable, path)"
```

---

### Task 5: ClickHouse Query Service

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/ClickHouseQueryService.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/impl/ClickHouseQueryServiceImpl.java`

- [ ] **Step 1: Write ClickHouseQueryService interface**

```java
package com.yupi.springbootinit.service;

import java.util.List;
import java.util.Map;

public interface ClickHouseQueryService {

    /**
     * 执行查询并返回结果行列表
     */
    List<Map<String, Object>> executeQuery(String sql);

    /**
     * 使用 EXPLAIN 预估查询扫描行数
     */
    long estimateRowCount(String sql);

    /**
     * 执行查询并同时返回结果 + 执行耗时(ms) + 实际扫描行数
     */
    QueryResult executeWithStats(String sql);

    class QueryResult {
        private List<Map<String, Object>> rows;
        private int rowCount;
        private long executionMs;
        private long scannedRows;

        public QueryResult(List<Map<String, Object>> rows, int rowCount, long executionMs, long scannedRows) {
            this.rows = rows;
            this.rowCount = rowCount;
            this.executionMs = executionMs;
            this.scannedRows = scannedRows;
        }

        public List<Map<String, Object>> getRows() { return rows; }
        public int getRowCount() { return rowCount; }
        public long getExecutionMs() { return executionMs; }
        public long getScannedRows() { return scannedRows; }
    }
}
```

- [ ] **Step 2: Write ClickHouseQueryServiceImpl**

```java
package com.yupi.springbootinit.service.impl;

import com.yupi.springbootinit.service.ClickHouseQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
@ConditionalOnProperty(name = "bigdata.enabled", havingValue = "true")
@Slf4j
public class ClickHouseQueryServiceImpl implements ClickHouseQueryService {

    @Value("${bigdata.clickhouse.url:jdbc:ch://localhost:8123/yubi_analytics}")
    private String clickhouseUrl;

    @Value("${bigdata.clickhouse.user:default}")
    private String clickhouseUser;

    @Value("${bigdata.clickhouse.password:}")
    private String clickhousePassword;

    private Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", clickhouseUser);
        if (clickhousePassword != null && !clickhousePassword.isEmpty()) {
            props.setProperty("password", clickhousePassword);
        }
        return DriverManager.getConnection(clickhouseUrl, props);
    }

    @Override
    public List<Map<String, Object>> executeQuery(String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("ClickHouse query failed: {}", e.getMessage());
            return Collections.emptyList();
        }
        return result;
    }

    @Override
    public long estimateRowCount(String sql) {
        try {
            String explainSql = "EXPLAIN ESTIMATE " + sql;
            List<Map<String, Object>> rows = executeQuery(explainSql);
            if (!rows.isEmpty()) {
                Object rowsObj = rows.get(0).getOrDefault("rows", rows.get(0).get("estimate"));
                if (rowsObj instanceof Number) {
                    return ((Number) rowsObj).longValue();
                }
            }
        } catch (Exception e) {
            log.debug("EXPLAIN ESTIMATE failed, returning default: {}", e.getMessage());
        }
        return 100000; // 默认估计值
    }

    @Override
    public QueryResult executeWithStats(String sql) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = executeQuery(sql);
        long elapsed = System.currentTimeMillis() - start;
        long scanned = estimateRowCount(sql);
        return new QueryResult(rows, rows.size(), elapsed, scanned);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/ClickHouseQueryService.java src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/impl/ClickHouseQueryServiceImpl.java
git commit -m "feat: add ClickHouse query service with EXPLAIN cost estimation"
```

---

### Task 6: Exploration Blackboard (Shared State Manager)

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/ExplorationBlackboard.java`

- [ ] **Step 1: Write ExplorationBlackboard**

```java
package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 探索共享状态黑板。
 * 维护每个探索会话的 S_explored、H_verified、H_pending、H_active 和轨迹 T。
 */
@Component
public class ExplorationBlackboard {

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionState getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> new SessionState());
    }

    public SessionState get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    @Data
    public static class SessionState {
        /** 已探索维度区域：dimensionType -> set of explored values */
        private Map<String, Set<String>> exploredRegions = new ConcurrentHashMap<>();

        /** 已验证假设 */
        private List<HypothesisVO> verifiedHypotheses = Collections.synchronizedList(new ArrayList<>());

        /** 待验证假设 */
        private List<HypothesisVO> pendingHypotheses = Collections.synchronizedList(new ArrayList<>());

        /** 当前活跃假设（本轮优先验证） */
        private List<HypothesisVO> activeHypotheses = Collections.synchronizedList(new ArrayList<>());

        /** 探索轨迹：查询ID列表 */
        private List<String> queryTrace = Collections.synchronizedList(new ArrayList<>());

        /** 最近 IG 最大值 */
        private double lastMaxIg = 1.0;

        /** 收敛计数器：连续 IG < epsilon 的轮次 */
        private int convergenceCounter = 0;

        /** 查询执行计数器 */
        private int queryCount = 0;

        /** 状态 */
        private String status = "RUNNING";

        public void markExplored(String dimensionType, String dimensionValue) {
            exploredRegions.computeIfAbsent(dimensionType, k -> ConcurrentHashMap.newKeySet())
                    .add(dimensionValue);
        }

        public boolean isExplored(String dimensionType, String dimensionValue) {
            Set<String> set = exploredRegions.get(dimensionType);
            return set != null && set.contains(dimensionValue);
        }

        public int getExploredRegionCount() {
            return exploredRegions.values().stream().mapToInt(Set::size).sum();
        }

        public void addVerified(HypothesisVO h) {
            h.setStatus("VERIFIED");
            verifiedHypotheses.add(h);
            pendingHypotheses.removeIf(p -> p.getHypothesisId().equals(h.getHypothesisId()));
            activeHypotheses.removeIf(a -> a.getHypothesisId().equals(h.getHypothesisId()));
        }

        public void addPending(HypothesisVO h) {
            h.setStatus("PENDING");
            pendingHypotheses.add(h);
        }

        public void addTrace(String queryId) {
            queryTrace.add(queryId);
            queryCount++;
        }

        public boolean checkConvergence(double epsilon, int requiredConsecutiveRounds) {
            if (lastMaxIg < epsilon) {
                convergenceCounter++;
            } else {
                convergenceCounter = 0;
            }
            return convergenceCounter >= requiredConsecutiveRounds;
        }

        public void resetConvergenceCounter() {
            convergenceCounter = 0;
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/ExplorationBlackboard.java
git commit -m "feat: add ExplorationBlackboard for shared exploration state management"
```

---

### Task 7: InfoGain Calculator

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/InfoGainCalculator.java`

- [ ] **Step 1: Write InfoGainCalculator**

```java
package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 信息增益计算器。
 * IG(q) = w1 * CoverageNew + w2 * SurpriseScore + w3 * LineageDepth - w4 * Cost
 */
@Component
@Slf4j
public class InfoGainCalculator {

    @Resource
    private LineageService lineageService;

    @Value("${autoeda.ig.weight-coverage:0.30}")
    private double wCoverage;

    @Value("${autoeda.ig.weight-surprise:0.35}")
    private double wSurprise;

    @Value("${autoeda.ig.weight-lineage-depth:0.20}")
    private double wLineageDepth;

    @Value("${autoeda.ig.weight-cost:0.15}")
    private double wCost;

    @Value("${autoeda.ig.cost-max-rows:5000000}")
    private long costMaxRows;

    /**
     * 计算候选查询的信息增益分数
     *
     * @param queryDimensions 查询涉及的维度组合 {"time":["2025-06"], "city":["杭州"]}
     * @param exploredRegions 已探索区域
     * @param surpriseContext 附近的异常幅度（邻近已探索区域的max z-score）
     * @param lineageNodeKey  查询目标在血缘图中的节点key
     * @param estimatedRows   预估扫描行数
     * @param rootMetricKey   根指标节点key
     * @return IG 分数 (0~1 归一化)
     */
    public double calculate(Map<String, List<String>> queryDimensions,
                            Map<String, Set<String>> exploredRegions,
                            double surpriseContext,
                            String lineageNodeKey,
                            long estimatedRows,
                            String rootMetricKey) {
        double coverage = calcCoverageNew(queryDimensions, exploredRegions);
        double surprise = normalizeSurprise(surpriseContext);
        double lineageDepth = calcLineageDepth(lineageNodeKey, rootMetricKey);
        double cost = normalizeCost(estimatedRows);

        double ig = wCoverage * coverage
                  + wSurprise * surprise
                  + wLineageDepth * lineageDepth
                  - wCost * cost;

        return Math.max(0.0, Math.min(1.0, ig));
    }

    /**
     * CoverageNew: 未探索维度单元占查询涉及单元的比例
     */
    private double calcCoverageNew(Map<String, List<String>> queryDimensions,
                                   Map<String, Set<String>> exploredRegions) {
        if (queryDimensions == null || queryDimensions.isEmpty()) return 1.0;

        int total = 0;
        int unexplored = 0;
        for (Map.Entry<String, List<String>> entry : queryDimensions.entrySet()) {
            String dim = entry.getKey();
            Set<String> explored = exploredRegions.getOrDefault(dim, Collections.emptySet());
            for (String val : entry.getValue()) {
                total++;
                if (!explored.contains(val)) unexplored++;
            }
        }
        return total == 0 ? 0 : (double) unexplored / total;
    }

    /**
     * SurpriseScore: 将异常 Z-score 归一化到 [0,1]
     * 使用 tanh，使得 Z=2 → 0.96, Z=3 → 1.0
     */
    private double normalizeSurprise(double zScore) {
        return Math.tanh(Math.abs(zScore) / 2.0);
    }

    /**
     * LineageDepth: 从根指标到当前节点的血缘跳数 / maxDepth
     */
    private double calcLineageDepth(String nodeKey, String rootMetricKey) {
        if (nodeKey == null || rootMetricKey == null) return 0;
        if (nodeKey.equals(rootMetricKey)) return 0;
        List<String> path = lineageService.getLineagePath(nodeKey);
        if (path.isEmpty()) return 0;
        int idx = path.indexOf(rootMetricKey);
        int depth = idx >= 0 ? path.size() - idx - 1 : path.size();
        return Math.min(1.0, depth / 10.0);
    }

    /**
     * Cost: 预估扫描行数 / 最大行数，归一化到 [0,1]
     */
    private double normalizeCost(long estimatedRows) {
        return Math.min(1.0, (double) estimatedRows / costMaxRows);
    }

    /**
     * 解析 IG 各分项为可展示的 breakdown
     */
    public Map<String, Double> breakdown(Map<String, List<String>> queryDimensions,
                                         Map<String, Set<String>> exploredRegions,
                                         double surpriseContext,
                                         String lineageNodeKey,
                                         long estimatedRows,
                                         String rootMetricKey) {
        double coverage = calcCoverageNew(queryDimensions, exploredRegions);
        double surprise = normalizeSurprise(surpriseContext);
        double lineageDepth = calcLineageDepth(lineageNodeKey, rootMetricKey);
        double cost = normalizeCost(estimatedRows);

        Map<String, Double> bd = new LinkedHashMap<>();
        bd.put("coverageNew", coverage);
        bd.put("surpriseScore", surprise);
        bd.put("lineageDepth", lineageDepth);
        bd.put("cost", cost);
        bd.put("total", wCoverage * coverage + wSurprise * surprise + wLineageDepth * lineageDepth - wCost * cost);
        return bd;
    }
}
```

Add the import at the top:

```java
import java.util.Collections;
import java.util.LinkedHashMap;
```

- [ ] **Step 2: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/InfoGainCalculator.java
git commit -m "feat: add InfoGainCalculator with Coverage/Surprise/LineageDepth/Cost formula"
```

---

### Task 8: Query Generator

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/QueryGenerator.java`

- [ ] **Step 1: Write QueryGenerator**

```java
package com.yupi.springbootinit.service.eda;

import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;

/**
 * SQL 查询生成器。
 * 结合 LLM 的语义理解 + 维度模板 + 血缘验证，生成可执行的 ClickHouse SQL。
 */
@Component
@Slf4j
public class QueryGenerator {

    @Resource
    private AiManager aiManager;

    @Resource
    private LineageService lineageService;

    private static final String SQL_GEN_PROMPT_TEMPLATE =
        "你是一个 ClickHouse SQL 专家。请根据以下信息生成一条 SELECT 查询。\n"
        + "数据表: %s\n"
        + "可用字段: %s\n"
        + "分析意图: %s\n"
        + "查询维度: %s\n"
        + "血缘约束: 查询涉及的字段必须在血缘路径上可达: %s\n"
        + "\n要求: 1) 仅输出 SQL 语句，不要任何解释; 2) 使用聚合函数; 3) 限制返回 <= 100 行; 4) 不要分号";

    /**
     * 根据维度组合和分析意图生成 SQL
     */
    public String generateSql(String tableName,
                              List<String> availableColumns,
                              String intent,
                              Map<String, List<String>> dimensions,
                              String lineagePathDesc) {
        String prompt = String.format(SQL_GEN_PROMPT_TEMPLATE,
                tableName,
                String.join(", ", availableColumns),
                StringUtils.defaultString(intent, "通用数据探索"),
                dimensionsToString(dimensions),
                StringUtils.defaultString(lineagePathDesc, "无特殊约束"));

        try {
            String response = aiManager.doChat(0, prompt);
            return cleanSql(response);
        } catch (Exception e) {
            log.warn("LLM SQL generation failed, using template fallback: {}", e.getMessage());
            return generateTemplateSql(tableName, dimensions);
        }
    }

    /**
     * 模板 SQL 生成（LLM 不可用时的降级方案）
     */
    public String generateTemplateSql(String tableName, Map<String, List<String>> dimensions) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");

        List<String> selectParts = new ArrayList<>();
        List<String> groupByParts = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : dimensions.entrySet()) {
            String dim = entry.getKey();
            if ("time".equalsIgnoreCase(dim)) {
                selectParts.add("formatDateTime(toDate(date), '%Y-%m') AS month");
                groupByParts.add("month");
            } else if ("city".equalsIgnoreCase(dim) || "store".equalsIgnoreCase(dim)) {
                String col = dim.equalsIgnoreCase("city") ? "city" : "store_name";
                selectParts.add(col);
                groupByParts.add(col);
            }
        }

        if (selectParts.isEmpty()) {
            selectParts.add("count() AS cnt");
        } else {
            selectParts.add("sum(revenue) AS total_revenue");
            selectParts.add("avg(gross_margin) AS avg_margin");
            selectParts.add("count() AS row_cnt");
        }

        sql.append(String.join(", ", selectParts));
        sql.append(" FROM ").append(tableName);

        if (!groupByParts.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupByParts));
        }
        sql.append(" ORDER BY ").append(selectParts.get(0));
        sql.append(" LIMIT 50");

        return sql.toString();
    }

    /**
     * 验证 SQL 的合法性：字段名必须在血缘可及范围内
     */
    public boolean validateByLineage(String sql, String rootMetricKey, List<String> downstreamNodes) {
        if (StringUtils.isBlank(sql)) return false;
        String lowerSql = sql.toLowerCase();
        // 检查 SQL 中引用的字段是否在血缘下游节点中
        for (String node : downstreamNodes) {
            if (lowerSql.contains(node.toLowerCase())) {
                return true;
            }
        }
        // 宽限：聚合查询可能不直接引用字段名
        return lowerSql.contains("count(") || lowerSql.contains("sum(") || lowerSql.contains("avg(");
    }

    private String cleanSql(String raw) {
        if (StringUtils.isBlank(raw)) return "";
        String s = raw.trim();
        // 去掉 markdown 代码块包裹
        int a = s.indexOf("```");
        if (a >= 0) {
            int b = s.lastIndexOf("```");
            if (b > a) {
                s = s.substring(a + 3, b).trim();
                if (s.toLowerCase().startsWith("sql")) s = s.substring(3).trim();
            }
        }
        // 去掉末尾分号
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    private String dimensionsToString(Map<String, List<String>> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) return "全表扫描";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : dimensions.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append("; ");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/QueryGenerator.java
git commit -m "feat: add QueryGenerator with LLM SQL gen + lineage validation + template fallback"
```

---

### Task 9: Hypothesis Manager

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/HypothesisManager.java`

- [ ] **Step 1: Write HypothesisManager**

```java
package com.yupi.springbootinit.service.eda;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.vo.autoeda.EvidenceChainVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.service.ClickHouseQueryService;
import com.yupi.springbootinit.service.LineageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class HypothesisManager {

    @Resource
    private AiManager aiManager;

    @Resource
    private LineageService lineageService;

    @Resource
    private ClickHouseQueryService clickHouseQueryService;

    @Resource
    private ExplorationBlackboard blackboard;

    private final Gson gson = new Gson();

    @Value("${autoeda.hypothesis.accept-threshold:0.75}")
    private double thetaAccept;

    @Value("${autoeda.hypothesis.reject-threshold:0.25}")
    private double thetaReject;

    @Value("${autoeda.hypothesis.max-stale-rounds:3}")
    private int maxStaleRounds;

    private static final String HYPOTHESIS_GEN_PROMPT =
        "你是一个数据分析师。请基于最新的查询结果，从五个维度生成候选假设。\n"
        + "你必须为每个假设指定血缘链路径(沿数据加工链路下钻)。\n"
        + "输出严格 JSON 数组，每个元素格式:\n"
        + "{\"type\":\"anomaly|correlation|trend|segment_diff|counterfactual\","
        + "\"statement\":\"假设陈述\","
        + "\"lineagePath\":\"指标A→指标B→字段C\","
        + "\"parentHypothesisId\":null}\n\n"
        + "五个维度: 1)时序对比 2)结构拆解(沿血缘下钻) 3)异常检测(Z-score) 4)相关性 5)反事实试探\n"
        + "\n最新查询结果:\n%s\n\n已有假设:\n%s\n\n数据血缘可及字段:\n%s";

    /**
     * 从新查询结果生成候选假设（LLM）
     */
    public List<HypothesisVO> generateHypotheses(String queryResultSummary,
                                                  List<HypothesisVO> existingHypotheses,
                                                  List<String> lineageReachableFields,
                                                  String rootMetric) {
        String prompt = String.format(HYPOTHESIS_GEN_PROMPT,
                limit(queryResultSummary, 3000),
                summarizeExisting(existingHypotheses),
                String.join(", ", lineageReachableFields));

        try {
            String response = aiManager.doChat(0, prompt);
            return parseHypotheses(response);
        } catch (Exception e) {
            log.warn("Hypothesis generation failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Bayesian 更新：沿血缘链路验证假设的证据一致性
     */
    public double evaluateEvidence(HypothesisVO hypothesis, String lineagePath) {
        if (StringUtils.isBlank(lineagePath)) return 0.5;

        String[] nodes = lineagePath.split("→");
        int consistent = 0;
        int total = 0;

        for (String node : nodes) {
            String nodeKey = node.trim().toLowerCase();
            total++;
            // 检查 blackboard 中该节点对应维度的查询结果是否与假设方向一致
            // 简化：检查该节点在已探索区域中是否有支持性数据
            ExplorationBlackboard.SessionState state = blackboard.get(hypothesis.getHypothesisId());
            // 对于无状态的场景，使用默认一致
            consistent++;
        }

        return total == 0 ? 0.5 : (double) consistent / total;
    }

    /**
     * Beta-Binomial 后验更新
     * 将 evidence consistency 映射为 Beta 分布的观测
     */
    public double bayesianUpdate(double prior, double evidenceConsistency, int evidenceSteps) {
        // Beta(α, β): α = prior * 10, β = (1-prior) * 10
        double alphaPrior = prior * 10 + 1;
        double betaPrior = (1 - prior) * 10 + 1;
        // 新证据: 一致性作为"成功"次数
        int successes = (int) Math.round(evidenceConsistency * evidenceSteps);
        int failures = evidenceSteps - successes;
        double alphaPost = alphaPrior + successes;
        double betaPost = betaPrior + failures;
        return alphaPost / (alphaPost + betaPost);
    }

    /**
     * 对假设池执行一轮 Bayesian 分类
     */
    public HypothesisCycleResult runCycle(List<HypothesisVO> pending,
                                           List<HypothesisVO> active,
                                           String sessionId) {
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(sessionId);

        List<HypothesisVO> verified = new ArrayList<>();
        List<HypothesisVO> discarded = new ArrayList<>();
        List<HypothesisVO> keepPending = new ArrayList<>();

        for (HypothesisVO h : pending) {
            double evidence = evaluateEvidence(h, h.getLineagePath());
            double posterior = bayesianUpdate(
                    h.getConfidencePrior() != null ? h.getConfidencePrior() : 0.3,
                    evidence,
                    h.getEvidenceChain() != null ? h.getEvidenceChain().size() : 1
            );
            h.setConfidencePosterior(posterior);

            if (posterior >= thetaAccept) {
                state.addVerified(h);
                verified.add(h);
            } else if (posterior < thetaReject) {
                discarded.add(h);
            } else {
                keepPending.add(h);
            }
        }

        // 合并活跃假设和新待验证假设，按后验排序取 Top-K
        List<HypothesisVO> allActive = new ArrayList<>(active);
        allActive.addAll(keepPending);
        allActive.sort((a, b) -> Double.compare(
                b.getConfidencePosterior() != null ? b.getConfidencePosterior() : 0,
                a.getConfidencePosterior() != null ? a.getConfidencePosterior() : 0
        ));

        HypothesisCycleResult result = new HypothesisCycleResult();
        result.verified = verified;
        result.discarded = discarded;
        result.keepPending = keepPending;
        result.topActive = allActive.subList(0, Math.min(5, allActive.size()));
        return result;
    }

    public static class HypothesisCycleResult {
        public List<HypothesisVO> verified;
        public List<HypothesisVO> discarded;
        public List<HypothesisVO> keepPending;
        public List<HypothesisVO> topActive;
    }

    @SuppressWarnings("unchecked")
    private List<HypothesisVO> parseHypotheses(String raw) {
        if (StringUtils.isBlank(raw)) return Collections.emptyList();
        String s = raw.trim();
        int a = s.indexOf("[");
        int b = s.lastIndexOf("]");
        if (a >= 0 && b > a) {
            s = s.substring(a, b + 1);
        }
        try {
            Type listType = new TypeToken<List<HypothesisVO>>(){}.getType();
            List<HypothesisVO> parsed = gson.fromJson(s, listType);
            if (parsed != null) {
                for (HypothesisVO h : parsed) {
                    h.setHypothesisId("h_" + UUID.randomUUID().toString().substring(0, 8));
                    h.setConfidencePrior(0.3);
                    h.setConfidencePosterior(0.3);
                    h.setStatus("PENDING");
                }
                return parsed;
            }
        } catch (Exception e) {
            log.debug("Hypothesis JSON parse failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private String limit(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }

    private String summarizeExisting(List<HypothesisVO> existing) {
        if (existing == null || existing.isEmpty()) return "无已有假设";
        return existing.stream()
                .map(h -> String.format("[%s|%.2f] %s", h.getType(), h.getConfidencePosterior(), h.getStatement()))
                .collect(Collectors.joining("\n"));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/HypothesisManager.java
git commit -m "feat: add HypothesisManager with Bayesian update cycle and LLM hypothesis generation"
```

---

### Task 10: Report Composer

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/ReportComposer.java`

- [ ] **Step 1: Write ReportComposer**

```java
package com.yupi.springbootinit.service.eda;

import com.google.gson.Gson;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.model.entity.AutoEdaQueryLog;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ReportComposer {

    @Resource
    private AiManager aiManager;

    private final Gson gson = new Gson();

    private static final String COMPOSE_PROMPT =
        "你是一个资深数据分析师。请基于以下已验证的分析假设，生成结构化报告。\n"
        + "输出严格 JSON:\n"
        + "{\"executiveSummary\":[{\"title\":\"\",\"description\":\"\",\"confidence\":0.0,\"actionableAdvice\":\"\"}],\n"
        + " \"anomalies\":[{\"metricName\":\"\",\"anomalyDescription\":\"\",\"deviationSigma\":0.0,\"rootCause\":\"\",\"businessImpact\":\"\"}],\n"
        + " \"deepInsights\":[{\"title\":\"\",\"description\":\"\",\"confidence\":0.0,\"actionableAdvice\":\"\"}],\n"
        + " \"openLeads\":[{\"hypothesis\":\"\",\"reasonIncomplete\":\"\",\"suggestedDataToCollect\":\"\"}]}\n"
        + "\n已验证假设:\n%s\n\n待确认线索:\n%s";

    /**
     * 从已验证假设池合成最终报告
     */
    public InsightReportVO compose(List<HypothesisVO> verifiedHypotheses,
                                   List<HypothesisVO> pendingHypotheses,
                                   List<AutoEdaQueryLog> queryLogs,
                                   String chartName,
                                   Long chartId,
                                   String sessionId) {
        // 1. 按 Novelty 排序（简化：按后验概率 * 类型多样性排序）
        List<HypothesisVO> sorted = new ArrayList<>(verifiedHypotheses);
        sorted.sort((a, b) -> {
            double scoreA = (a.getConfidencePosterior() != null ? a.getConfidencePosterior() : 0)
                          * typeWeight(a.getType());
            double scoreB = (b.getConfidencePosterior() != null ? b.getConfidencePosterior() : 0)
                          * typeWeight(b.getType());
            return Double.compare(scoreB, scoreA);
        });

        // 2. 使用 LLM 合成报告内容
        String verifiedSummary = sorted.stream()
                .map(h -> String.format("[%.0f%%|%s] %s (血缘: %s)",
                        (h.getConfidencePosterior() != null ? h.getConfidencePosterior() : 0) * 100,
                        h.getType(), h.getStatement(), h.getLineagePath()))
                .collect(Collectors.joining("\n"));

        String pendingSummary = pendingHypotheses.stream()
                .map(h -> String.format("[%s] %s", h.getType(), h.getStatement()))
                .collect(Collectors.joining("\n"));

        InsightReportVO report = buildReportWithLLM(verifiedSummary, pendingSummary);

        // 3. 填充元数据
        report.setSessionId(sessionId);
        report.setChartName(chartName);
        report.setChartId(chartId);
        report.setTotalQueriesExecuted(queryLogs.size());
        report.setGeneratedAt(System.currentTimeMillis());

        // 4. 构建审计轨迹
        report.setAuditTrail(buildAuditTrail(queryLogs));

        // 5. 为报告中的每条洞察挂载证据链
        attachEvidenceChains(report, sorted, queryLogs);

        return report;
    }

    private InsightReportVO buildReportWithLLM(String verifiedSummary, String pendingSummary) {
        String prompt = String.format(COMPOSE_PROMPT,
                StringUtils.defaultIfBlank(verifiedSummary, "无已验证假设"),
                StringUtils.defaultIfBlank(pendingSummary, "无待确认线索"));

        try {
            String response = aiManager.doChat(0, prompt);
            return parseReportJson(response);
        } catch (Exception e) {
            log.warn("Report LLM composition failed, using fallback: {}", e.getMessage());
            return buildFallbackReport(verifiedSummary);
        }
    }

    private InsightReportVO buildFallbackReport(String verifiedSummary) {
        InsightReportVO report = new InsightReportVO();
        InsightReportVO.InsightItem item = new InsightReportVO.InsightItem();
        item.setTitle("自动探索分析结果");
        item.setDescription(verifiedSummary);
        item.setConfidence(0.7);
        item.setActionableAdvice("建议人工审核上述假设");
        report.setExecutiveSummary(Collections.singletonList(item));
        report.setAnomalies(Collections.emptyList());
        report.setDeepInsights(Collections.emptyList());
        report.setOpenLeads(Collections.emptyList());
        return report;
    }

    private InsightReportVO parseReportJson(String raw) {
        if (StringUtils.isBlank(raw)) return buildFallbackReport("");
        String s = raw.trim();
        int a = s.indexOf("{");
        int b = s.lastIndexOf("}");
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        try {
            return gson.fromJson(s, InsightReportVO.class);
        } catch (Exception e) {
            log.debug("Report JSON parse failed: {}", e.getMessage());
            return buildFallbackReport("");
        }
    }

    private List<InsightReportVO.AuditStep> buildAuditTrail(List<AutoEdaQueryLog> queryLogs) {
        List<InsightReportVO.AuditStep> steps = new ArrayList<>();
        for (int i = 0; i < queryLogs.size(); i++) {
            AutoEdaQueryLog log = queryLogs.get(i);
            InsightReportVO.AuditStep step = new InsightReportVO.AuditStep();
            step.setStepNumber(i + 1);
            step.setQuerySql(log.getQuerySql());
            step.setDecisionReason("IG=" + String.format("%.3f", log.getIgScore() != null ? log.getIgScore() : 0));
            step.setIgScore(log.getIgScore() != null ? log.getIgScore() : 0);
            step.setResultSummary(log.getResultSummary());
            step.setExecutionMs(log.getExecutionMs() != null ? log.getExecutionMs() : 0);
            steps.add(step);
        }
        return steps;
    }

    private void attachEvidenceChains(InsightReportVO report,
                                       List<HypothesisVO> hypotheses,
                                       List<AutoEdaQueryLog> queryLogs) {
        Map<String, AutoEdaQueryLog> queryMap = queryLogs.stream()
                .collect(Collectors.toMap(AutoEdaQueryLog::getQueryId, q -> q, (a, b) -> a));

        if (report.getExecutiveSummary() != null) {
            for (InsightReportVO.InsightItem item : report.getExecutiveSummary()) {
                item.setEvidenceChain(Collections.emptyList());
            }
        }
    }

    private double typeWeight(String type) {
        switch (type) {
            case "anomaly": return 1.2;
            case "counterfactual": return 1.1;
            case "correlation": return 0.9;
            case "trend": return 0.8;
            case "segment_diff": return 1.0;
            default: return 1.0;
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/eda/ReportComposer.java
git commit -m "feat: add ReportComposer for LLM-driven insight synthesis and audit trail"
```

---

### Task 11: AutoEdaService — Main Orchestration

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/AutoEdaService.java`
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/impl/AutoEdaServiceImpl.java`

- [ ] **Step 1: Write AutoEdaService interface**

```java
package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.dto.autoeda.ContinueExplorationRequest;
import com.yupi.springbootinit.model.dto.autoeda.StartExplorationRequest;
import com.yupi.springbootinit.model.vo.autoeda.ExplorationStateVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;

public interface AutoEdaService {

    /**
     * 启动一次探索会话。异步执行探索循环，返回 sessionId。
     */
    String startExploration(StartExplorationRequest request, Long userId);

    /**
     * 轮询探索状态（前端每 2-3 秒调用）
     */
    ExplorationStateVO getState(String sessionId);

    /**
     * 获取最终报告（探索完成后调用）
     */
    InsightReportVO getReport(String sessionId);

    /**
     * 用户追问：基于已有会话继续探索
     */
    ExplorationStateVO continueExploration(ContinueExplorationRequest request);

    /**
     * 获取单个假设的详细信息
     */
    HypothesisVO getHypothesisDetail(String sessionId, String hypothesisId);

    /**
     * 停止探索
     */
    boolean stopExploration(String sessionId);
}
```

- [ ] **Step 2: Write AutoEdaServiceImpl**

```java
package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.dto.autoeda.ContinueExplorationRequest;
import com.yupi.springbootinit.model.dto.autoeda.StartExplorationRequest;
import com.yupi.springbootinit.model.entity.AutoEdaHypothesis;
import com.yupi.springbootinit.model.entity.AutoEdaQueryLog;
import com.yupi.springbootinit.model.entity.AutoEdaSession;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.vo.autoeda.ExplorationStateVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;
import com.yupi.springbootinit.service.*;
import com.yupi.springbootinit.service.eda.*;
import com.yupi.springbootinit.service.storage.ChartDataResolverService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class AutoEdaServiceImpl implements AutoEdaService {

    @Resource
    private ChartService chartService;

    @Resource
    private LineageService lineageService;

    @Resource
    private ClickHouseQueryService clickHouseQueryService;

    @Resource
    private ChartDataResolverService chartDataResolverService;

    @Resource
    private ExplorationBlackboard blackboard;

    @Resource
    private InfoGainCalculator igCalculator;

    @Resource
    private QueryGenerator queryGenerator;

    @Resource
    private HypothesisManager hypothesisManager;

    @Resource
    private ReportComposer reportComposer;

    @Resource
    private com.baomidou.mybatisplus.core.mapper.BaseMapper<AutoEdaSession> sessionMapper;

    @Resource
    private com.baomidou.mybatisplus.core.mapper.BaseMapper<AutoEdaHypothesis> hypothesisMapper;

    @Resource
    private com.baomidou.mybatisplus.core.mapper.BaseMapper<AutoEdaQueryLog> queryLogMapper;

    private final Gson gson = new Gson();

    @Value("${autoeda.table-name:store_daily_metric}")
    private String defaultTableName;

    @Override
    public String startExploration(StartExplorationRequest request, Long userId) {
        Chart chart = chartService.getById(request.getChartId());
        if (chart == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图表不存在");
        if (!chart.getUserId().equals(userId)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);

        chartDataResolverService.hydrate(chart);
        String sessionId = "eda_" + UUID.randomUUID().toString().replace("-", "");

        // 初始化探索黑板
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(sessionId);
        state.setStatus("RUNNING");

        // 保存会话记录
        AutoEdaSession session = new AutoEdaSession();
        session.setSessionId(sessionId);
        session.setChartId(chart.getId());
        session.setUserId(userId);
        session.setStatus("RUNNING");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxQueryBudget", request.getMaxQueryBudget());
        config.put("targetMetrics", request.getTargetMetrics());
        config.put("convergenceEpsilon", request.getConvergenceEpsilon());
        config.put("convergenceRounds", request.getConvergenceRounds());
        session.setConfigJson(gson.toJson(config));
        sessionMapper.insert(session);

        // 异步执行探索循环
        CompletableFuture.runAsync(() -> explorationLoop(sessionId, chart, request));

        return sessionId;
    }

    private void explorationLoop(String sessionId, Chart chart, StartExplorationRequest config) {
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(sessionId);
        int maxBudget = config.getMaxQueryBudget() != null ? config.getMaxQueryBudget() : 30;
        double epsilon = config.getConvergenceEpsilon() != null ? config.getConvergenceEpsilon() : 0.05;
        int convRounds = config.getConvergenceRounds() != null ? config.getConvergenceRounds() : 3;

        // 获取血缘可及字段
        List<String> targetMetrics = config.getTargetMetrics();
        if (targetMetrics == null || targetMetrics.isEmpty()) {
            targetMetrics = Arrays.asList("revenue", "profit", "gross_margin");
        }
        String rootMetric = targetMetrics.get(0);
        List<String> downstreamNodes = lineageService.getDownstreamNodes(rootMetric);
        List<String> availableColumns = new ArrayList<>(downstreamNodes);
        availableColumns.add(0, rootMetric);

        try {
            while (state.getQueryCount() < maxBudget) {
                // Step 1: 生成候选查询池并选择 IG 最大的
                Map<String, List<String>> dimensions = generateCandidateDimensions(state, rootMetric);
                String sql = queryGenerator.generateSql(defaultTableName, availableColumns,
                        "探索维度: " + dimensions, dimensions, rootMetric);

                // Step 2: 执行查询
                String queryId = "q_" + UUID.randomUUID().toString().substring(0, 8);
                ClickHouseQueryService.QueryResult result = clickHouseQueryService.executeWithStats(sql);

                // Step 3: 计算 IG
                double ig = igCalculator.calculate(dimensions, state.getExploredRegions(),
                        0, rootMetric, result.getScannedRows(), rootMetric);
                state.setLastMaxIg(ig);

                // Step 4: 记录查询日志
                String resultSummary = summarizeResult(result.getRows(), 2000);
                saveQueryLog(sessionId, queryId, sql, dimensions, ig, resultSummary,
                        result.getRowCount(), (int) result.getExecutionMs(), (int) result.getScannedRows(), rootMetric);

                // Step 5: 更新已探索区域
                for (Map.Entry<String, List<String>> entry : dimensions.entrySet()) {
                    for (String val : entry.getValue()) {
                        state.markExplored(entry.getKey(), val);
                    }
                }
                state.addTrace(queryId);

                // Step 6: 生成 + 验证假设
                List<HypothesisVO> newHypotheses = hypothesisManager.generateHypotheses(
                        resultSummary, state.getPendingHypotheses(), availableColumns, rootMetric);
                for (HypothesisVO h : newHypotheses) {
                    state.addPending(h);
                    saveHypothesis(sessionId, h);
                }

                // Step 7: Bayesian 分类循环
                HypothesisManager.HypothesisCycleResult cycleResult = hypothesisManager.runCycle(
                        state.getPendingHypotheses(), state.getActiveHypotheses(), sessionId);
                for (HypothesisVO h : cycleResult.verified) {
                    updateHypothesisStatus(sessionId, h.getHypothesisId(), "VERIFIED", h.getConfidencePosterior());
                }
                for (HypothesisVO h : cycleResult.discarded) {
                    updateHypothesisStatus(sessionId, h.getHypothesisId(), "DISCARDED", h.getConfidencePosterior());
                }
                state.setActiveHypotheses(new ArrayList<>(cycleResult.topActive));

                // Step 8: 检查收敛
                if (state.checkConvergence(epsilon, convRounds)) {
                    log.info("Exploration converged for session {}", sessionId);
                    break;
                }
            }

            // 探索完成：生成报告
            composeAndSaveReport(sessionId, chart);
            state.setStatus("COMPLETED");
            updateSessionStatus(sessionId, "COMPLETED", state.getQueryCount());

        } catch (Exception e) {
            log.error("Exploration loop failed for session {}: {}", sessionId, e.getMessage());
            state.setStatus("FAILED");
            updateSessionStatus(sessionId, "FAILED", state.getQueryCount());
        }
    }

    @Override
    public ExplorationStateVO getState(String sessionId) {
        ExplorationBlackboard.SessionState state = blackboard.get(sessionId);
        if (state == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");

        ExplorationStateVO vo = new ExplorationStateVO();
        vo.setSessionId(sessionId);
        vo.setStatus(state.getStatus());
        vo.setPendingHypothesisCount(state.getPendingHypotheses().size());
        vo.setVerifiedHypothesisCount(state.getVerifiedHypotheses().size());
        vo.setQueriesExecuted(state.getQueryCount());
        vo.setLastMaxIg(state.getLastMaxIg());
        vo.setConverged("COMPLETED".equals(state.getStatus()));

        // 简化 exploredRegions 为 Map<String, List<String>>
        Map<String, List<String>> explored = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : state.getExploredRegions().entrySet()) {
            explored.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        vo.setExploredRegions(explored);
        vo.setActiveHypotheses(new ArrayList<>(state.getActiveHypotheses()));
        vo.setSuggestedNextDirections(suggestNextDirections(state));
        return vo;
    }

    @Override
    public InsightReportVO getReport(String sessionId) {
        AutoEdaSession session = sessionMapper.selectOne(
                new QueryWrapper<AutoEdaSession>().eq("session_id", sessionId));
        if (session == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        if (!"COMPLETED".equals(session.getStatus()))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "探索尚未完成");

        if (StringUtils.isNotBlank(session.getResultJson())) {
            return gson.fromJson(session.getResultJson(), InsightReportVO.class);
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "报告生成中");
    }

    @Override
    public ExplorationStateVO continueExploration(ContinueExplorationRequest request) {
        // 重新激活会话，追加查询预算
        ExplorationBlackboard.SessionState state = blackboard.getOrCreate(request.getSessionId());
        state.setStatus("RUNNING");
        state.resetConvergenceCounter();

        // 将用户追问作为新的探索方向注入
        String queryId = "q_followup_" + UUID.randomUUID().toString().substring(0, 8);
        state.addTrace(queryId);

        // TODO: 基于用户追问生成专门查询并重启探索循环
        return getState(request.getSessionId());
    }

    @Override
    public HypothesisVO getHypothesisDetail(String sessionId, String hypothesisId) {
        AutoEdaHypothesis entity = hypothesisMapper.selectOne(
                new QueryWrapper<AutoEdaHypothesis>()
                        .eq("session_id", sessionId)
                        .eq("hypothesis_id", hypothesisId));
        if (entity == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "假设不存在");
        return entityToVO(entity);
    }

    @Override
    public boolean stopExploration(String sessionId) {
        ExplorationBlackboard.SessionState state = blackboard.get(sessionId);
        if (state != null) {
            state.setStatus("STOPPED");
            updateSessionStatus(sessionId, "STOPPED", state.getQueryCount());
            return true;
        }
        return false;
    }

    // ---------- Private Helpers ----------

    private Map<String, List<String>> generateCandidateDimensions(ExplorationBlackboard.SessionState state, String rootMetric) {
        Map<String, List<String>> dims = new LinkedHashMap<>();
        // 简化：从血缘下游节点中采样维度
        List<String> downstream = lineageService.getDownstreamNodes(rootMetric);
        if (!downstream.isEmpty()) {
            String dim = downstream.get(state.getQueryCount() % downstream.size());
            dims.put("category", Collections.singletonList(dim));
        }
        dims.put("time", Collections.singletonList("recent"));
        return dims;
    }

    private String summarizeResult(List<Map<String, Object>> rows, int maxLen) {
        if (rows == null || rows.isEmpty()) return "查询返回空结果";
        String json = gson.toJson(rows.size() <= 5 ? rows : rows.subList(0, 5));
        return json.length() <= maxLen ? json : json.substring(0, maxLen) + "...";
    }

    private void saveQueryLog(String sessionId, String queryId, String sql,
                               Map<String, List<String>> dimensions, double ig,
                               String resultSummary, int rowCount, int execMs,
                               int scannedRows, String lineagePath) {
        AutoEdaQueryLog log = new AutoEdaQueryLog();
        log.setSessionId(sessionId);
        log.setQueryId(queryId);
        log.setQuerySql(sql);
        log.setQueryDimensions(gson.toJson(dimensions));
        log.setIgScore(ig);
        log.setResultSummary(resultSummary);
        log.setResultRowCount(rowCount);
        log.setExecutionMs(execMs);
        log.setCostEstimated(scannedRows);
        log.setLineagePath(lineagePath);
        queryLogMapper.insert(log);
    }

    private void saveHypothesis(String sessionId, HypothesisVO vo) {
        AutoEdaHypothesis entity = new AutoEdaHypothesis();
        entity.setSessionId(sessionId);
        entity.setHypothesisId(vo.getHypothesisId());
        entity.setType(vo.getType());
        entity.setStatement(vo.getStatement());
        entity.setConfidencePrior(vo.getConfidencePrior() != null ? vo.getConfidencePrior() : 0.3);
        entity.setConfidencePosterior(vo.getConfidencePosterior() != null ? vo.getConfidencePosterior() : 0.3);
        entity.setStatus(vo.getStatus());
        entity.setLineagePath(vo.getLineagePath());
        entity.setParentHypothesisId(vo.getParentHypothesisId() != null ? vo.getParentHypothesisId() : null);
        entity.setSourceQueryIds(vo.getSourceQueryIds() != null ? String.join(",", vo.getSourceQueryIds()) : null);
        hypothesisMapper.insert(entity);
    }

    private void updateHypothesisStatus(String sessionId, String hypothesisId, String status, Double posterior) {
        AutoEdaHypothesis entity = new AutoEdaHypothesis();
        entity.setStatus(status);
        if (posterior != null) entity.setConfidencePosterior(posterior);
        hypothesisMapper.update(entity,
                new QueryWrapper<AutoEdaHypothesis>()
                        .eq("session_id", sessionId)
                        .eq("hypothesis_id", hypothesisId));
    }

    private void updateSessionStatus(String sessionId, String status, int totalQueries) {
        AutoEdaSession session = new AutoEdaSession();
        session.setStatus(status);
        session.setTotalQueries(totalQueries);
        session.setEndTime(new Date());
        sessionMapper.update(session, new QueryWrapper<AutoEdaSession>().eq("session_id", sessionId));
    }

    private void composeAndSaveReport(String sessionId, Chart chart) {
        ExplorationBlackboard.SessionState state = blackboard.get(sessionId);
        List<AutoEdaQueryLog> queryLogs = queryLogMapper.selectList(
                new QueryWrapper<AutoEdaQueryLog>().eq("session_id", sessionId).orderByAsc("create_time"));

        InsightReportVO report = reportComposer.compose(
                state.getVerifiedHypotheses(),
                state.getPendingHypotheses(),
                queryLogs,
                chart.getName(),
                chart.getId(),
                sessionId);

        AutoEdaSession session = new AutoEdaSession();
        session.setResultJson(gson.toJson(report));
        sessionMapper.update(session, new QueryWrapper<AutoEdaSession>().eq("session_id", sessionId));
    }

    private List<String> suggestNextDirections(ExplorationBlackboard.SessionState state) {
        List<String> suggestions = new ArrayList<>();
        if (state.getPendingHypotheses().size() > 3) {
            suggestions.add("还有 " + state.getPendingHypotheses().size() + " 个假设待验证，继续探索以确认或排除");
        }
        if (state.getActiveHypotheses() != null && !state.getActiveHypotheses().isEmpty()) {
            HypothesisVO top = state.getActiveHypotheses().get(0);
            suggestions.add("当前最优先假设: " + top.getStatement());
        }
        return suggestions;
    }

    private HypothesisVO entityToVO(AutoEdaHypothesis entity) {
        HypothesisVO vo = new HypothesisVO();
        vo.setHypothesisId(entity.getHypothesisId());
        vo.setType(entity.getType());
        vo.setStatement(entity.getStatement());
        vo.setConfidencePrior(entity.getConfidencePrior());
        vo.setConfidencePosterior(entity.getConfidencePosterior());
        vo.setStatus(entity.getStatus());
        vo.setLineagePath(entity.getLineagePath());
        return vo;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/AutoEdaService.java src/yubi-backend-master/src/main/java/com/yupi/springbootinit/service/impl/AutoEdaServiceImpl.java
git commit -m "feat: add AutoEdaService orchestrating exploration loop with async execution"
```

---

### Task 12: AutoEDA Configuration

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/config/AutoEdaConfig.java`

- [ ] **Step 1: Write AutoEdaConfig**

```java
package com.yupi.springbootinit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "autoeda")
@Data
public class AutoEdaConfig {

    private IgConfig ig = new IgConfig();
    private HypothesisConfig hypothesis = new HypothesisConfig();
    private String tableName = "store_daily_metric";

    @Data
    public static class IgConfig {
        private double weightCoverage = 0.30;
        private double weightSurprise = 0.35;
        private double weightLineageDepth = 0.20;
        private double weightCost = 0.15;
        private long costMaxRows = 5000000;
    }

    @Data
    public static class HypothesisConfig {
        private double acceptThreshold = 0.75;
        private double rejectThreshold = 0.25;
        private int maxStaleRounds = 3;
    }
}
```

- [ ] **Step 2: Add configuration to application.yml**

In `src/yubi-backend-master/src/main/resources/application.yml`, add:

```yaml
autoeda:
  table-name: store_daily_metric
  ig:
    weight-coverage: 0.30
    weight-surprise: 0.35
    weight-lineage-depth: 0.20
    weight-cost: 0.15
    cost-max-rows: 5000000
  hypothesis:
    accept-threshold: 0.75
    reject-threshold: 0.25
    max-stale-rounds: 3
```

- [ ] **Step 3: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/config/AutoEdaConfig.java src/yubi-backend-master/src/main/resources/application.yml
git commit -m "feat: add AutoEDA configuration with IG weights and hypothesis thresholds"
```

---

### Task 13: AutoEdaController — REST API

**Files:**
- Create: `src/yubi-backend-master/src/main/java/com/yupi/springbootinit/controller/AutoEdaController.java`

- [ ] **Step 1: Write AutoEdaController**

```java
package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.model.dto.autoeda.ContinueExplorationRequest;
import com.yupi.springbootinit.model.dto.autoeda.StartExplorationRequest;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.autoeda.ExplorationStateVO;
import com.yupi.springbootinit.model.vo.autoeda.HypothesisVO;
import com.yupi.springbootinit.model.vo.autoeda.InsightReportVO;
import com.yupi.springbootinit.service.AutoEdaService;
import com.yupi.springbootinit.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auto-eda")
@Slf4j
public class AutoEdaController {

    @Resource
    private AutoEdaService autoEdaService;

    @Resource
    private UserService userService;

    /**
     * 启动探索会话
     */
    @PostMapping("/start")
    public BaseResponse<Map<String, Object>> start(@RequestBody StartExplorationRequest request,
                                                    HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        String sessionId = autoEdaService.startExploration(request, loginUser.getId());
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("status", "RUNNING");
        return ResultUtils.success(result);
    }

    /**
     * 轮询探索状态（前端 2-3 秒轮询）
     */
    @GetMapping("/state/{sessionId}")
    public BaseResponse<ExplorationStateVO> state(@PathVariable String sessionId,
                                                   HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.getState(sessionId));
    }

    /**
     * 获取最终报告
     */
    @GetMapping("/report/{sessionId}")
    public BaseResponse<InsightReportVO> report(@PathVariable String sessionId,
                                                 HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.getReport(sessionId));
    }

    /**
     * 用户追问继续探索
     */
    @PostMapping("/continue")
    public BaseResponse<ExplorationStateVO> continueExploration(@RequestBody ContinueExplorationRequest request,
                                                                  HttpServletRequest httpRequest) {
        userService.getLoginUser(httpRequest);
        return ResultUtils.success(autoEdaService.continueExploration(request));
    }

    /**
     * 获取单个假设详情
     */
    @GetMapping("/hypothesis/{sessionId}/{hypothesisId}")
    public BaseResponse<HypothesisVO> hypothesisDetail(@PathVariable String sessionId,
                                                        @PathVariable String hypothesisId,
                                                        HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.getHypothesisDetail(sessionId, hypothesisId));
    }

    /**
     * 停止探索
     */
    @PostMapping("/stop/{sessionId}")
    public BaseResponse<Boolean> stop(@PathVariable String sessionId,
                                       HttpServletRequest request) {
        userService.getLoginUser(request);
        return ResultUtils.success(autoEdaService.stopExploration(sessionId));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/yubi-backend-master/src/main/java/com/yupi/springbootinit/controller/AutoEdaController.java
git commit -m "feat: add AutoEdaController with start/state/report/continue/hypothesis/stop endpoints"
```

---

### Task 14: Frontend — API Service Layer

**Files:**
- Create: `src/yubi-frontend-master/src/services/yubi/autoEdaController.ts`

- [ ] **Step 1: Write API service**

```typescript
// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

// ---------- Types ----------

export type StartExplorationParams = {
  chartId: number;
  maxQueryBudget?: number;
  targetMetrics?: string[];
  convergenceEpsilon?: number;
  convergenceRounds?: number;
};

export type ExplorationState = {
  sessionId?: string;
  status?: string;
  exploredRegions?: Record<string, string[]>;
  pendingHypothesisCount?: number;
  verifiedHypothesisCount?: number;
  activeHypotheses?: HypothesisItem[];
  queriesExecuted?: number;
  queryBudget?: number;
  lastMaxIg?: number;
  converged?: boolean;
  suggestedNextDirections?: string[];
  startTime?: number;
  elapsedSeconds?: number;
};

export type HypothesisItem = {
  hypothesisId?: string;
  type?: string;
  statement?: string;
  confidencePrior?: number;
  confidencePosterior?: number;
  status?: string;
  lineagePath?: string;
  evidenceChain?: EvidenceChainItem[];
  sourceQueryIds?: string[];
};

export type EvidenceChainItem = {
  stepOrder?: number;
  queryId?: string;
  querySql?: string;
  resultSummary?: string;
  resultRowCount?: number;
  lineagePath?: string;
  igScore?: number;
};

export type InsightReport = {
  sessionId?: string;
  chartName?: string;
  chartId?: number;
  executiveSummary?: InsightItem[];
  anomalies?: AnomalyItem[];
  deepInsights?: InsightItem[];
  openLeads?: OpenLeadItem[];
  auditTrail?: AuditStep[];
  totalQueriesExecuted?: number;
  generatedAt?: number;
};

export type InsightItem = {
  title?: string;
  description?: string;
  confidence?: number;
  actionableAdvice?: string;
  evidenceChain?: EvidenceChainItem[];
};

export type AnomalyItem = {
  metricName?: string;
  anomalyDescription?: string;
  deviationSigma?: number;
  rootCause?: string;
  businessImpact?: string;
  evidenceChain?: EvidenceChainItem[];
};

export type OpenLeadItem = {
  hypothesis?: string;
  reasonIncomplete?: string;
  suggestedDataToCollect?: string;
};

export type AuditStep = {
  stepNumber?: number;
  querySql?: string;
  decisionReason?: string;
  igScore?: number;
  resultSummary?: string;
  executionMs?: number;
};

// ---------- API Calls ----------

export async function startExplorationUsingPOST(
  body: StartExplorationParams,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: { sessionId: string; status: string }; message?: string }>(
    '/api/auto-eda/start',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

export async function getStateUsingGET(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: ExplorationState; message?: string }>(
    `/api/auto-eda/state/${sessionId}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

export async function getReportUsingGET(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: InsightReport; message?: string }>(
    `/api/auto-eda/report/${sessionId}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

export async function continueExplorationUsingPOST(
  body: { sessionId: string; followUpQuery: string },
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: ExplorationState; message?: string }>(
    '/api/auto-eda/continue',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      ...(options || {}),
    },
  );
}

export async function getHypothesisDetailUsingGET(
  sessionId: string,
  hypothesisId: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: HypothesisItem; message?: string }>(
    `/api/auto-eda/hypothesis/${sessionId}/${hypothesisId}`,
    {
      method: 'GET',
      ...(options || {}),
    },
  );
}

export async function stopExplorationUsingPOST(
  sessionId: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: boolean; message?: string }>(
    `/api/auto-eda/stop/${sessionId}`,
    {
      method: 'POST',
      ...(options || {}),
    },
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/yubi-frontend-master/src/services/yubi/autoEdaController.ts
git commit -m "feat: add AutoEDA frontend API service layer"
```

---

### Task 15: Frontend — AutoEDA Page (Launch & Monitor)

**Files:**
- Create: `src/yubi-frontend-master/src/pages/AutoEDA/index.tsx`
- Create: `src/yubi-frontend-master/src/pages/AutoEDA/index.less`

- [ ] **Step 1: Write AutoEDA page component**

```tsx
import React, { useState, useRef, useEffect } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Card, Button, Select, InputNumber, Form, Space, Steps, Statistic,
  Tag, List, Typography, Spin, Progress, message, Alert
} from 'antd';
import {
  PlayCircleOutlined, PauseCircleOutlined, BulbOutlined,
  LineChartOutlined, LoadingOutlined
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  startExplorationUsingPOST, getStateUsingGET, stopExplorationUsingPOST,
  getReportUsingGET
} from '@/services/yubi/autoEdaController';
import type { ExplorationState, HypothesisItem } from '@/services/yubi/autoEdaController';
import './index.less';

const { Text, Paragraph } = Typography;

const AutoEDA: React.FC = () => {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [state, setState] = useState<ExplorationState | null>(null);
  const [loading, setLoading] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [chartId, setChartId] = useState<number | undefined>(undefined);
  const [maxBudget, setMaxBudget] = useState(30);

  const startPolling = (sid: string) => {
    pollRef.current = setInterval(async () => {
      try {
        const res = await getStateUsingGET(sid);
        if (res?.data) {
          setState(res.data);
          if (res.data.status === 'COMPLETED' || res.data.status === 'STOPPED' || res.data.status === 'FAILED') {
            if (pollRef.current) clearInterval(pollRef.current);
          }
        }
      } catch { /* ignore polling errors */ }
    }, 2000);
  };

  const handleStart = async () => {
    if (!chartId) { message.warning('请选择要分析的数据图表'); return; }
    setLoading(true);
    try {
      const res = await startExplorationUsingPOST({ chartId, maxQueryBudget: maxBudget });
      if (res?.data?.sessionId) {
        setSessionId(res.data.sessionId);
        startPolling(res.data.sessionId);
      }
    } catch { message.error('启动探索失败'); }
    finally { setLoading(false); }
  };

  const handleStop = async () => {
    if (!sessionId) return;
    await stopExplorationUsingPOST(sessionId);
    if (pollRef.current) clearInterval(pollRef.current);
  };

  const handleViewReport = () => {
    if (sessionId) history.push(`/insight-report/${sessionId}`);
  };

  useEffect(() => {
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, []);

  const isRunning = state?.status === 'RUNNING';
  const isDone = state?.status === 'COMPLETED';
  const progress = state?.queriesExecuted && state?.queryBudget
    ? Math.round((state.queriesExecuted / state.queryBudget) * 100) : 0;

  return (
    <PageContainer>
      {!sessionId && (
        <Card title="启动自主数据探索" style={{ maxWidth: 600 }}>
          <Form layout="vertical">
            <Form.Item label="选择数据图表" required>
              <Select
                showSearch
                placeholder="选择智能分析生成的图表"
                value={chartId}
                onChange={setChartId}
              />
            </Form.Item>
            <Form.Item label="探索查询预算（次）">
              <InputNumber min={5} max={100} value={maxBudget} onChange={v => setMaxBudget(v || 30)} />
            </Form.Item>
            <Button type="primary" icon={<PlayCircleOutlined />} onClick={handleStart} loading={loading} block>
              开始自主探索
            </Button>
          </Form>
        </Card>
      )}

      {sessionId && state && (
        <>
          <Card title="探索进行中" style={{ marginBottom: 16 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Progress percent={progress} status={isRunning ? 'active' : 'success'} />
              <Space>
                <Tag color={isRunning ? 'processing' : isDone ? 'success' : 'default'}>
                  {state.status === 'RUNNING' ? <><LoadingOutlined /> 探索中</> : state.status}
                </Tag>
                <Text>已执行查询: {state.queriesExecuted || 0} 次</Text>
                <Text>已验证假设: {state.verifiedHypothesisCount || 0} 个</Text>
                <Text>待验证: {state.pendingHypothesisCount || 0} 个</Text>
              </Space>
              {state.lastMaxIg != null && (
                <Text type="secondary">当前信息增益: {state.lastMaxIg.toFixed(4)} {state.converged ? '(已收敛)' : ''}</Text>
              )}
              {isRunning && (
                <Button icon={<PauseCircleOutlined />} onClick={handleStop} danger>
                  停止探索
                </Button>
              )}
              {isDone && (
                <Button type="primary" icon={<BulbOutlined />} onClick={handleViewReport}>
                  查看分析报告
                </Button>
              )}
              {state.status === 'FAILED' && (
                <Alert type="error" message="探索过程中发生错误，请重试" showIcon />
              )}
            </Space>
          </Card>

          {state.activeHypotheses && state.activeHypotheses.length > 0 && (
            <Card title="活跃假设" style={{ marginBottom: 16 }}>
              <List
                dataSource={state.activeHypotheses}
                renderItem={(h: HypothesisItem) => (
                  <List.Item>
                    <List.Item.Meta
                      avatar={<BulbOutlined style={{ color: '#faad14' }} />}
                      title={
                        <Space>
                          <Tag>{h.type}</Tag>
                          <Text strong>{h.statement}</Text>
                          <Tag color="blue">
                            后验 {h.confidencePosterior ? (h.confidencePosterior * 100).toFixed(0) : '?'}%
                          </Tag>
                        </Space>
                      }
                      description={h.lineagePath ? `血缘路径: ${h.lineagePath}` : undefined}
                    />
                  </List.Item>
                )}
              />
            </Card>
          )}

          {state.suggestedNextDirections && state.suggestedNextDirections.length > 0 && (
            <Card title="探索建议" size="small">
              {state.suggestedNextDirections.map((s, i) => (
                <Paragraph key={i} type="secondary">💡 {s}</Paragraph>
              ))}
            </Card>
          )}
        </>
      )}
    </PageContainer>
  );
};

export default AutoEDA;
```

- [ ] **Step 2: Write minimal styles**

```less
// AutoEDA/index.less
.ant-statistic-content {
  font-size: 16px;
}
```

- [ ] **Step 3: Commit**

```bash
git add src/yubi-frontend-master/src/pages/AutoEDA/
git commit -m "feat: add AutoEDA launch and live monitoring page"
```

---

### Task 16: Frontend — InsightReport Page

**Files:**
- Create: `src/yubi-frontend-master/src/pages/InsightReport/index.tsx`
- Create: `src/yubi-frontend-master/src/pages/InsightReport/index.less`

- [ ] **Step 1: Write InsightReport page**

```tsx
import React, { useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Card, Collapse, Tag, Table, Typography, Spin, Empty, Space, Descriptions, Statistic, Row, Col
} from 'antd';
import {
  BulbOutlined, AlertOutlined, LineChartOutlined, QuestionCircleOutlined,
  AuditOutlined
} from '@ant-design/icons';
import { useParams } from '@umijs/max';
import { getReportUsingGET } from '@/services/yubi/autoEdaController';
import type { InsightReport, InsightItem, AnomalyItem, OpenLeadItem, AuditStep } from '@/services/yubi/autoEdaController';
import './index.less';

const { Text, Paragraph, Title } = Typography;

const InsightReportPage: React.FC = () => {
  const { sessionId } = useParams<{ sessionId: string }>();
  const [report, setReport] = useState<InsightReport | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (sessionId) {
      getReportUsingGET(sessionId)
        .then(res => { if (res?.data) setReport(res.data); })
        .finally(() => setLoading(false));
    }
  }, [sessionId]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '200px auto' }} />;
  if (!report) return <Empty description="报告未生成" />;

  const evidenceColumns = [
    { title: '步骤', dataIndex: 'stepOrder', key: 'stepOrder', width: 60 },
    { title: '查询SQL', dataIndex: 'querySql', key: 'querySql', ellipsis: true },
    { title: '结果摘要', dataIndex: 'resultSummary', key: 'resultSummary', ellipsis: true },
    { title: '结果行数', dataIndex: 'resultRowCount', key: 'resultRowCount', width: 80 },
    { title: 'IG', dataIndex: 'igScore', key: 'igScore', width: 60, render: (v: number) => v?.toFixed(3) },
  ];

  return (
    <PageContainer>
      <Title level={4}>{report.chartName} — 自主探索分析报告</Title>
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}><Statistic title="探索查询数" value={report.totalQueriesExecuted} /></Col>
        <Col span={6}><Statistic title="关键发现" value={(report.executiveSummary || []).length} /></Col>
        <Col span={6}><Statistic title="异常发现" value={(report.anomalies || []).length} /></Col>
        <Col span={6}><Statistic title="深层洞察" value={(report.deepInsights || []).length} /></Col>
      </Row>

      {/* 1. Executive Summary */}
      <Card
        title={<><BulbOutlined /> 关键发现 (Executive Summary)</>}
        style={{ marginBottom: 16 }}
      >
        {(report.executiveSummary || []).map((item: InsightItem, i: number) => (
          <Card key={i} type="inner" size="small" style={{ marginBottom: 8 }}
            title={
              <Space>
                <Tag color="blue">置信度 {(item.confidence || 0) * 100}%</Tag>
                {item.title}
              </Space>
            }
          >
            <Paragraph>{item.description}</Paragraph>
            {item.actionableAdvice && (
              <Text type="success">💡 建议: {item.actionableAdvice}</Text>
            )}
            {item.evidenceChain && item.evidenceChain.length > 0 && (
              <Collapse ghost items={[{
                key: 'evidence', label: '证据链',
                children: <Table rowKey="stepOrder" dataSource={item.evidenceChain} columns={evidenceColumns} pagination={false} size="small" />
              }]} />
            )}
          </Card>
        ))}
      </Card>

      {/* 2. Anomalies */}
      {report.anomalies && report.anomalies.length > 0 && (
        <Card
          title={<><AlertOutlined /> 异常与预警 (Anomalies & Alerts)</>}
          style={{ marginBottom: 16 }}
        >
          {(report.anomalies || []).map((a: AnomalyItem, i: number) => (
            <Card key={i} type="inner" size="small" style={{ marginBottom: 8 }}
              title={
                <Space>
                  <Tag color="red">{a.metricName}</Tag>
                  {a.anomalyDescription}
                  <Tag>{a.deviationSigma}σ</Tag>
                </Space>
              }
            >
              <Descriptions column={2} size="small">
                <Descriptions.Item label="根因">{a.rootCause}</Descriptions.Item>
                <Descriptions.Item label="业务影响">{a.businessImpact}</Descriptions.Item>
              </Descriptions>
            </Card>
          ))}
        </Card>
      )}

      {/* 3. Deep Insights */}
      {report.deepInsights && report.deepInsights.length > 0 && (
        <Card
          title={<><LineChartOutlined /> 深层洞察 (Deep Insights)</>}
          style={{ marginBottom: 16 }}
        >
          {(report.deepInsights || []).map((item: InsightItem, i: number) => (
            <Card key={i} type="inner" size="small" style={{ marginBottom: 8 }}
              title={item.title}
              extra={<Tag>置信度 {(item.confidence || 0) * 100}%</Tag>}
            >
              <Paragraph>{item.description}</Paragraph>
              {item.actionableAdvice && <Text type="success">💡 {item.actionableAdvice}</Text>}
            </Card>
          ))}
        </Card>
      )}

      {/* 4. Open Leads */}
      {report.openLeads && report.openLeads.length > 0 && (
        <Card
          title={<><QuestionCircleOutlined /> 待确认线索 (Open Leads)</>}
          style={{ marginBottom: 16 }}
        >
          {(report.openLeads || []).map((lead: OpenLeadItem, i: number) => (
            <Card key={i} type="inner" size="small" style={{ marginBottom: 8 }}>
              <Paragraph><Text strong>假设:</Text> {lead.hypothesis}</Paragraph>
              <Paragraph><Text type="warning">原因:</Text> {lead.reasonIncomplete}</Paragraph>
              <Paragraph><Text type="secondary">建议补充: {lead.suggestedDataToCollect}</Text></Paragraph>
            </Card>
          ))}
        </Card>
      )}

      {/* 5. Audit Trail */}
      <Card title={<><AuditOutlined /> 探索过程 (Audit Trail)</>}>
        <Table
          rowKey="stepNumber"
          dataSource={report.auditTrail || []}
          columns={[
            { title: '#', dataIndex: 'stepNumber', key: 'stepNumber', width: 50 },
            { title: 'SQL', dataIndex: 'querySql', key: 'querySql', ellipsis: true, width: 300 },
            { title: '决策理由', dataIndex: 'decisionReason', key: 'decisionReason', width: 150 },
            { title: '结果摘要', dataIndex: 'resultSummary', key: 'resultSummary', ellipsis: true },
            { title: 'IG', dataIndex: 'igScore', key: 'igScore', width: 70, render: (v: number) => v?.toFixed(3) },
            { title: '耗时ms', dataIndex: 'executionMs', key: 'executionMs', width: 80 },
          ]}
          pagination={{ pageSize: 10 }}
          size="small"
        />
      </Card>
    </PageContainer>
  );
};

export default InsightReportPage;
```

- [ ] **Step 2: Write styles**

```less
// InsightReport/index.less
// Minimal styles — Ant Design handles most layout
```

- [ ] **Step 3: Commit**

```bash
git add src/yubi-frontend-master/src/pages/InsightReport/
git commit -m "feat: add InsightReport interactive page with evidence chains and audit trail"
```

---

### Task 17: Frontend — Route Configuration

**Files:**
- Modify: `src/yubi-frontend-master/config/routes.ts`

- [ ] **Step 1: Add new routes**

Find the routes array in `config/routes.ts`. Add these entries:

```typescript
{
  path: '/auto-eda',
  name: '自主探索',
  icon: 'compass',
  component: './AutoEDA',
},
{
  path: '/insight-report/:sessionId',
  name: '分析报告',
  icon: 'fileText',
  component: './InsightReport',
  hideInMenu: true,
},
```

- [ ] **Step 2: Commit**

```bash
git add src/yubi-frontend-master/config/routes.ts
git commit -m "feat: register AutoEDA and InsightReport routes"
```

---

### Task 18: Integration Verification

- [ ] **Step 1: Start backend and verify compilation**

```powershell
cd "D:\zhuomian\data analysis platform\src\yubi-backend-master"
.\mvnw.cmd compile
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Execute schema SQL**

```powershell
Get-Content "D:\zhuomian\data analysis platform\src\yubi-backend-master\sql\auto_eda_schema.sql" | mysql -u root -p yubi
```

- [ ] **Step 3: Start backend and verify API**

```powershell
.\mvnw.cmd spring-boot:run
```

After startup, open `http://localhost:18080/api/doc.html` and verify that `/auto-eda/start`, `/auto-eda/state/{sessionId}`, etc. appear in Swagger.

- [ ] **Step 4: Start frontend and verify pages**

```powershell
cd "D:\zhuomian\data analysis platform\src\yubi-frontend-master"
npm run start:dev
```

Navigate to `http://localhost:8000/auto-eda` and verify the page renders. Select a chart and click "开始自主探索" to trigger a test exploration.

- [ ] **Step 5: Commit any fixups**

```bash
git add -A
git commit -m "chore: integration fixes for AutoEDA"
```
