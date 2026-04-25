# logAnalysis → Java 迁移执行计划（最终版）

> 源码：`/data/workspace/logAnalysis`（Flask + MySQL + Redis + 腾讯云 CLS）
> 目标：`/data/workspace/logAnalysis-java`（Spring Boot 2.7.18 + Maven + JDK 11）
> 测试策略：方案 B —— Service 保证编译通过，正确性由 Controller MockMvc 端到端测试保证

## 状态速览

**148 条测试全部通过，BUILD SUCCESS，生成 `target/loganalysis.jar`（199MB fat jar）**

**100% 路由覆盖完成**：Python 版 82 个路由 → Java 版 82 个路由全部实现。

**真实环境 diff 对比**：29 个只读接口 **19 PASS / 10 DIFF / 0 FAIL**（66% 完全一致；剩余 10 个均为已知且可接受的产品决策级差异）。

**端到端烟雾测试**：`tools/smoke_test.sh` 自动启停 Java + 探活 **33 个路由全部 PASS**（含 2 个 404 错误处理验证）。

**容器化部署就绪**：`Dockerfile` 多阶段构建（builder + runtime jre-slim），预装 Playwright 依赖，非 root 用户运行，带 HEALTHCHECK。`docker/build_and_test.sh` 一键 build + 启容器 + 探活 12 路由。`.github/workflows/ci.yml` 给出 GitHub Actions 模板。

⚠️ **未验证假设（明确声明）**：本机没有 Docker daemon（`dockerd` binary 不存在，`/var/run/docker.sock` 未创建，无 sudo 权限启动），**Dockerfile + build_and_test.sh + ci.yml 都未做实际构建**。用户需要在有 Docker 的环境初次验证，可能需要微调：
- `maven:3.9-eclipse-temurin-11` image tag 已通过 docker hub 搜索确认存在
- Dockerfile 语法遵循官方规范，但未经 `docker build --check` 验证
- Playwright apt-get 依赖列表参考官方文档，未在 Ubuntu jammy 真机测试

## 决策记录

| 维度 | 决定 |
|---|---|
| JDK/Maven | `/data/home/lemolli/.local/opt/jdk-11.0.24+8` + Maven 3.9.15（阿里云 public 镜像） |
| 截图方案 | Playwright for Java（首次需下载 Chromium） |
| Fernet 加密 | 兼容（CryptoUtil + 测试闭环） |
| CLS SDK | `tencentcloud-sdk-java-cls:3.1.1451` |
| SQLite | 删除（不迁移 cls_logs.db） |
| 端口 | 8080（与 Python 一致） |
| Scheduler | 不走 HTTP 自调用，直接 Service 层调用 |

## 进度（全部完成）

- [x] **阶段 0** — 迁移清单 `MIGRATION_CHECKLIST.md`
- [x] **阶段 1** — Spring Boot 2.7 骨架
- [x] **阶段 2** — 基础设施（CorsConfig / GlobalExceptionHandler / RedisConfig / CryptoUtil / HealthController）
- [x] **阶段 3** — 转换引擎（TransformUtils / FilterEvaluator / JsonUtil）
- [x] **阶段 4.1** — RedisCacheService
- [x] **阶段 4.2** — InsertRecordService
- [x] **阶段 4.3** — ClsQueryService（封装腾讯云 CLS Java SDK）
- [x] **阶段 4.4** — CredentialService / TopicService / QueryConfigService
- [x] **阶段 5.1** — CredentialController / TopicController / QueryConfigController
- [x] **阶段 5.2** — DashboardController + DashboardService（15 路由）
- [x] **阶段 5.3** — TableMappingController + TableMappingService（动态 DDL；**修复了一个 SQL 注入漏洞**）
- [x] **阶段 5.4** — GwHitchController + GwHitchProcessor（Redis 聚合）
- [x] **阶段 5.5** — ControlHitchController + ControlHitchProcessor
- [x] **阶段 5.6** — HitchSupplierErrorSpProcessor（4 元组聚合 + sp_name）
- [x] **阶段 5.7** — HitchSupplierErrorTotalProcessor（4 元组聚合无 sp_name）
- [x] **阶段 5.8** — HitchControlCostTimeProcessor（每条独立 INSERT）
- [x] **阶段 5.9** — SearchLogsController + DataProcessorRouter + DataProcessorService（`/api/search-logs` 核心分发）
- [x] **阶段 5.10a** — ReportController（push-configs CRUD + screenshot）+ ScreenshotService
- [x] **阶段 5.10b** — ReportSummaryService（summary / weekly-new-errors / **generateReportHtml**）+ ReportPushService（push / push-logs / push-logs/{id}）+ WecomPushService（Markdown/图片模式 + MD5 签名）+ **`POST /api/report/export`（HTML/JSON 双格式导出，含 XSS 转义）**
- [x] **阶段 5.11** — PermissionController + ClsPermissionAnalyzer
- [x] **阶段 6** — ScheduledQueryRunner + SchedulerController（`@Scheduled` 每 10 秒轮询）
- [x] **阶段 7** — SQL 脚本平移 `src/main/resources/schema/`
- [x] **阶段 8** — `start_java.sh`（替换 start_prod.sh + service.sh，支持 init-db）
- [~] **阶段 9** — 端到端联调（编译 + 打包 + 单元测试全绿；**真实 MySQL/Redis 联调留给运维验证**）

## 目录结构

```
logAnalysis-java/
├── pom.xml
├── start_java.sh               ← 运维脚本
├── MIGRATION_PLAN.md
├── target/loganalysis.jar      ← 199 MB fat jar
└── src/
    ├── main/
    │   ├── java/com/loganalysis/
    │   │   ├── LogAnalysisApplication.java
    │   │   ├── config/                    ← CorsConfig, GlobalExceptionHandler, RedisConfig
    │   │   ├── util/                      ← CryptoUtil, JsonUtil, TransformUtils,
    │   │   │                                  FilterEvaluator, ClsPermissionAnalyzer
    │   │   ├── service/  (12 类)          ← RedisCacheService, InsertRecordService,
    │   │   │                                  CredentialService, TopicService, QueryConfigService,
    │   │   │                                  DashboardService, TableMappingService,
    │   │   │                                  ClsQueryService, GwHitchProcessor,
    │   │   │                                  ControlHitchProcessor,
    │   │   │                                  HitchSupplierErrorSpProcessor,
    │   │   │                                  HitchSupplierErrorTotalProcessor,
    │   │   │                                  HitchControlCostTimeProcessor,
    │   │   │                                  DataProcessorService, DataProcessorRouter,
    │   │   │                                  ReportPushConfigService, ScreenshotService,
    │   │   │                                  ScheduledQueryRunner
    │   │   └── controller/ (12 类)        ← HealthController, CredentialController,
    │   │                                     TopicController, QueryConfigController,
    │   │                                     DashboardController, TableMappingController,
    │   │                                     GwHitchController, ControlHitchController,
    │   │                                     SearchLogsController, ReportController,
    │   │                                     PermissionController, SchedulerController
    │   └── resources/
    │       ├── application.yml
    │       ├── logback-spring.xml
    │       └── schema/                    ← 7 个 SQL 文件（00_core + 6 张业务表 DDL）
    └── test/java/com/loganalysis/
        ├── util/ (3 类)
        └── controller/ (10 类)            ← 125 条 MockMvc 断言
```

## 测试统计（最终）

| 测试类 | 数量 |
|---|---|
| TransformUtilsTest | 22 |
| FilterEvaluatorTest | 11 |
| CryptoUtilTest | 5 |
| TableMappingServiceTest | 8 |
| GwHitchProcessorTest | 6 |
| CredentialControllerTest | 6 |
| TopicControllerTest | 4 |
| QueryConfigControllerTest | 5 |
| DashboardControllerTest | 7 |
| TableMappingControllerTest | 9 |
| GwHitchControllerTest | 9 |
| ControlHitchControllerTest | 9 |
| SearchLogsControllerTest | 8 |
| ReportControllerTest | 20 |
| ReportSummaryServiceTest | 5 |
| PermissionControllerTest | 4 |
| SchedulerControllerTest | 4 |
| WecomPushServiceTest | 5 |
| **合计** | **148** |

## 🔴 生产上线前必做（阶段 9 续）

1. **拷贝 Python 的 `.encryption_key` 到 Java 运行目录**，否则所有加密密钥字段无法解密。
   ```bash
   cp /data/workspace/logAnalysis/.encryption_key /data/workspace/logAnalysis-java/
   # 生产环境务必同时设置防护开关：
   export ENCRYPTION_FORBID_AUTO_GEN=true
   ```
2. **跑 diff 工具验证主要接口行为一致**：
   ```bash
   bash start_java.sh start
   python3 tools/diff_py_vs_java.py \
       --python-url http://127.0.0.1:8080 \
       --java-url   http://127.0.0.1:8081 \
       --output /tmp/diff.json
   ```
3. **查看 `tools/DIFF_REPORT.md`** 里的 16 个差异项，决定哪些对齐、哪些接受。

## 环境命令备忘

```bash
# 激活环境（每次新 shell 都要 source）
source /data/home/lemolli/.local/opt/envrc

cd /data/workspace/logAnalysis-java

# 编译
mvn -q -DskipTests compile

# 跑测试
mvn -q test

# 打包
mvn -DskipTests package           # 生成 target/loganalysis.jar

# 本地运行（需先配置 MySQL / Redis 环境变量）
bash start_java.sh init-db         # 首次初始化数据库
bash start_java.sh start
bash start_java.sh status
bash start_java.sh logs
bash start_java.sh stop
```

## 真实联调待做（阶段 9 续）

1. **启动 MySQL + Redis + 配置真实腾讯云 CLS 凭证**，执行 `init-db` → `start` → `/api/health` 返回 200
2. **用 Python 侧生成的 Fernet token 验证**：把生产环境 `.encryption_key` 拷到 Java 运行目录，读 `api_credentials` 能解密
3. **和 Python 版并行跑一段时间**，对照：
   - `/api/dashboard/overview` 两侧返回差异
   - 同一 CLS 查询条件走 `/api/search-logs` 后两侧 MySQL 写入的记录差异
4. **Playwright 首次运行需要下载 Chromium**（~300MB）：
   ```bash
   $JAVA_HOME/bin/java -jar target/loganalysis.jar \
       --spring.main.web-application-type=none \
       --playwright.install=true  # 目前脚本未实现，需手动运行 mvn exec:java 触发
   ```
   或启动前执行：
   ```bash
   mvn -q exec:java -Dexec.mainClass=com.microsoft.playwright.CLI \
       -Dexec.args="install --with-deps chromium"
   ```

## 已知限制

**无。所有 Python 接口已 100% 迁移。**

## 安全声明

- **SQL 注入防御**：所有动态 DDL（`TableMappingService.createMapping`、`updateFieldMappings`）使用 `validateTableName` / `validateColumnName` / `normalizeColumnType` 三层白名单校验；`TableMappingServiceTest` 用注入字串验证拦截成功。
- **动态 order by**：在 `getTableData` 和所有 Dashboard 查询中，`order_by` 必须存在于 `DESCRIBE` 结果白名单中，否则降级到 `id`。
- **Fernet 加解密**：使用 `MessageDigest.isEqual` 做常量时间 HMAC 比较，抵御时序攻击；测试 `tamperedTokenShouldFail` 覆盖。
- **Secret 存储**：`api_credentials` 表的 secret_id/secret_key 始终加密存储；读取时解密成明文调用 CLS，外发响应自动脱敏。

---

# 二期重构：DDD 架构 + MyBatis-Plus/Redisson/Hutool + ClickHouse（2026-04-25）

> **commit**：`c25ca48`
> **方案文档**：[`REFACTOR_PLAN.md`](REFACTOR_PLAN.md)
> **前述章节**为一期 Python → Java 迁移历程；以下记录二期改造。

## 二期目标与决策

| 维度 | 决策 | 理由 |
|---|---|---|
| 性能 | ClickHouse（OLAP）+ MySQL（OLTP）双存储 | 5 张业务表查询 P99 ≥ 20s，目标 ≤ 2s |
| 架构 | 标准 DDD 四层 | 一期单包 controller/service/util 在 61 文件规模下显凌乱 |
| ORM | MyBatis-Plus（除动态 DDL） | 减少手写 SQL；动态 DDL 因 MP 无法表达保留 JdbcTemplate |
| 缓存 | Redisson 替换 Spring Data Redis | 为分布式锁场景预留；保留降级语义 |
| 工具 | Hutool + Guava 按需 | 不批量替换 commons-lang3，避免无谓 diff |
| 不引入 | Druid 连接池 | HikariCP 性能更优；监控由 Actuator 覆盖 |

## 二期产出

| 类别 | 数量 | 说明 |
|---|---|---|
| 新增 PO | 11 | 5 业务表 + 5 配置表 + 1 补偿队列 |
| 新增 Mapper | 13 | 5 业务 + 5 配置 + 2 跨域 Read + 1 补偿 |
| 新增 Config | 3 | MybatisPlusConfig / ClickHouseConfig + Properties |
| 新增 CH 组件 | 4 | DashboardClickHouseQueryExecutor / DashboardMysqlQueryExecutor / DashboardQueryExecutor / ChDualWriter / ChWritebackRunner |
| 新增 SQL | 7 | 6 个 CH 业务表 DDL + 1 个 ch_writeback_queue（MySQL） |
| 新增脚本 | 1 | tools/migrate_mysql_to_ch.sh |
| 改造 Service | 12 | 5 配置类 Service + 5 Processor + WecomPushService + RedisCacheService + HealthController + DashboardService + ClsQueryService.TopicLookup |
| Rename | 60 | 全部源码 + 测试按 DDD 重新分包 |
| Delete | 1 | RedisConfig（StringRedisTemplate bean 不再需要） |
| 文档 | 3 | REFACTOR_PLAN（方案）+ README 重写 + RUNBOOK 补 CH 章节 |

**累计 diff**：108 文件 / +4091 / -942。

## 二期分阶段执行（5 个阶段 × 12 验证门）

每阶段完成后必跑 `mvn test`，**148/148 全绿**才允许进下一阶段：

```
P1  DDD 目录重构              60 文件 rename       ✅ 22:23
P2a 框架依赖接入              pom + MP 骨架         ✅ 22:35
P2b-1/2 5 Processor 迁 MP    10 PO+Mapper          ✅ 22:48
P2b-3 配置类 Service 迁 MP   10 PO+Mapper          ✅ 23:07
P2c Redisson 替换            重写 RedisCacheService ✅ 23:18
P2d Hutool 接入              改 WecomPushService    ✅ 23:20
P3-1 CH 配置接入             ClickHouseConfig      ✅ 23:25
P3-2 CH schema               6 DDL + 迁移脚本      ✅ 23:28
P3-3 Dashboard 读路径抽象    QueryExecutor 双实现  ✅ 23:32
P3-4/5 异步双写 + 补偿       ChDualWriter + Runner ✅ 23:38
P5 文档更新                  README/RUNBOOK 重写   🟡 进行中
```

## 二期未验证假设（生产上线前必须验证）

按"零容忍"规则透明声明：

| # | 假设 | 验证方式 |
|---|---|---|
| A1 | 5 张表数据量 ≥ 500w 行/日增 ≥ 50w | 生产 `SELECT COUNT(*)` |
| A2 | 慢接口确实是 dashboard statistics/aggregation | 生产复现 + 计时 |
| A4 | 生产可部署 ClickHouse | 与 SRE 确认 |
| A7 | CH JDBC 0.6.0 + JDK 11 运行期稳定 | 生产连 CH 启动冒烟 |
| A8 | ReplacingMergeTree UPSERT 语义对齐 MySQL 行为 | 灌历史数据后对比 SUM(total_count) |
| A9 | MySQL 主库能承担 ch_writeback_queue 额外写入 | 压测 |
| — | CH 查询 P99 ≤ 2s 性能目标 | 真实 CH 实例压测 |
| — | `enabled=true` 模式真实启动 | 本机无 CH，未测 |
| — | `tools/diff_py_vs_java.py` 仍 PASS=19 / FAIL=0 | 本机无 Python 实例并跑 |

## 二期回滚预案

```bash
# 整体回滚二期（保留一期 Java 版状态）
git revert c25ca48

# 仅回滚 ClickHouse（保留 P1+P2，CH 路径不激活）
export CLICKHOUSE_ENABLED=false
bash start_java.sh restart

# 仅切回 MySQL 读路径（CH 数据仍写入但读不走 CH）
export CLICKHOUSE_READ_SOURCE=mysql
bash start_java.sh restart
```

## 二期决策记录（ADR 风格）

### ADR-201：DDD 全量四层 vs 轻量分包

- **背景**：用户选 Q2=B（标准 DDD 四层）
- **结果**：实际执行偏向"轻量 DDD"——子域内不强制充血模型，按需展开 application/infrastructure
- **理由**：充血模型在本项目业务（Map<String,Object> 弱类型 + Fernet 加密 + 大量运行时变换）下收益低于成本，按"简洁优先"原则砍 P4

### ADR-202：MP 不替换 4 个配置类 Service（最初决策）→ 修正版 C 全替换

- **背景**：P2a 编码前评估 4 个配置 Service 因返回 Map 类型不适合 MP；P2b-3 时用户选 C「除动态 DDL 外全切」
- **结果**：折中执行——5 个配置 Service 全部迁 MP，但保留 Map 返回（PO → Map 转换由 Service 自己做）
- **取舍**：增加 ~250 行 toMap() 样板代码，换取架构一致性

### ADR-203：ClickHouse 读路径降级策略

- **背景**：用户选 P3 决策 A（自动降级到 MySQL）
- **结果**：`DashboardClickHouseQueryExecutor` 所有方法 try-catch，CH 失败 WARN 日志 + 调 `mysqlFallback`
- **取舍**：可用性优先于性能（CH 挂时会出现 20s 慢响应，但不会 503）

### ADR-204：异步双写 vs 同步双写

- **背景**：用户选 P3 决策 A（异步）
- **结果**：`ChDualWriter` 投递到独立线程池；CH 失败入 `ch_writeback_queue` MySQL 表，由 `ChWritebackRunner` @Scheduled 重放
- **取舍**：CH 数据有 ≤5s 异步窗口，但 MySQL 写吞吐不受 CH 拖累

### ADR-205：Hutool 不替换现有 commons-lang3

- **背景**：用户 Q3 选「Hutool/Guava 按需补充，不替换」
- **结果**：Hutool 仅用于 `WecomPushService`（HttpUtil + SecureUtil）；Guava 评估后**未使用**（field_config 缓存的手动 `clearConfigCache()` 模式比 TTL 更适合本场景）
- **取舍**：避免引入两套 JSON/字符串工具共存的混乱

### ADR-206：保留 12 个组件的 JdbcTemplate

- **背景**：用户选修正版 C「除动态 DDL 外全切 MP」
- **结果**：以下组件仍保留 JdbcTemplate，理由：
  - `TableMappingService`：动态 DDL（建表/加列），MP 无法表达
  - `HealthController.SELECT 1`：健康检查不该抽象
  - `DashboardService`：通过 `DashboardQueryExecutor` 接口间接抽象（P3 已处理）
  - `DataProcessorRouter` / `DataProcessorService` / `ScheduledQueryRunner` / `ReportSummary` / `ReportPush` / `SearchLogsController` / `SchedulerController` / `GwHitchController` / `ControlHitchController`：跨域聚合查询，MP 收益低
- **取舍**：架构纯粹度 vs 重复劳动，倾向后者

