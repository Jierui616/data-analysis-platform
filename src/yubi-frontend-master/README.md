# 智能数据分析平台 · 前端（湖仓数智金融分析平台）

基于 **Ant Design Pro + Umi Max 4** 的企业级中后台前端，与 `yubi-backend` 配合使用，提供智能图表生成、经营大屏、数据血缘与风险画像等页面。

## 技术栈

- **框架**：React 18、Umi Max 4（`@umijs/max`）
- **UI**：Ant Design 5、Ant Design Pro Components
- **可视化**：ECharts、`echarts-for-react`、`echarts-gl`
- **其他**：lodash、moment、xlsx（导入/导出等）

## 功能与路由

| 路由 | 说明 |
|------|------|
| `/user/login` | 登录 |
| `/add_chart` | 智能分析（实时） |
| `/add_chart_async` | 智能分析（异步） |
| `/deep_chat` | 深度对话（BI 多轮） |
| `/chart_result/:id` | 图表结果页 |
| `/my_chart` | 图表管理与结果回看（含血缘弹窗等） |
| `/lineage_center` | 血缘追踪中心 |
| `/data_import` | 成本与排班导入 |
| `/bi_bigscreen` | 经营指挥大屏 |
| `/risk_portrait/overview` | 风险画像 · 总览 |
| `/risk_portrait/store` | 门店/加盟商画像 |
| `/risk_portrait/member` | 会员画像 |
| `/risk_portrait/anonymous` | 匿名用户画像 |
| `/risk_portrait/rules` | 策略与规则 |
| `/risk_portrait/audit` | 命中与留痕 |
| `/admin/*` | 管理页（需管理员权限） |

默认进入站点根路径 `/` 会重定向到 **`/add_chart`**。

布局标题与站点名称在 `config/config.ts`、`config/defaultSettings.ts` 中配置（当前为「湖仓数智金融分析平台」）。

## 环境要求

- **Node.js** ≥ 12（推荐 18 LTS）
- 本地需启动后端 API，或使用与 `config/proxy.ts` 一致的远程地址

## 安装依赖

```bash
cd src/yubi-frontend-master   # 自 <项目根> 进入
npm install
```

安装完成后若缺少 Umi 生成目录，可执行：

```bash
npx max setup
```

## 本地开发

推荐使用无 Mock、对接真实后端的命令：

```bash
npm run start:dev
```

默认前端开发服务器：**http://localhost:8000**（以终端输出为准）。

### 代理与后端地址

开发环境下，`/api` 请求会通过 `config/proxy.ts` 转发：

- 当前 **`dev`** 配置将 `/api/` 代理到 **`http://127.0.0.1:18080`**（与后端 `application.yml` 中 `server.port` 一致）。
- 若你改了后端端口，请同步修改 `proxy.ts` 的 `target` 以及 `config/config.ts`、`src/app.tsx` 里写死的后端地址。

生产构建后不经过 devServer，需通过网关或 Nginx 将前端同源请求转发到后端。

## 构建与预览

```bash
npm run build
npm run preview
```

产物在 `dist/`。`preview` 默认端口见 `package.json` 中脚本说明。

## 代码与接口

- 页面源码：`src/pages/`
- 对接后端的请求封装：`src/services/yubi/`（如 `chartController.ts`、`restaurantController.ts`、`riskPortraitController.ts`、`biCenterController.ts`、`userController.ts`）
- 路由：`config/routes.ts`
- 主题与布局：`config/defaultSettings.ts`、`config/config.ts`

若后端 OpenAPI 变更，可在配置好 `config/config.ts` 中的 openapi 插件后执行：

```bash
npm run openapi
```

以重新生成部分接口定义（生成前建议备份已手工修改的文件）。

## 质量脚本（可选）

```bash
npm run lint       # ESLint + Prettier + tsc
npm run lint:fix   # 自动修复部分 ESLint 问题
npm test           # Jest
```

## 与后端 README 的对应关系

数据库初始化、`dashscope`、风险画像演示开关等以后端 [`../yubi-backend-master/README.md`](../yubi-backend-master/README.md) 为准；前端仅负责展示与调用 REST API。
