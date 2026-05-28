# yubi — 智能数据分析平台

## Stack

- **Backend**: Java 8, Spring Boot 2.7, MyBatis-Plus 3.5, MySQL
- **Frontend**: React 18, Ant Design 5, Umi Max 4, ECharts 5
- **Optional infra**: Redis / RabbitMQ / ClickHouse / Kafka / MinIO
- **AI**: Alibaba DashScope (百炼) for chart generation via LLM
- **Build**: Maven (Maven Wrapper) + npm/pnpm

## Layout

| Path | Purpose |
|------|---------|
| `src/yubi-backend-master/` | Spring Boot backend — REST API, SQL init scripts, Knife4j docs |
| `src/yubi-frontend-master/` | React SPA — pages under `src/pages/`, services under `src/services/` |
| `data/` | Sample CSV data for import/testing |
| `docker-compose.bigdata.yml` | Optional big-data stack (ClickHouse + Kafka + MinIO) |
| `deploy.md` | Source-of-truth deployment guide |

## Commands

### Backend (`src/yubi-backend-master/`)

| Command | Action |
|---------|--------|
| `./mvnw.cmd spring-boot:run` (Windows) / `./mvnw spring-boot:run` (macOS/Linux) | Start backend on **port 18080**, context `/api` |
| Profile: `--spring.profiles.active=dev,bigdata-local` | Enables ClickHouse/Kafka/MinIO integration |

### Frontend (`src/yubi-frontend-master/`)

| Command | Action |
|---------|--------|
| `npm install` | Install deps (postinstall runs `max setup`) |
| `npm run start:dev` | Dev server **port 8000**, proxy `/api` → `127.0.0.1:18080` |
| `npm run start:no-mock` | Dev server without mock data |
| `npm run build` | Production build into `dist/` |
| `npm run lint` | ESLint + Prettier + TypeScript check |
| `npm run test` / `jest` | Jest test runner |
| `npm run tsc` | TypeScript type-check (`tsc --noEmit`) |
| `npm run prettier` | Format all with Prettier |

## Conventions

- **TypeScript**: strict mode, path alias `@/` → `src/`, `@@/` → `src/.umi/`
- **Code style**: Prettier — single quotes, trailing commas, printWidth 100, LF line endings
- **Lint**: ESLint via `@umijs/lint` config; lint-staged runs on pre-commit (Husky)
- **Frontend routes**: Ant Design Pro convention — one dir per route under `src/pages/`
- **API services**: auto-generated/colocated in `src/services/yubi/` from OpenAPI spec
- **Backend**: Lombok, AOP, MyBatis-Plus mapper+service pattern; devtools enabled

## Watch out for

- **`.umi/` and `.umi-production/` are generated** by Umi — never edit by hand. Delete them if paths break after moving the project.
- **SQL import order matters** — run `create_table.sql` → `restaurant_schema.sql` → `risk_portrait_schema.sql` in sequence (see `deploy.md` §5.2).
- **Backend Redis auto-config is excluded** by default — enabling it requires changes to `MainApplication` + `spring.session.store-type`.
- **`application.yml` contains real keys** (DashScope API key, DB credentials) — never commit to public repos. Use local overrides or env vars.
