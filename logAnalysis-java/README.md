# logAnalysis-java

> **腾讯云 CLS 日志采集与分析系统的 Java 版本**
>
> 历经两期演进：
> - **一期**：从 Python Flask 迁移到 Spring Boot 2.7（82 路由对齐）
> - **二期**：DDD 架构重构 + MyBatis-Plus / Redisson / Hutool 接入 + ClickHouse 双写读路径

[![tests](https://img.shields.io/badge/tests-148%2F148-brightgreen)](src/test/java) [![routes](https://img.shields.io/badge/routes-82%2F82-brightgreen)](src/main/java/com/loganalysis) [![java](https://img.shields.io/badge/java-11-orange)](https://adoptium.net/) [![spring--boot](https://img.shields.io/badge/spring--boot-2.7.18-green)](https://spring.io/projects/spring-boot) [![arch](https://img.shields.io/badge/arch-DDD-blue)](#架构)

---

## 这是什么

从腾讯云 CLS 拉日志、转换、聚合、入库、告警推送的后端服务。二期后定位为**支持 OLAP 规模**的日志分析平台：MySQL 承载 OLTP + 配置，ClickHouse 承载 OLAP 聚合查询，Processor 异步双写。

- ✅ **82 个 HTTP 路由完整保留**（100% 向后兼容）
- ✅ **148 条单元/集成测试全绿**
- ✅ **DDD 四层分包**（interfaces / application / domain / infrastructure）
- ✅ **ClickHouse 可选启用**（特性开关，不影响默认 MySQL 路径）

## 技术栈

| 层 | 组件 | 版本 | 备注 |
|---|---|---|---|
| 语言 / 运行时 | JDK 11 (Eclipse Temurin) | 11.0.24 | |
| 框架 | Spring Boot | 2.7.18 | |
| OLTP 持久化 | **MyBatis-Plus** + MySQL Connector/J | 3.5.7 + 8.0.33 | 覆盖 5 配置表 + 5 业务表 |
| OLAP 持久化（可选） | **ClickHouse JDBC** | 0.6.0 | 条件装配，`enabled=false` 默认 |
| 动态 DDL | Spring JdbcTemplate | — | `TableMappingService` 专用保留 |
| 缓存 / 分布式锁 | **Redisson** | 3.27.2 | 替换 Spring Data Redis |
| 工具类 | **Hutool** + Guava | 5.8.27 + 32.1.3 | 按需使用 |
| 第三方 SDK | tencentcloud-sdk-java-cls | 3.1.1451 | |
| 截图 | Playwright for Java | 1.40.0 | |
| 构建 | Maven | 3.9.x | 阿里云镜像 |
| 测试 | JUnit 5 + MockMvc + Mockito + AssertJ | — | 148 断言 |

## 架构

### DDD 分层

```
src/main/java/com/loganalysis/
├── LogAnalysisApplication.java
│
├── common/                   跨领域共享
│   ├── config/               MybatisPlusConfig, ClickHouseConfig, CorsConfig
│   ├── exception/            GlobalExceptionHandler
│   └── util/                 CryptoUtil, JsonUtil, TransformUtils, FilterEvaluator
│
├── credential/               子域：腾讯云凭证（Fernet 加密）
│   ├── application/          CredentialService（MP）
│   ├── infrastructure/
│   │   └── persistence/      CredentialPO + CredentialMapper
│   └── interfaces/rest/      CredentialController
│
├── topic/                    子域：CLS Topic 配置
├── queryconfig/              子域：查询模板 + 定时调度配置
├── tablemapping/             子域：动态表映射（DDL 白名单）
│   ├── application/          TableMappingService（保留 JdbcTemplate，MP 无法表达动态 DDL）
│   └── infrastructure/
│       └── persistence/      TopicTableMappingReadMapper + FieldMappingReadMapper（跨域共享）
│
├── hitch/                    子域：5 个业务日志处理器
│   ├── application/          5 个 Processor + DataProcessorRouter/Service
│   └── infrastructure/
│       ├── cache/            RedisCacheService（Redisson）
│       ├── persistence/      5 个 PO + 5 个 Mapper
│       └── writeback/        ChDualWriter + ChWritebackRunner（CH 异步双写 + 补偿）
│
├── dashboard/                子域：聚合查询（读路径，P3 性能优化战场）
│   ├── application/          DashboardService
│   ├── infrastructure/       DashboardQueryExecutor 接口
│   │                         + MysqlQueryExecutor / ClickHouseQueryExecutor 两实现
│   └── interfaces/rest/      DashboardController + PageController
│
├── search/                   子域：CLS 查询 + 日志分发
│   ├── application/
│   ├── infrastructure/
│   │   ├── ClsQueryService   封装腾讯云 SDK
│   │   ├── InsertRecordService + LogRecordPO/Mapper
│   │   └── permission/       ClsPermissionAnalyzer
│   └── interfaces/rest/      SearchLogsController + PermissionController
│
├── report/                   子域：日报/推送/截图
├── scheduler/                子域：定时调度
└── health/                   子域：健康检查
```

### 存储分层

```
                    ┌─────────────────┐
                    │  CLS Java SDK   │
                    └────────┬────────┘
                             │
                 ┌───────────▼───────────┐
                 │  SearchLogsController │
                 └───────────┬───────────┘
                             │
                 ┌───────────▼────────────┐
                 │  DataProcessorRouter   │
                 └───────────┬────────────┘
                             │分发到 5 Processor
       ┌─────────────────────┼─────────────────────────┐
       │                     │                         │
   ┌───▼──────┐       ┌──────▼───────┐        ┌────────▼──────┐
   │ Redis    │       │ MySQL        │        │ ClickHouse    │
   │ 聚合缓存 │       │ 主库（权威） │  异步  │ OLAP 镜像     │
   │ Redisson │       │ 业务表 + 配置│  双写  │（可选启用）   │
   └──────────┘       └──────────────┘        └───────────────┘
                             │                         │
                             │MySQL 写失败 → 补偿 ←───┘CH 写失败入队
                             │
                      ┌──────▼─────────┐
                      │ ch_writeback_queue │
                      │ + ChWritebackRunner │
                      │   @Scheduled 重放   │
                      └───────────────────┘
```

**读路径**（Dashboard 聚合查询）：
- `read-source=mysql`（默认）：走 MySQL 原 SQL
- `read-source=clickhouse`：先查 CH；CH 失败时**静默降级**到 MySQL（自动回退，前端无感）

**写路径**（5 Processor）：
- 第一步：写 MySQL（权威源，失败抛异常终止）
- 第二步：若 `dual-write=true`，异步投递写 CH；CH 失败进补偿队列，定时重放

## 5 分钟跑起来

```bash
# 前置：JDK 11 + MySQL 8 + Redis 5
source /data/home/lemolli/.local/opt/envrc

# 1. 克隆 + 构建
cd logAnalysis-java
mvn -DskipTests package                # → target/loganalysis.jar

# 2. 迁移密钥（如果要和 Python 版共用凭证库）
bash start_java.sh migrate-key

# 3. 初始化 MySQL 库（全新库）
bash start_java.sh init-db

# 4. 启动（默认 ClickHouse 未启用，行为同一期）
export ENCRYPTION_FORBID_AUTO_GEN=true
bash start_java.sh start

# 5. 验证
curl http://localhost:8080/api/health
# → {"status":"healthy","checks":{"database":"ok","redis":"ok"}}
```

### 启用 ClickHouse（可选）

```bash
# 1. 部署 ClickHouse 实例（单机起步，≥ 4C8G）

# 2. 执行 CH DDL
clickhouse-client --multiquery < src/main/resources/schema/clickhouse/00_database.sql
for f in src/main/resources/schema/clickhouse/*.sql; do
  [ "$(basename $f)" != "00_database.sql" ] && clickhouse-client --multiquery < "$f"
done

# 3. 一次性迁移历史数据
MYSQL_HOST=... MYSQL_PASSWORD=... \
CLICKHOUSE_HOST=... CLICKHOUSE_PASSWORD=... \
bash tools/migrate_mysql_to_ch.sh

# 4. 开 CH 双写（先保持 read-source=mysql 积累 CH 数据）
export CLICKHOUSE_ENABLED=true
export CLICKHOUSE_READ_SOURCE=mysql       # 此时 CH 只写不读
export CLICKHOUSE_DUAL_WRITE=true
export CLICKHOUSE_URL=jdbc:clickhouse://127.0.0.1:8123/cls_logs_ch
bash start_java.sh restart

# 5. 观察 1-7 天，CH 和 MySQL 数据一致后切流
export CLICKHOUSE_READ_SOURCE=clickhouse  # Dashboard 查询走 CH
bash start_java.sh restart
```

## 核心接口（82 个路由，保持一期兼容）

| Controller | 前缀 | 路由数 | 主要职责 |
|---|---|---|---|
| `HealthController` | `/api/health` | 1 | 健康检查（DB / Redis） |
| `CredentialController` | `/api/credentials` | 5 | 腾讯云凭证 CRUD（Fernet 加密存储） |
| `TopicController` | `/api/topics` | 4 | CLS Topic 配置 CRUD |
| `QueryConfigController` | `/api/query-configs` | 4 | 查询模板 CRUD |
| `DashboardController` | `/api/dashboard/*` | 12 | 5 张业务表的 statistics + aggregation + 概览 |
| `TableMappingController` | `/api/table-mappings/*` | 15 | 动态建表映射（含 SQL 注入防护白名单） |
| `GwHitchController` | `/api/gw-hitch/*` | 9 | 网关顺风车错误聚合处理器 |
| `ControlHitchController` | `/api/control-hitch/*` | 9 | 控制层顺风车错误聚合处理器 |
| `SearchLogsController` | `/api/search-logs` 等 | 5 | **核心**：CLS 查询 + 分发到专用处理器 |
| `ReportController` | `/api/report/*` | 10 | 日报汇总 + 企微推送 + 截图 + HTML 导出 |
| `PermissionController` | `/api/permission/*` | 2 | CLS 权限错误分析 + 验证 |
| `SchedulerController` | `/api/scheduler/*` | 3 | 调度器状态 + 手动触发 |

## 二期特性

### 1. DDD 分领域组织（10 个子域）

见[架构](#架构)章节。每个领域的 `application/domain/infrastructure/interfaces` 目录职责清晰：
- `interfaces` 层依赖 `application`
- `application` 层依赖 `domain` + `infrastructure`
- `domain` 层**禁止外部依赖**（纯 POJO + 接口）
- 领域之间**禁止**直接访问彼此的 `infrastructure`，必须走 `application` 服务

### 2. MyBatis-Plus 接入（配置类 + 业务表）

- 22 个新增 PO+Mapper 覆盖 10 张表
- 保留 JdbcTemplate 的场景（明确决策）：
  - `TableMappingService` 的动态 DDL（建表/加列/DESCRIBE）
  - `HealthController` 的 `SELECT 1`
  - Dashboard 的聚合查询（由 `DashboardQueryExecutor` 接口间接抽象）

### 3. Redisson 取代 Spring Data Redis

- `RedisCacheService` 用 `RBucket<String>` 重写
- 保留 `isAvailable()` 降级语义：Redis 挂 → 5 Processor 退回"每次查 MySQL"
- 预留分布式锁扩展点（当前单实例部署未使用）

### 4. Hutool 按需使用

- `WecomPushService` 的 HTTP 调用 (`HttpUtil`) 和 MD5 签名 (`SecureUtil`)
- **不批量替换** commons-lang3（按"简洁优先"原则）

### 5. ClickHouse 异步双写 + 读路径降级

- 写：`ChDualWriter` 投递到 `clickHouseAsyncExecutor` 线程池；CH 失败入 `ch_writeback_queue` 补偿表
- 补偿：`ChWritebackRunner` @Scheduled 每 30s 重放，指数退避（最长 1h），积压超阈值告警
- 读：`DashboardQueryExecutor` 接口 + 两个实现（MySQL / ClickHouse）按 `read-source` 自动切换
- 降级：CH 查询失败 → 静默回退到 MySQL（前端无感）

### 6. 自定义 Transform DSL（一期遗留，依然对齐 Python 版）

```
regex:/[^ ]+:0                                  # 正则 group 0
json_path:resData.code|fallback:errCode         # JSON 路径 + 备选字段
default_if_timeout:system_error                 # 超时检测默认值
datetime_parse                                  # 去掉毫秒
substr:10240                                    # 截取
```

实现见 `common/util/TransformUtils.java`（22 条断言验证）。

### 7. Fernet 加密兼容（一期遗留）

API 凭证用 `cryptography.Fernet` 格式加密存库。Java 侧用 `CryptoUtil` 实现同格式（AES-128-CBC + HMAC-SHA256）。

**关键保护**：`loganalysis.encryption.forbid-auto-generate=true` 防止 Java 启动时静默生成新密钥。

### 8. 动态 DDL 的 SQL 注入防护（一期遗留）

`TableMappingService` 支持运行时建表、加列、动态 order by，三层白名单校验（`validateTableName` / `validateColumnName` / `normalizeColumnType`）。

### 9. 定时调度（@Scheduled）

每 10 秒轮询：
- `query_configs.schedule_enabled=1` 的配置按 `schedule_interval` 触发
- `report_push_config.schedule_enabled=1` 的配置按 `schedule_time` (HH:MM) 日触发
- ClickHouse 启用时，`ChWritebackRunner` 每 30s 重放补偿队列

## 配置项速查

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_HOST/PORT/USER/PASSWORD/DATABASE` | localhost/3306/root/123456/cls_logs | MySQL 连接 |
| `REDIS_HOST/PORT/PASSWORD/DB` | 127.0.0.1/6379//0 | Redis（Redisson 自动读取） |
| `ENCRYPTION_KEY_FILE` | `.encryption_key` | Fernet 密钥文件 |
| `ENCRYPTION_FORBID_AUTO_GEN` | false | **生产必设为 true** |
| `CLICKHOUSE_ENABLED` | **false** | 总开关 |
| `CLICKHOUSE_READ_SOURCE` | `mysql` | `clickhouse` 切到 CH 读 |
| `CLICKHOUSE_DUAL_WRITE` | `true` | `enabled=true` 时仍可独立关 |
| `CLICKHOUSE_URL` | `jdbc:clickhouse://127.0.0.1:8123/cls_logs_ch` | CH JDBC URL |
| `CLICKHOUSE_USER/PASSWORD` | default/空 | CH 凭证 |

### 特性开关组合

| 场景 | enabled | read-source | dual-write | 行为 |
|---|---|---|---|---|
| 默认 | false | (忽略) | (忽略) | 行为 100% 等同一期 |
| 灰度写入 | true | mysql | true | CH 只写不读，用于积累数据 |
| 切流 CH | true | clickhouse | true | CH 读写；CH 读失败静默降级 |
| 只读 CH | true | clickhouse | false | CH 读；MySQL 单写（风险：CH 只读最新数据） |

## 目录结构

```
logAnalysis-java/
├── README.md                          ← 你在这里
├── RUNBOOK.md                         运维手册（生产上线完整流程，含 CH 灰度步骤）
├── MIGRATION_PLAN.md                  迁移历程与决策（一期 + 二期）
├── REFACTOR_PLAN.md                   二期重构方案（评审版 + 执行状态）
├── pom.xml                            Maven 构建
├── Dockerfile + .dockerignore         容器化（未实测）
├── start_java.sh                      服务管理（start/stop/migrate-key/init-db）
│
├── src/main/java/com/loganalysis/     参见前文 DDD 分层
│
├── src/main/resources/
│   ├── application.yml                Spring 配置（含 loganalysis.clickhouse.* 节点）
│   ├── logback-spring.xml
│   └── schema/
│       ├── 00_core_tables.sql         MySQL 核心配置表
│       ├── *.sql                      MySQL 业务表
│       ├── ch_writeback_queue.sql     CH 双写补偿队列（MySQL 主库）
│       └── clickhouse/                CH 建表 DDL（6 个文件）
│
├── src/test/java/                     148 条断言，按领域分包
│
├── tools/
│   ├── diff_py_vs_java.py             Python vs Java 行为对比
│   ├── smoke_test.sh                  一键启停+33 路由探活
│   ├── migrate_mysql_to_ch.sh         MySQL → CH 历史数据迁移
│   └── DIFF_REPORT.md                 差异分析报告
│
├── docker/
│   ├── maven-settings.xml
│   └── build_and_test.sh              一键构建镜像+启容器+探活
│
└── .github/workflows/ci.yml           GitHub Actions CI 模板
```

## 测试 / 开发

```bash
# 跑所有单元测试（148 条）
mvn test

# 只跑某类测试
mvn test -Dtest='com.loganalysis.common.util.*'

# 只跑某个领域
mvn test -Dtest='com.loganalysis.hitch.*'

# 重新打包
mvn -DskipTests package

# 本地开发（改代码后 restart）
bash start_java.sh restart

# 一键烟雾测试（33 只读路由探活）
bash tools/smoke_test.sh
```

## 未验证假设（按"零容忍"规则透明声明）

以下项在**本地开发环境无法验证**，需用户在生产或有真实依赖的环境验证：

| 项 | 风险 | 验证方式 |
|---|---|---|
| ClickHouse JDBC 0.6.0 + JDK 11 运行期稳定 | 中 | 生产连 CH 启动冒烟 + `/api/dashboard/overview` 走 CH 路径 |
| `ReplacingMergeTree` UPSERT 语义与 MySQL 一致（SUM total_count 正确） | 中 | 灌历史数据后对比 CH 和 MySQL 的 `SUM(total_count) GROUP BY ...` |
| 生产 5 张表数据量 / 日增 / 慢接口 P99（P0 诊断） | 高 | 生产查询（参见 REFACTOR_PLAN §2.3） |
| CH 查询实际 P99 ≤ 2s 目标 | 高 | 真实 CH 实例压测 |
| MySQL 主库承担 `ch_writeback_queue` 额外写入 | 低 | 压测对比 |
| `Dockerfile` 实际 build 和运行 | 中 | 在有 docker 的环境跑 `bash docker/build_and_test.sh` |
| Playwright Chromium 首次下载 | 低 | 首次调 `/api/report/screenshot` 会下载 ~300MB |

## 安全

- ✅ Fernet 加密兼容（历史密文可解）
- ✅ 动态 DDL 白名单（防 SQL 注入，测试过 `"TEXT; DROP TABLE x"` 被拦）
- ✅ HMAC 常量时间比较（`MessageDigest.isEqual` 防时序攻击）
- ✅ 所有 SQL 使用参数化（MP / `@Select` / JdbcTemplate 全部参数化）
- ✅ 动态 ORDER BY 列名白名单（`@Select("ORDER BY ${orderBy}")` 上层强校验）
- ✅ `.gitignore` 拦截 `.encryption_key` / `*.secret.yml`

## 上线流程

推荐按 [RUNBOOK.md](RUNBOOK.md) 的 6 阶段流程：
1. **部署准备**（密钥迁移）
2. **灰度启动**（新版在 8081，老版在 8080 并跑）
3. **灰度对比验证**（每小时 cron diff）
4. **切流到新版**（Nginx weight 或直切端口）
5. **启用 ClickHouse**（可选，见 [5 分钟跑起来 → 启用 ClickHouse](#启用-clickhouse可选)）
6. **systemd unit**（持久化）

**回滚预案**：
- 整体回滚：Nginx weight 一改即回 Python，或 `systemctl start loganalysis-py`
- 仅回滚 ClickHouse：`CLICKHOUSE_READ_SOURCE=mysql` + `DUAL_WRITE=false` 一键降级

## 文档导航

| 文档 | 给谁看 | 读多久 |
|---|---|---|
| `README.md` | 初次接触项目的人 | 10 分钟 |
| `RUNBOOK.md` | 运维 / 上线执行人 | 20 分钟，含完整清单 |
| `REFACTOR_PLAN.md` | 维护 / 二开 / 评审 | 15 分钟，二期方案决策记录 |
| `MIGRATION_PLAN.md` | 背景了解 | 10 分钟，一期 Python → Java 迁移历程 |
| `tools/DIFF_REPORT.md` | 产品 / 前端 | 5 分钟，与 Python 版差异清单 |
| `src/main/resources/schema/README.md` | 运维 DBA | 5 分钟 |

## 许可

内部项目。

---

**常见问题**：
- 运维：看 `RUNBOOK.md#故障排查`
- 与 Python 版行为差异：看 `tools/DIFF_REPORT.md`
- 二期重构决策：看 `REFACTOR_PLAN.md`
- 一期迁移历史：看 `MIGRATION_PLAN.md`
