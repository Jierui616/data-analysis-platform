# CLAUDE.md — 数据分析平台开发约束

## 项目概述

基于 React + Spring Boot + AIGC 的智能数据分析平台。核心特色是 **AutoEDA 自主探索引擎**——一个能主动探索数据、生成假设、贝叶斯验证、自适应收敛的 Agent 系统。

## 技术栈

| 层 | 技术 |
|---|------|
| 前端 | React + TypeScript + ECharts |
| 后端 | Spring Boot + Spring Cloud |
| 消息队列 | RabbitMQ（图表 AI 异步）、Kafka（业务事件流） |
| AI | Ollama / OpenAI 兼容接口 |
| 存储 | MySQL（主库）、ClickHouse（分析读）、Redis（缓存）、MinIO（大文件） |
| 构建 | Maven (Java 8, Spring Boot 2.x) |

## EDA 子系统架构（论文核心）

```
AutoEdaController (API层)
  └─ AutoEdaServiceImpl (探索主循环)
       ├─ CandidateQueryGenerator (候选查询池生成: 下钻/邻域/未覆盖/假设验证)
       ├─ ExplorationOrchestrator (编排: 血缘过滤 → IG评分 → 选择最佳)
       ├─ ExplorationBlackboard (共享状态: 已探索区域/假设池/轨迹/收敛)
       ├─ HypothesisManager (假设管理: LLM生成 → 证据评估 → 贝叶斯更新)
       ├─ InfoGainCalculator (信息增益: Coverage + Surprise + Lineage - Cost)
       ├─ QueryGenerator (SQL生成: LLM + 模板降级)
       └─ ReportComposer (报告合成: 洞察 + 异常 + 证据链)
```

### 关键类

| 类 | 文件 | 职责 |
|----|------|------|
| `ExplorationBlackboard` | `service/eda/ExplorationBlackboard.java` | 探索状态黑板（S_explored, H_verified, H_pending, H_active, trajectory） |
| `HypothesisManager` | `service/eda/HypothesisManager.java` | LLM 假设生成 + Bayesian 更新 + HypothesisCycle |
| `InfoGainCalculator` | `service/eda/InfoGainCalculator.java` | IG(q) = w1*Coverage + w2*Surprise + w3*Lineage - w4*Cost |
| `CandidateQueryGenerator` | `service/eda/CandidateQueryGenerator.java` | 策略 a/b/c/d 生成候选查询池 |
| `ExplorationOrchestrator` | `service/eda/ExplorationOrchestrator.java` | 血缘过滤 → IG 评分 → 最优选择 |
| `QueryGenerator` | `service/eda/QueryGenerator.java` | LLM 生成 SQL + 模板降级 |
| `ReportComposer` | `service/eda/ReportComposer.java` | 结构化报告 + 审计轨迹 |
| `ExplorationMode` | `service/eda/ExplorationMode.java` | 实验模式枚举（FULL/RANDOM/LLM_ONLY/...） |
| `ExplorationContext` | `service/eda/ExplorationContext.java` | 单轮探索上下文 DTO |
| `AutoEdaServiceImpl` | `service/impl/AutoEdaServiceImpl.java` | 探索主循环 + API 实现 |
| `HypothesisVerifyExecutor` | `service/eda/HypothesisVerifyExecutor.java` | 假设验证 SQL 执行 |

### 关键配置 (application.yml)

```yaml
autoeda:
  ig:
    weight-coverage: 0.30
    weight-surprise: 0.35
    weight-lineage-depth: 0.20
    weight-cost: 0.15
  experiment:
    use-explain-cost: true
  hypothesis:
    accept-threshold: 0.75
    reject-threshold: 0.25
    max-stale-rounds: 3
```

## 论文创新升级计划

1. **LCE** (Lineage-Constrained Exploration) — 已实现
2. **BHC** (Dual-Loop + Bayesian Hypothesis Cycle) — 已实现
3. **PSC** (Posterior Staleness Convergence) — 已实现
4. **AIG** (Adaptive InfoGain) — **待实现**：三阶段自适应权重
5. **EHIG** (Expected Hypothesis InfoGain) — **待实现**：信息论统一框架

## 工作约束

### 代码风格
- 遵循现有代码风格：`@Component` + `@Resource` 注入
- 类注释用中文，方法可用中/英文
- 日志用 `@Slf4j` + `log.info/warn/debug`
- 所有新类放入 `com.yupi.springbootinit.service.eda` 包下
- 配置项通过 `@Value` 注入，在 application.yml 中声明默认值

### 实现原则
- **增量开发**：不重构现有代码，保持向后兼容
- **老师的基础 IG 不动**：`InfoGainCalculator` 保持不变，新策略作为独立类添加
- **AIG 作为 IG 的升级方案**：新增 `AdaptiveInfoGainCalculator`，不是替换
- **EHIG 作为可选策略**：新增 `EntropyBasedSelector`，与 IG/AIG 并行
- **实验模式扩展**：在 `ExplorationMode` 中添加新枚举值
- 每次改动后编译检查

### 编译与运行
- 后端根目录：`src/yubi-backend-master/`
- 构建命令：`mvn compile -f src/yubi-backend-master/pom.xml`
- JDK 版本：Java 8
- 不要引入新的 Maven 依赖，除非确实需要且经过确认

### Git 约束
- 每个功能点完成后 commit
- Commit message 格式：`feat: <中文描述>`
- 关联论文创新点请在 commit body 中标注（如 `Ref: AIG-1`）
- 不要 force push，不要改 main 分支历史

### 禁止事项
- ❌ 不删除或重构 `InfoGainCalculator.java`（老师的基础版本）
- ❌ 不修改数据库 schema 除非必要
- ❌ 不引入新的 Maven 依赖除非明确需要
- ❌ 不修改前端代码
- ❌ 不修改 application.yml 中已有的配置项值
