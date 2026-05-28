# Fix: AutoEDA Data & Design Issues

> **Context:** Three problems in the InfoGain-EDA implementation: (1) AutoEDA page chart select dropdown is empty — no chart API wired up; (2) exploration queries use hardcoded table name, not connected to real ClickHouse data; (3) both AutoEDA and InsightReport pages have minimal, unattractive UI.

**Goal:** Wire chart list into AutoEDA page, connect exploration to real ClickHouse table `yubi_analytics.store_daily_metric_ch`, and redesign both pages with proper ProCard layouts, color-coded indicators, and interactive evidence chains.

---

## Phase 1: Data Source Connection (Backend)

### 1.1 ClickHouseQueryService — Add column discovery

**File:** `service/ClickHouseQueryService.java`
- Add `List<String> discoverColumns(String tableName)` method

**File:** `service/impl/ClickHouseQueryServiceImpl.java`
- Implement `discoverColumns` via `DESCRIBE TABLE` (or `SELECT * LIMIT 0`)
- Returns column names as strings

### 1.2 AutoEdaServiceImpl — Use real ClickHouse table

**File:** `service/impl/AutoEdaServiceImpl.java`
- Change `@Value("${autoeda.table-name:store_daily_metric}")` to `yubi_analytics.store_daily_metric_ch`
- In `explorationLoop()`: call `clickHouseQueryService.discoverColumns(defaultTableName)` to get real columns
- Use `chart.getGoal()` as exploration intent context
- Pass real columns to `queryGenerator.generateSql()` instead of lineage-derived nodes
- In `generateCandidateDimensions()`: use real metric columns (revenue, grossMarginRate, avgTicket, etc.) as dimension sources

### 1.3 QueryGenerator — Use real columns and improve prompts

**File:** `service/eda/QueryGenerator.java`
- The `availableColumns` parameter already works — no change needed here
- Improve the `generateTemplateSql()` fallback to use real ClickHouse column names

---

## Phase 2: Chart Selection (Frontend)

### 2.1 AutoEDA page — Accept chartId as URL param

**File:** `pages/AutoEDA/index.tsx`
- Use `useSearchParams()` to read `?chartId=xxx` from URL
- On mount, if chartId present: fetch chart details via `getChartByIdUsingGET({id: chartId})`
- Display chart name, goal, type in a header card
- Auto-populate the chart select with the current chart
- Also fetch recent chart list via `listMyChartByPageUsingPOST` to populate dropdown options

### 2.2 MyChart page — Add "自主探索" menu item

**File:** `pages/MyChart/index.tsx`
- Add menu item to `getCardMenuItems`:
  ```ts
  { key: 'autoEda', label: '自主探索', icon: <CompassOutlined /> }
  ```
- In `handleMenuClick`: `navigate(`/ai/auto-eda?chartId=${chartId}`)`
- Import `CompassOutlined` from `@ant-design/icons`

---

## Phase 3: Page Redesign (Frontend)

### 3.1 AutoEDA page redesign

**File:** `pages/AutoEDA/index.tsx`

Redesign with these sections:
1. **Header Card** — Chart name, goal, type badge (only when chartId provided)
2. **Config Card** — Budget slider + Start button (compact, inline form)
3. **Progress Card** (during exploration) — Steps component showing phase, Progress bar, Statistic row (queries executed, hypotheses verified, last IG score)
4. **Active Hypotheses List** — Cards with color-coded confidence badges (green >75%, yellow 40-75%, red <40%), type tags, lineage path on hover
5. **Suggestions Panel** — Alert-style cards with bulb icon

Use ProCard for all sections. Add proper loading states and empty states.

### 3.2 InsightReport page redesign

**File:** `pages/InsightReport/index.tsx`

Redesign with:
1. **Report Header** — Chart name, generation time, stat row (Row/Col with Statistic)
2. **Executive Summary** — Timeline component for key findings, confidence badges
3. **Anomalies & Alerts** — Red-themed ProCard with Descriptions for root cause/business impact
4. **Deep Insights** — Blue-themed ProCard with expandable evidence chains
5. **Open Leads** — Orange-themed cards suggesting next data to collect
6. **Audit Trail** — Collapsible Table with SQL syntax highlighting (using `<pre><code>`)

Use ProCard `collapsible` and `ghost` variants. Add back-to-exploration button.

### 3.3 Styles

**File:** `pages/AutoEDA/index.less` — Add spacing, card hover effects, confidence badge colors
**File:** `pages/InsightReport/index.less` — Add section spacing, audit trail SQL styling, evidence chain visual

---

## Verification

1. Compile backend: `./mvnw compile` (expect BUILD SUCCESS)
2. Start backend: `./mvnw spring-boot:run` (expect port 18080)
3. Start frontend: `npm run start:dev` (expect Webpack compiled successfully)
4. Open `http://localhost:8000/biz/charts` — verify "自主探索" appears in card dropdown menu
5. Click "自主探索" on a chart — verify navigation to `/ai/auto-eda?chartId=xxx` with chart info displayed
6. Click "开始自主探索" — verify exploration starts, progress shows, hypotheses appear
7. After completion, click "查看分析报告" — verify report loads with all 5 sections styled correctly
