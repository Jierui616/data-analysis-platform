# 智能数据分析平台 · 后端（yubi-backend）

Spring Boot 后端服务，为「AI 智能分析、经营 BI、数据血缘、风险画像」等能力提供 REST API，并与阿里云百炼（DashScope）对接实现大模型调用。

**本地如何跑起来：** 全栈从零部署请以**项目根目录** [`deploy.md`](../../deploy.md) 为准；下文为后端专项说明（与 `deploy.md` 互补，数据库 SQL 顺序等以根目录 `deploy.md` 为准）。

## 从零部署与启动

按顺序做即可：拿到代码 → 装运行环境 →（可选）中间件 → 改配置 → 建库并执行 SQL → 启动后端；（联调时）再启动前端。

### 1. 获取代码

将本仓库（或包含 `src/yubi-backend-master`、`src/yubi-frontend-master` 的压缩包）放到本机任意目录，下文用 **`<项目根>`** 表示该目录，后端路径为 **`<项目根>/src/yubi-backend-master`**。

### 2. 运行环境

| 组件 | 说明 |
|------|------|
| **JDK 8+** | 配置好 `JAVA_HOME` 与 `PATH`，否则无法使用 `mvnw`。 |
| **MySQL 5.7+ / 8.x** | 必装；库名需与 `application.yml` 里 JDBC URL 一致（默认库名 `yubi`）。 |
| **Maven** | 可选；项目自带 **Maven Wrapper**（`mvnw` / `mvnw.cmd`），未装 Maven 时用 Wrapper 即可。 |
| **Node.js** | 仅在你需要启动前端联调时安装；见文末「与前端联调」。 |

可选中间件（按功能需要；当前工程默认排除了 Redis 自动配置，多数本地开发可先不启 Redis，见后文「Redis 说明」）：

- **Redis**：Session、BI 缓存等（启用前需改 `MainApplication` 并配置 `spring.session.store-type`）。
- **RabbitMQ**：队列相关能力需 `spring.rabbitmq.*` 可连。

### 3.（可选）用 Docker 起 Redis / RabbitMQ

若你本机已创建过名为 `yubi-redis`、`yubi-rabbit` 的容器，每次开机后执行：

```powershell
docker start yubi-redis
docker start yubi-rabbit
```

若还没有容器，可各执行一次（端口与 `application.yml` 默认一致；可按需改映射）：

```powershell
docker run -d --name yubi-redis -p 6379:6379 redis:7-alpine
docker run -d --name yubi-rabbit -p 5672:5672 -p 15672:15672 -e RABBITMQ_DEFAULT_USER=guest -e RABBITMQ_DEFAULT_PASS=guest rabbitmq:3-management-alpine
```

### 4. 修改后端配置

编辑本工程下的 **`src/main/resources/application.yml`**（仓库根路径为 **`src/yubi-backend-master/src/main/resources/application.yml`**）（或用 `application-test.yml` / 环境变量覆盖敏感项）：

- **`spring.datasource`**：`url` / `username` / `password` 指向你的 MySQL。
- **`dashscope.api-key`**：阿里云百炼 API Key（**不要提交真实密钥到公开仓库**；可用本地私有 yml 或环境变量覆盖）。
- **`dashscope.model`**：模型名按控制台文档调整。
- **`risk-portrait.*`**：演示兜底开关；导入真实数据后可改为仅从表读取。
- **`spring.rabbitmq.*`**：若使用消息队列，与 Docker 或本机 RabbitMQ 一致。

默认 **`spring.profiles.active: dev`**，服务端口 **`18080`**，`context-path` 为 **`/api`**。

### 5. 初始化数据库（首次必做）

> 脚本中大量使用 `IF NOT EXISTS` 等语义，**已存在库表时重复执行一般不会破坏已有结构**；仍建议先在测试库验证。

**5.1 建库**（库名与 JDBC 中一致，示例为 `yubi`，字符集 `utf8mb4`）：

```powershell
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS yubi DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

**5.2 在库中按顺序执行 SQL**（请在 **`src/yubi-backend-master`** 目录打开终端，便于使用相对路径；路径含空格时请给文件加引号）。

在 PowerShell 中可先进入目录：

```powershell
cd "<项目根>\src\yubi-backend-master"
```

然后在 **`src/yubi-backend-master`** 目录下执行（PowerShell 用管道导入，避免 `source` 在 `-e` 批处理下的兼容问题）：

```powershell
Get-Content .\sql\create_table.sql | mysql -u root -p yubi
Get-Content .\sql\restaurant_schema.sql | mysql -u root -p yubi
Get-Content .\sql\risk_portrait_schema.sql | mysql -u root -p yubi
```

若使用量贩零食大屏等扩展标签，再执行：

```powershell
Get-Content .\sql\patch_snack_retail_screen_labels.sql | mysql -u root -p yubi
```

也可在 **MySQL Workbench / 命令行** 里依次执行 `source <绝对路径>/sql/xxx.sql`；或在 CMD 中：`mysql -u root -p yubi < sql\create_table.sql`（每条一条命令，顺序同上）。

**顺序不要打乱**：先基础表，再经营分析表，再风险画像相关表。

### 6. 启动后端

在 **`src/yubi-backend-master`** 目录下：

**Windows（推荐 Maven Wrapper）：**

```powershell
.\mvnw.cmd spring-boot:run
```

若希望固定端口并关闭 DevTools 热重启（部分环境下可减少反复重启），可用：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080 --spring.devtools.restart.enabled=false --spring.devtools.livereload.enabled=false"
```

**macOS / Linux：**

```bash
cd "<项目根>/src/yubi-backend-master"
chmod +x mvnw
./mvnw spring-boot:run
```

本机已安装 Maven 且 `mvn` 在 `PATH` 中时，也可使用 `mvn spring-boot:run`。也可在 IDE 中运行主类 **`com.yupi.springbootinit.MainApplication`**。

### 7. 验证与接口文档

启动成功后访问（Knife4j 仅在 `dev` / `test` 下启用）：

```text
http://localhost:18080/api/doc.html
```

### 8. 启动前端（联调）

前端仓库为同级目录 **`src/yubi-frontend-master`**，详见其中 **`README.md`**。常用命令：

```powershell
cd "<项目根>\src\yubi-frontend-master"
npm install
npm run start:no-mock
```

开发态前端默认 **8000** 端口，并把 `/api` 代理到 **`http://127.0.0.1:18080`**；若改后端端口，请同步修改 `src/yubi-frontend-master/config/proxy.ts` 等配置。

---

## 技术栈

| 类别 | 说明 |
|------|------|
| 框架 | Spring Boot 2.7.x、Spring MVC、Spring AOP、Spring Scheduling |
| 持久层 | MyBatis、MyBatis-Plus（分页、逻辑删除） |
| 数据库 | MySQL（主存储） |
| 可选组件 | Redis（Session / BI 缓存）、RabbitMQ、Elasticsearch（模板中保留，默认可按需关闭） |
| 文档 | Swagger 2 + Knife4j（仅在 `dev` / `test` 环境启用） |
| AI | 阿里云百炼 OpenAI 兼容接口（`dashscope.*`） |

## 核心业务（与前端页面对齐）

- **图表与 AI 分析**：自然语言生成图表、异步生成、图表 CRUD、结果查询（`ChartController`）。
- **BI 分析中心**：基于图表数据的经营分析、AI 运行解读、深度多轮对话会话（`BiCenterController` 等）。
- **连锁餐饮经营**：门店/总部视角、成本与排班相关指标、CSV 导入与演示数据（`RestaurantBiController` + `sql/restaurant_schema.sql`）。
- **数据血缘**：血缘追踪、图谱、影响分析与修复任务相关接口（`LineageController`）。
- **风险与信用画像**：画像总览、门店/会员/匿名画像、策略试算、命中留痕；支持演示模式与表数据驱动（`RiskPortraitController` + `risk-portrait.*` 配置）。
- **用户与会话**：登录、当前用户等（`UserController`）；模板中还保留帖子、文件等示例接口，可按需裁剪。
- **OpenAPI 文档**：`dev`/`test` 下 Knife4j 交互式文档（见上文），便于第三方对接与扩展。

## 与前端联调

前端开发默认把 `/api` 代理到 **`http://127.0.0.1:18080`**。若你改用其他端口，请同步修改 `src/yubi-frontend-master/config/proxy.ts` 的 `dev.target`，以及前端的 OpenAPI / `baseURL` 等与后端 origin 相关的配置。

## Redis 说明（可选）

当前 `MainApplication` 使用：

```java
@SpringBootApplication(exclude = {RedisAutoConfiguration.class})
```

需要 Redis 时：配置好 Redis → 去掉上述 `exclude` → 按需打开 `spring.session.store-type: redis`。

## 目录说明（简要）

```text
src/main/java/com/yupi/springbootinit/
  controller/    REST 控制器
  service/       业务逻辑
  mapper/        MyBatis Mapper
  model/         实体与 DTO
src/main/resources/
  application*.yml
  mapper/        XML 映射文件
sql/             初始化与补丁脚本
```

## 许可证与致谢

业务功能面向数据分析与风险画像场景。二次分发请遵守你本地的开源约定与第三方依赖许可。
