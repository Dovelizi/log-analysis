# log-analysis

> **腾讯云 CLS 日志采集与分析系统** · 包含 Python（线上运行版）与 Java（迁移新版）两份实现

本仓库是一个**同源双实现**的日志分析系统合集：同样的业务逻辑、同样的数据库、同样的 HTTP API，分别用 Python 和 Java 各实现一遍。Python 版是当前生产线上跑的版本，Java 版是按 100% 功能对齐原则重写的新版，可在做充分并跑对比后替换上线。

```
log-analysis/
├── logAnalysis-python/    Flask 版本（生产线上运行中）
├── logAnalysis-java/      Spring Boot 版本（迁移新版）
└── README.md              本文件
```

---

## 两个子项目的关系

| 维度 | logAnalysis-python | logAnalysis-java |
|---|---|---|
| 角色 | **生产**实现 | **迁移新版**（对齐 Python 82 个 HTTP 路由） |
| 语言 / 运行时 | Python 3 | JDK 11 |
| Web 框架 | Flask 3.x | Spring Boot 2.7.18 |
| 默认端口 | 8080 | 8080（同端口，二选一启动） |
| 数据库 / 缓存 | **共用同一个 MySQL `cls_logs` + 同一个 Redis** | 同左 |
| Fernet 加密密钥 | `.encryption_key`（不入库） | 同一把密钥，直接复用 |
| 静态前端 | `static/index.html` | 同一份 `index.html`，已随 Java 版一起打包 |
| 调度器 | 内部 HTTP 自调用 | `@Scheduled` 直接调 Service（更轻量） |
| 构建产物 | `.py` 源码直接运行 | 打成 fat jar（`target/loganalysis.jar`，不入库） |

**关键设计**：两版共享同一份 `.encryption_key` 和同一个 DB schema，**可以同时跑在同一台机器的不同端口做对比验证**，不用双写数据。

---

## 业务用途

服务对接**腾讯云 CLS（Cloud Log Service）**，做以下事情：

1. **日志采集**：按配置（`topic_id` / 时间窗口 / 查询语句）调 CLS `SearchLog` 拉日志
2. **转换聚合**：按自定义 DSL（regex / json_path / fallback / datetime_parse / ...）把原始日志解析、过滤、按维度聚合
3. **结构化入库**：写入 MySQL 各业务表（`gw_hitch_error_mothod` / `control_hitch_error_mothod` / `hitch_supplier_error_sp` / ...）
4. **看板 & 报表**：前端页面查询统计、TOP 排名、耗时分布
5. **定时推送**：按配置日报推送到企业微信（Markdown 或 Playwright 截图）
6. **调度器**：轮询配置表，自动触发周期性采集与推送

---

## 功能模块对照

两版**模块结构是一一对应**的，只是落到不同的目录形式：

| 业务模块 | Python 位置 | Java 位置 |
|---|---|---|
| HTTP 路由（8 组） | `routes/*.py` 蓝图 | `src/main/java/.../controller/*.java` |
| 凭证管理（加密存储） | `routes/credentials` + Fernet | `CredentialController` + `CryptoUtil` |
| 主题管理 | `routes/topics` | `TopicController` |
| 查询配置 | `routes/query_configs` | `QueryConfigController` |
| 看板查询 | `routes/dashboard_routes.py` | `DashboardController` |
| 表映射（动态建表） | `routes/table_mapping_routes.py` | `TableMappingController` |
| GW 顺风车处理器 | `services/gw_hitch_processor.py` | `GwHitchProcessor` |
| Control 顺风车处理器 | `services/control_hitch_processor.py` | `ControlHitchProcessor` |
| 供应商错误（SP + Total） | `services/hitch_supplier_error_*.py` | `HitchSupplierError*Processor` |
| 耗时分析 | `services/hitch_control_cost_time_processor.py` | `HitchControlCostTimeProcessor` |
| 通用数据处理 | `services/data_processor.py` | `DataProcessorService` + `DataProcessorRouter` |
| 报表汇总 | `routes/report_routes.py` summary | `ReportSummaryService` |
| 企业微信推送 | `routes/report_routes.py` push | `WecomPushService` + `ReportPushService` |
| 截图（Playwright） | `services/screenshot_service.py` | `ScreenshotService` |
| 权限检查 | `app.py` CLSPermissionAnalyzer | `ClsPermissionAnalyzer` |
| 调度器 | `services/scheduler.py` | `ScheduledQueryRunner` |
| 转换 DSL | `services/transform_utils.py` | `TransformUtils` + `FilterEvaluator` |
| CLS 查询 | Python `requests` + TC3 手搓签名 | `ClsQueryService` + 腾讯云官方 SDK |

---

## 技术栈

### Python 版

| 层 | 组件 |
|---|---|
| 运行时 | Python 3.9+ |
| Web 框架 | Flask 3.x |
| 数据访问 | PyMySQL（原生 cursor） |
| 缓存 | redis-py |
| CLS SDK | 自行用 `requests` + TC3 签名实现 |
| 加密 | `cryptography.Fernet` |
| 调度 | 自写轮询 + HTTP 自调用 |
| 截图 | Playwright Python |
| 前端 | 单文件 `static/index.html`（纯 HTML + JS，无构建） |

启动入口：`python3 app.py`（内置 Flask dev server）或 `gunicorn -c deploy/gunicorn.docker.conf.py app:app`

### Java 版

| 层 | 组件 |
|---|---|
| 运行时 | JDK 11 (Eclipse Temurin) |
| 框架 | Spring Boot 2.7.18 |
| 数据访问 | Spring JdbcTemplate + HikariCP |
| 缓存 | Spring Data Redis (Lettuce) |
| CLS SDK | `tencentcloud-sdk-java-cls:3.1.1451` 官方 SDK |
| 加密 | `javax.crypto`（与 Fernet token 格式兼容） |
| 调度 | `@Scheduled` 直接调 Service |
| 截图 | Playwright for Java 1.40.0 |
| 构建 | Maven 3.9.x |
| 测试 | JUnit 5 + MockMvc + Mockito + AssertJ |

启动入口：`bash start_java.sh start`（start/stop/restart/status/init-db/migrate-key/logs/clean 子命令）

---

## 数据层共用

两版读写**完全相同的** MySQL 表集合：

```
核心配置表
  api_credentials          腾讯云凭证（Fernet 加密存储）
  log_topics               CLS topic 配置
  query_configs            查询 + 处理器配置 + 调度开关
  topic_table_mappings     topic 到业务表的映射 + transform 配置
  field_mappings           字段级转换规则

业务数据表
  gw_hitch_error_mothod                网关顺风车错误聚合
  control_hitch_error_mothod           顺风车控制错误聚合
  hitch_supplier_error_sp              供应商 SP 错误（四元组聚合）
  hitch_supplier_error_total           供应商合计错误
  hitch_control_cost_time              耗时明细（无聚合）

运营表
  log_records               采集原始日志
  analysis_results          分析结果
  collection_logs           采集执行记录
  record_insert_log         入库审计
  report_push_config        推送配置
  report_push_log           推送历史
```

Java 版的 DDL 脚本在 `logAnalysis-java/src/main/resources/schema/`（`00_core_tables.sql` 优先执行，其他业务表按需）。

---

## 快速开始

### 二选一启动

```bash
# 方式 A：跑 Python 版
cd logAnalysis-python
pip install -r requirements.txt
python3 app.py         # → http://127.0.0.1:8080

# 方式 B：跑 Java 版
cd logAnalysis-java
# 首次需要有 JDK 11 + Maven + .encryption_key
mvn -DskipTests package
bash start_java.sh migrate-key ../logAnalysis-python/.encryption_key  # 复用 Python 的密钥
bash start_java.sh start                                              # → http://127.0.0.1:8080
```

### 并跑对比（推荐在上线前做一次）

把 Java 版改成 `--server.port=8081`，和 Python 版并跑，用对比工具看行为差异：

```bash
cd logAnalysis-java
python3 tools/diff_py_vs_java.py \
  --python-url http://127.0.0.1:8080 \
  --java-url   http://127.0.0.1:8081
# 预期输出：29 接口 → 19 PASS / 10 DIFF / 0 FAIL
# 剩余 10 个 DIFF 是已知可接受差异，详见 tools/DIFF_REPORT.md
```

---

## 文档导航

| 想看什么 | 文档 |
|---|---|
| Java 版功能 / 技术栈 / 快速开始 | `logAnalysis-java/README.md` |
| Java 版生产上线运维手册 | `logAnalysis-java/RUNBOOK.md` |
| 迁移的每一步决策过程 | `logAnalysis-java/MIGRATION_PLAN.md` |
| Python vs Java 接口差异逐项分析 | `logAnalysis-java/tools/DIFF_REPORT.md` |
| Python 版部署指引 | `logAnalysis-python/deploy/DEPLOY.md` |
| 日志规范 / 分页规范 | `logAnalysis-python/docs/*.md` |

---

## 🔴 安全注意

**绝不能提交到本仓库**：

- `.encryption_key` — Fernet 密钥（两个子项目都已在 `.gitignore` 中）
- `.env` — 环境变量（含 DB/Redis 密码）
- `deploy/.env` — 部署密码
- `*.log` / `logs/` — 可能含 SQL 语句和业务数据
- `target/*.jar` — 体积大的构建产物
- `__pycache__/` / `*.pyc` — 构建缓存

两个子项目的 `.gitignore` 均已配置完整。若要新增文件请先 `git check-ignore <file>` 验证。

---

## 项目状态

- **Python 版**：生产线上运行中
- **Java 版**：
  - 功能对齐 100%（82/82 路由）
  - 测试 148/148 PASS
  - 真实 diff 19/29 PASS（10 个已知差异、0 失败）
  - 端到端烟雾测试 33/33 PASS
  - **可进入灰度上线阶段**

详见 `logAnalysis-java/MIGRATION_PLAN.md` 和 `logAnalysis-java/tools/DIFF_REPORT.md`。
