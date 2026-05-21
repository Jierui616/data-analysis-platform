# 数据分析平台（全栈）

智能数据分析与经营 BI 项目：后端提供 REST API（含 AI 分析、血缘、风险画像等），前端为 Ant Design Pro 中后台，二者联调使用。

## 仓库结构

| 路径 | 说明 |
|------|------|
| [`src/yubi-backend-master/`](src/yubi-backend-master/) | Spring Boot 后端（MyBatis-Plus、MySQL、可选 Redis / RabbitMQ / ES 等） |
| [`src/yubi-frontend-master/`](src/yubi-frontend-master/) | React + Umi Max 4 前端（ECharts 可视化） |
| [`deploy.md`](deploy.md) | **从零部署主文档**：环境、数据库脚本顺序、配置项说明 |
| [`data/`](data/) | 示例或导入相关数据（若存在） |

更细的模块说明见各子目录 README：

- 后端：[src/yubi-backend-master/README.md](src/yubi-backend-master/README.md)
- 前端：[src/yubi-frontend-master/README.md](src/yubi-frontend-master/README.md)

## 环境概要

- **JDK 8+**、**Maven**（或使用后端自带的 `mvnw` / `mvnw.cmd`）
- **MySQL 5.7+ / 8.x**（默认库名等与 `application.yml` 一致）
- **Node.js** ≥ 12（推荐 18 LTS），用于前端
- 可选：**Redis**、**RabbitMQ**、**Elasticsearch**（按功能需要启用，详见后端 README）

## 快速本地运行（摘要）

完整步骤（含 SQL 顺序、密钥与中间件）请以 **[deploy.md](deploy.md)** 为准。

1. **初始化数据库**：在 `src/yubi-backend-master/sql/` 下按文档顺序导入。
2. **后端**：编辑 `src/yubi-backend-master/src/main/resources/application.yml`（数据源等），在后端目录执行：
   - Windows：`.\mvnw.cmd spring-boot:run`
   - 默认：**http://127.0.0.1:18080**，上下文 **`/api`**
3. **前端**：进入 `src/yubi-frontend-master`，执行 `npm install` 与 `npm run start:dev`，默认：**http://localhost:8000**（`/api` 由 `config/proxy.ts` 代理到后端）。

## 技术栈速览

- **后端**：Spring Boot 2.7、MyBatis-Plus、Spring Session（可选 Redis）、阿里云百炼（DashScope）等
- **大数据（可选）**：ClickHouse（OLAP）、Kafka（事件流）、MinIO（对象存储）；见 [deploy.md](deploy.md) 第三节与 `docker-compose.bigdata.yml`
- **前端**：React 18、Ant Design 5、Umi Max 4、ECharts

## 许可证与声明

子项目若自带许可证文件，以其为准；使用第三方 API（如百炼）时请遵守对应服务条款，勿将真实密钥提交到公开仓库。
