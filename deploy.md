# 零售经营智能分析平台 · 部署说明

## 仓库目录约定

```text
<项目根>/
├── deploy.md              # 本文件：环境、数据库、启动步骤
├── docker-compose.bigdata.yml  # 可选：本地 ClickHouse + Kafka + MinIO
├── requirements.txt       # 可选占位（主栈非 Python 时可仅注释）
├── README.md              # 入口简介（可选）
├── src/                   # 项目源码
│   ├── yubi-backend-master/   # Spring Boot 后端
│   └── yubi-frontend-master/  # Umi 前端
└── data/                  # 测试与演示数据
    └── testdata/          # CSV 等离线样例（成本/排班/扩展经营）
```

下文用 **`<项目根>`** 表示本工作区所在目录。源码路径为 **`src/yubi-backend-master`**、**`src/yubi-frontend-master`**。

更细的后端技术说明见 [`src/yubi-backend-master/README.md`](src/yubi-backend-master/README.md)，前端路由与构建见 [`src/yubi-frontend-master/README.md`](src/yubi-frontend-master/README.md)。

### 迁移目录后前端注意事项

若你曾在本机其他路径下运行过前端，Umi 可能在 `src/yubi-frontend-master/src/.umi` 中写入了**绝对路径**；移动工程后请**删除**该目录（或整目录重新 `npm install` 后首次 `npm run start:dev` 会重建）。本仓库重组后已清理 `.umi`，首次在 `src/yubi-frontend-master` 下执行 `npm run start:dev` 即可。

---

## 一、拿到代码后先确认目录

应至少包含：

- `src/yubi-backend-master/` — 后端与 `sql/` 初始化脚本  
- `src/yubi-frontend-master/` — 前端工程  
- `data/testdata/` — 导入演示用 CSV 等（可选；与「数据导入中心」模板列一致）

---

## 二、环境准备

| 组件 | 用途 | 说明 |
|------|------|------|
| **JDK 8+** | 运行后端 | 配置 `JAVA_HOME` 与 `PATH` |
| **MySQL 5.7+ / 8.x** | 主库 | 必装；默认库名 **`yubi`**，需与 JDBC URL 一致 |
| **Maven** | 构建后端 | 可选；后端自带 **`mvnw.cmd` / `mvnw`**，未装 Maven 也可用 Wrapper |
| **Node.js** | 前端 | 推荐 **18 LTS**（≥12） |
| **Docker Desktop**（可选） | Redis / RabbitMQ / **大数据栈** | 见「三」与「三（乙）」；未用对应能力可不装 |

---

## 三、（可选）Docker 启动 Redis / RabbitMQ

若尚未创建容器，首次可执行（端口与 `application.yml` 默认一致，可按需改映射）：

```powershell
docker run -d --name yubi-redis -p 6379:6379 redis:7-alpine
docker run -d --name yubi-rabbit -p 5672:5672 -p 15672:15672 -e RABBITMQ_DEFAULT_USER=guest -e RABBITMQ_DEFAULT_PASS=guest rabbitmq:3-management-alpine
```

之后每次开机或 Docker 重启后，若容器已存在但未运行：

```powershell
docker start yubi-redis
docker start yubi-rabbit
```

说明：当前后端默认排除了部分 Redis 自动配置，多数本地开发可先不启 Redis；若业务需要 Session/缓存走 Redis，需按后端 README 调整 `MainApplication` 与 `application.yml`。

---

## 三（乙）、（可选）本地大数据栈：ClickHouse + Kafka + MinIO

架构图中的 **ClickHouse / Kafka / MinIO** 与主业务库 **MySQL** 独立，用于本地联调 OLAP、事件流与对象存储。**需已安装 Docker Desktop（Windows）或 Docker Engine（Linux）**。

### 1. 一键启动

在 **`<项目根>`** 目录执行：

```powershell
docker compose -f docker-compose.bigdata.yml up -d
```

首次会拉取镜像，可能需要几分钟。查看容器状态：

```powershell
docker compose -f docker-compose.bigdata.yml ps
```

停止并删除容器（**保留数据卷**，下次 `up` 数据仍在）：

```powershell
docker compose -f docker-compose.bigdata.yml down
```

若要连数据卷一并清空（慎用）：

```powershell
docker compose -f docker-compose.bigdata.yml down -v
```

### 2. 端口与默认账号（仅本地）

| 服务 | 宿主机端口 | 说明 |
|------|------------|------|
| ClickHouse HTTP | **8123** | JDBC/HTTP：`jdbc:ch://localhost:8123/yubi_analytics`（驱动见后端接入文档） |
| ClickHouse Native | **9000** | `clickhouse-client` 默认协议端口 |
| Kafka | **9092** | Bootstrap：`localhost:9092`；KRaft 模式，无 ZooKeeper |
| MinIO S3 API | **9100** | 避免与 ClickHouse 9000 冲突，映射为 9100 |
| MinIO 控制台 | **9101** | 浏览器打开 `http://localhost:9101` |

- **MinIO** 默认凭据：`minioadmin` / `minioadmin123`（仅开发用，勿用于生产）。  
- **ClickHouse** 默认用户 `default`，**无密码**（仅开发用）。首次启动会创建库 **`yubi_analytics`**。  
- **Kafka**：`auto.create.topics.enable=true`，可直接用客户端发消息建 Topic。

### 3. 与 Spring 后端联调（已接入）

后端已提供 **ClickHouse / Kafka / MinIO** 集成（默认关闭，不影响纯 MySQL 启动）：

1. 复制配置模板并启用 profile：
   ```powershell
   cd "<项目根>\src\yubi-backend-master\src\main\resources"
   copy application-bigdata-local.yml.example application-bigdata-local.yml
   ```
   启动后端时增加 profile（或在 IDE 中设置 `spring.profiles.active=dev,bigdata-local`）：
   ```powershell
   cd "<项目根>\src\yubi-backend-master"
   $env:SPRING_PROFILES_ACTIVE="dev,bigdata-local"
   .\mvnw.cmd spring-boot:run
   ```
2. 首次启用 ClickHouse 后，登录系统调用 **`POST /api/bigdata/sync/metrics`**，将 MySQL `store_daily_metric` 同步到 OLAP 表（也可用 Knife4j）。
3. 查看栈状态：**`GET /api/bigdata/status`**（ClickHouse 行数、Kafka、MinIO 连通性）。
4. 总部经营概览 **`POST /api/restaurant/overview/hq`** 响应中会带 **`bigData`** 字段（含 `analyticsEngine`: `mysql` / `clickhouse`）；同步完成且 `bigdata.enabled=true` 时，summary/trend 优先走 ClickHouse。
5. 图表 CSV 外置：在 profile 中设 `storage.chartData.enabled: true`，并确保已执行 `sql/chart_data_ref_alter.sql`。

| 组件 | 职责 |
|------|------|
| **MySQL** | 事务主库（用户、图表、餐饮指标写入） |
| **ClickHouse** | 门店日指标 OLAP、平台事件事实表 |
| **Kafka** | 图表生成完成、导入完成等事件（与 RabbitMQ 图表任务队列并存） |
| **MinIO** | 图表原始 CSV 对象存储 |
| **RabbitMQ** | 不变，仍负责 `/chart/gen/async/mq` 异步 AI 任务 |

### 4. 常见问题

- **拉取镜像 `403 Forbidden`（如 `dockerhub.azk8s.cn` + `bitnami/*`）**：Compose 已改为 **`apache/kafka` 官方镜像**；若仍 403，可在 Docker Desktop 里暂时关闭/更换 `registry-mirrors`，或对 `docker.io` 使用可拉取的代理源。  
- **端口被占用**：修改 `docker-compose.bigdata.yml` 左侧宿主机端口后再 `up`。  
- **Kafka 从本机 Java 连接**：Bootstrap 使用 **`localhost:9092`**（与官方示例中 `PLAINTEXT_HOST://localhost:9092` 一致）。  
- **WSL2 / 防火墙**：若前端或其它机器访问不到 `localhost`，需按 Docker 文档检查端口转发。

---

## 四、修改后端配置

编辑 **`src/yubi-backend-master/src/main/resources/application.yml`**（敏感项勿提交到公开仓库，可用本地私有配置或环境变量覆盖）：

- **`spring.datasource`**：主库 `url`/`username`/`password`（默认 `localhost:3306/yubi`）。  
- **`spring.datasource.restaurant`**：餐饮库 `yubi_restaurant`。  
- **`spring.datasource.risk`**：风险画像库 `yubi_risk`。三个库默认在同一 MySQL 实例，可配置不同实例。
- **`dashscope.api-key`** 等：使用阿里云百炼时按需填写。
- **`spring.rabbitmq.*`**：使用消息队列时与 Docker 或本机 RabbitMQ 一致。
- **`server.port`**：默认 **18080**；`context-path` 为 **`/api`**。若改端口，需同步改前端代理（见下文）。

---

## 五、初始化数据库（首次必做）

项目现已拆分为 **3 个 MySQL 库** + **1 个 ClickHouse 库**：

| 数据库 | 说明 | 表数 |
|--------|------|------|
| `yubi` | 主库：核心平台 + 血缘 + AutoEDA | 12 |
| `yubi_restaurant` | 餐饮模块：门店、菜品、活动、成本、排班、预警 | 10 |
| `yubi_risk` | 风险画像模块：命中留痕、会员名单、匿名分桶 | 5 |
| `yubi_analytics` | ClickHouse OLAP（可选，见三（乙）） | 2 |

### 5.1 一键部署（推荐）

进入后端目录，执行 `deploy_all.sql`（一次完成所有建库 + 建表 + 种子数据）：

**PowerShell：**
```powershell
cd "<项目根>\src\yubi-backend-master"
Get-Content .\sql\deploy_all.sql | mysql -u root -p
```

**CMD / Git Bash：**
```bash
cd "src/yubi-backend-master"
mysql -u root -p < sql/deploy_all.sql
```

若要同时灌入演示数据（3 家门店 × 7 天），编辑 `deploy_all.sql` 取消注释 `SOURCE 06_fixture_restaurant.sql` 一行，再执行。

### 5.2 分步执行（也可逐库手动）

```powershell
cd "<项目根>\src\yubi-backend-master"

# 主库 yubi（核心 + 血缘 + AutoEDA）
Get-Content .\sql\00_init_yubi.sql | mysql -u root -p
Get-Content .\sql\01_init_yubi_lineage.sql | mysql -u root -p
Get-Content .\sql\02_init_yubi_auto_eda.sql -Encoding UTF8 | mysql -u root -p

# 餐饮库 yubi_restaurant
Get-Content .\sql\03_init_yubi_restaurant.sql | mysql -u root -p
Get-Content .\sql\05_seed_restaurant.sql | mysql -u root -p

# 风险画像库 yubi_risk
Get-Content .\sql\04_init_yubi_risk.sql | mysql -u root -p

# 可选演示数据
# Get-Content .\sql\06_fixture_restaurant.sql | mysql -u root -p
```

> 所有脚本均使用 `CREATE TABLE IF NOT EXISTS`，重复执行不报错。脚本内含 `CREATE DATABASE`，无需手动建库。

### 5.3 从旧结构迁移（如果你之前有数据）

若你之前所有表在 `yubi` 单库中，先执行 5.1 建新库，再执行清理脚本：

```powershell
cd "<项目根>\src\yubi-backend-master"
Get-Content .\sql\cleanup_old_yubi.sql | mysql -u root -p
```

这会删除 `yubi` 库中已迁出的餐饮表、风险画像表、废弃 post 表。**仅在你确认旧数据不需要保留时执行。**

> 提示：旧数据如需保留，用 `RENAME TABLE yubi.xxx TO yubi_restaurant.xxx`（需同一 MySQL 实例），而不是 DROP。

---

## 六、启动后端（默认端口 18080）

在 **`src/yubi-backend-master`** 目录：

**Windows（Maven Wrapper）：**

```powershell
cd "<项目根>\src\yubi-backend-master"
.\mvnw.cmd spring-boot:run
```

若需固定端口并减少 DevTools 反复重启：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080 --spring.devtools.restart.enabled=false --spring.devtools.livereload.enabled=false"
```

**macOS / Linux：**

```bash
cd "<项目根>/src/yubi-backend-master"
chmod +x mvnw
./mvnw spring-boot:run
```

启动成功后，开发环境接口文档（Knife4j）示例：

```text
http://localhost:18080/api/doc.html
```

---

## 七、启动前端（默认端口 8000）

首次安装依赖：

```powershell
cd "<项目根>\src\yubi-frontend-master"
npm install
```

对接真实后端、关闭 Mock（任选其一，团队常用 **`start:dev`**）：

```powershell
npm run start:dev
```

或：

```powershell
npm run start:no-mock
```

浏览器访问以终端输出为准，一般为 **`http://localhost:8000`**。开发态下 `/api` 会代理到 **`http://127.0.0.1:18080`**；若你修改了后端端口，请同步修改 **`src/yubi-frontend-master/config/proxy.ts`** 中的 `target`。

---

## 八、快速自检清单

1. MySQL 中存在 `yubi`、`yubi_restaurant`、`yubi_risk` 三个库，表数量正确（12 / 10 / 5）。  
2. `application.yml` 中数据库账号与 JDBC URL 正确。  
3. 后端监听 **18080**，能打开 **`/api/doc.html`**（dev 环境）。  
4. 前端 **`npm run start:dev`** 或 **`start:no-mock`** 能打开页面，且接口不报跨域/连接拒绝（代理指向正确）。

---

## 九、子项目文档

- [后端 README — 技术栈、业务模块、Redis 说明等](src/yubi-backend-master/README.md)  
- [前端 README — 路由、构建、代理说明](src/yubi-frontend-master/README.md)
