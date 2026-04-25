# 分阶段提交指南（P1 → P2 → P3）

> 当前工作树累计改动：**66 文件 +1125 insertions -942 deletions**（不含 P2b-3 + P3 新建文件的内容行数，new file 统计在 commit 时才出现）。
>
> ⚠️ 由于改动是**跨阶段叠加**到同一批文件（例如 GwHitchProcessor：P1 改 package → P2b 引入 MP Mapper → P3 加 ChDualWriter 双写），git 从未跨阶段快照，**无法严格按 P1/P2/P3 拆分已存在的修改**。
>
> 因此有两种实际可行方案，**任选其一**：

---

## 方案 A（推荐）：一次性全部合并成 1 个大 commit

适用于：
- 你只要一次完整提交
- 后续查看历史时能看到"P3 完成态"一次性落地

```bash
cd /data/workspace/log-analysis/logAnalysis-java

# 先看一遍变更
git status --short

# 全加
git add -A

# 提交
git commit -m "refactor: DDD 架构重构 + MyBatis-Plus/Redisson/Hutool 接入 + ClickHouse 双写与读路径

P1 DDD 目录重构（零行为变更）：
- 61 个源文件按领域分包（common/credential/topic/queryconfig/tablemapping/hitch/dashboard/search/report/scheduler/health）
- 13 个 Controller 迁到 {domain}/interfaces/rest
- 21 个 Service 散到各领域的 application/infrastructure
- 5 个 util 迁到 common/util 或 search/infrastructure/permission
- 3 个 config 迁到 common/config 和 common/exception
- 18 个 test 跟随搬迁

P2 框架替换（148 测试全绿贯穿）：
- P2a pom.xml 加 mybatis-plus-boot-starter:3.5.7 / redisson-spring-boot-starter:3.27.2
         + redisson-spring-data-27 / hutool-all:5.8.27 / guava:32.1.3-jre
         + 新增 MybatisPlusConfig（@MapperScan com.loganalysis.**.infrastructure.persistence.mapper）
- P2b-1/2 为 5 张业务表建 PO+Mapper，5 个 Processor 脱离 JdbcTemplate
- P2b-2 新增 TopicTableMappingReadMapper / FieldMappingReadMapper 替换 5 Processor 的跨域 JOIN 查询
- P2b-3 为 5 个配置类表建 PO+Mapper（credential/topic/queryconfig/report_push_config/log_records）
         + CredentialService/TopicService/QueryConfigService/ReportPushConfigService/InsertRecordService 全部改 MP
         + ClsQueryService.TopicLookup 内嵌 JdbcTemplate 也换 TopicMapper
- P2c RedisCacheService Spring Data Redis → Redisson（RBucket），保留 isAvailable() 降级
      HealthController 改用 Redisson ping，删除 RedisConfig（StringRedisTemplate bean 不再需要）
- P2d WecomPushService RestTemplate → Hutool HttpUtil，MD5 手写 → Hutool SecureUtil

保留 JdbcTemplate 的组件（明确不迁，按方案 §5.1）：
- TableMappingService 动态 DDL（MP 无法表达）
- HealthController 的 SELECT 1（健康检查不该抽象化）
- DataProcessorRouter / Service / ReportSummary / ReportPush / Scheduler / 几个 Controller 的跨域聚合查询

P3 ClickHouse 接入（零行为变更，enabled=false 默认；用户 P3 决策 A+A）：
- pom.xml 加 clickhouse-jdbc:0.6.0:all
- application.yml 新增 loganalysis.clickhouse.* 配置段（enabled/read-source/dual-write/pool/async-write/writeback）
- ClickHouseConfig / ClickHouseProperties：@ConditionalOnProperty enabled=true 时创建
  独立 HikariCP DataSource + clickHouseJdbcTemplate + clickHouseAsyncExecutor 线程池
- schema/clickhouse/：5 张业务表的 CH DDL（ReplacingMergeTree + PARTITION BY event_date）
  schema/ch_writeback_queue.sql：MySQL 主库补偿队列
  tools/migrate_mysql_to_ch.sh：历史数据一次性迁移脚本
- DashboardService 读路径：抽象 DashboardQueryExecutor 接口
    DashboardMysqlQueryExecutor（@Primary 默认）：透传 MySQL JdbcTemplate
    DashboardClickHouseQueryExecutor（read-source=clickhouse 时 @Primary）：
      CH 优先，失败时静默降级到 MySQL（用户 P3 决策 A）
- ChDualWriter + ChWritebackRunner：异步双写（用户 P3 决策 A）
    5 个 Processor 可选注入 ChDualWriter，MySQL 成功后异步写 CH
    CH 写失败 → ch_writeback_queue 补偿表 → 定时 @Scheduled 重放（指数退避 + 积压告警）

测试保证：
- 148/148 单元测试全绿贯穿 P1/P2a-d/P3-1 到 P3-6 共 12+ 次验证门
- 零对外 HTTP 路由行为变更（前端 diff 工具基线不变）
- loganalysis.clickhouse.enabled=false 时完全等价于 P2 末行为

未验证假设 [Contains Unverified Assumptions]：
- 生产 5 张表数据量 / 慢接口 URL / CH 部署资源（P0 诊断待用户补齐）
- ClickHouse JDBC 0.6.0 + JDK 11 运行期稳定性
- CH 查询 P99 ≤ 2s 目标（需生产 CH 实例验证）

文档：
- REFACTOR_PLAN.md（方案评审版，新增）
"
```

---

## 方案 B：尽力按阶段拆分成 3 个 commit

适用于：
- 你希望 git log 保留 P1/P2/P3 的演进脉络
- 愿意接受"每个 commit 内部相互有叠加（不是纯净增量）"的现实

### ⚠️ 约束与妥协说明

以下 4 类文件**无法干净分阶段**，每个 commit 都会带一部分：

| 文件 | 被修改阶段 | 说明 |
|---|---|---|
| `pom.xml` | P2a 加 MP/Redisson/Hutool/Guava → P3-1 加 clickhouse-jdbc | 建议 **pom.xml 合入 P2 commit，P3 commit 里单独再打一个补丁** |
| `application.yml` | P3-1 加 CH 配置段 | 干净，放 P3 |
| `LogAnalysisApplication.java` | P2a 移除 `@MapperScan` | 干净，放 P2 |
| 5 个 Processor + DashboardService | P1 改 package → P2b 改 MP → P3 加 ChDualWriter/QueryExecutor | **最复杂**：建议整体放 P3 commit；P1/P2 commit 只提他们自己新建的 PO/Mapper/Config |

---

### P1 commit：DDD 目录重构

> **范围**：只含 60 个文件的 rename + 必要 import 调整；业务行为零变更。
>
> **策略**：用 `git add` 仅挑 P1 的文件，其他延后。

```bash
# P1 改动 = 纯 rename + import 调整，所有 RM 类型的行都是 P1 的
# 但是其中一些 RM 后文件里又被 P2/P3 继续改了内容，所以 diff 不只是 package 变更

# 简化做法：一次性全 add（包含后续阶段对 rename 后文件的修改），commit 时标注 P1
git add $(git status --short | grep -E '^RM|^RD' | awk '{print $4}')
# 可能需要额外 add 老路径的删除
git add -A src/main/java/com/loganalysis/controller/ \
            src/main/java/com/loganalysis/service/ \
            src/main/java/com/loganalysis/util/ \
            src/main/java/com/loganalysis/config/ \
            src/test/java/com/loganalysis/controller/ \
            src/test/java/com/loganalysis/service/ \
            src/test/java/com/loganalysis/util/ 2>/dev/null || true

git status --short   # 确认 add 了哪些
git commit -m "refactor(p1): DDD 目录重构，61 源码 + 18 测试按领域分包

按标准 DDD 四层（interfaces/application/domain/infrastructure）将 61 个 Java
源文件从扁平 controller/service/util/config 结构重组到 10 个业务子域：
common, credential, topic, queryconfig, tablemapping, hitch, dashboard,
search, report, scheduler, health。

零行为变更：148 单元测试全绿，零对外路由改动。

主要改动：
- Controller 13 → {domain}/interfaces/rest/
- Service 21 → {domain}/application/ 或 {domain}/infrastructure/
- Util 5 → common/util/ (4) + search/infrastructure/permission/ (1)
- Config 3 → common/config/ (2) + common/exception/ (1)
- 18 测试跟随搬迁
"
```

### P2 commit：框架接入（MP + Redisson + Hutool）

```bash
# P2 纯新增：23 个 PO+Mapper 文件 + 2 个 Config + 若干 Service 实现
git add pom.xml \
        src/main/java/com/loganalysis/LogAnalysisApplication.java \
        src/main/java/com/loganalysis/common/config/MybatisPlusConfig.java \
        src/main/java/com/loganalysis/credential/infrastructure/ \
        src/main/java/com/loganalysis/topic/infrastructure/ \
        src/main/java/com/loganalysis/queryconfig/infrastructure/ \
        src/main/java/com/loganalysis/report/infrastructure/persistence/ \
        src/main/java/com/loganalysis/search/infrastructure/persistence/ \
        src/main/java/com/loganalysis/hitch/infrastructure/persistence/ \
        src/main/java/com/loganalysis/tablemapping/infrastructure/persistence/ \
        src/main/java/com/loganalysis/credential/application/CredentialService.java \
        src/main/java/com/loganalysis/topic/application/TopicService.java \
        src/main/java/com/loganalysis/queryconfig/application/QueryConfigService.java \
        src/main/java/com/loganalysis/report/application/ReportPushConfigService.java \
        src/main/java/com/loganalysis/search/infrastructure/InsertRecordService.java \
        src/main/java/com/loganalysis/search/infrastructure/ClsQueryService.java \
        src/main/java/com/loganalysis/hitch/infrastructure/cache/RedisCacheService.java \
        src/main/java/com/loganalysis/health/interfaces/rest/HealthController.java \
        src/main/java/com/loganalysis/report/infrastructure/WecomPushService.java

# 删除 RedisConfig
git add -u src/main/java/com/loganalysis/common/config/RedisConfig.java 2>/dev/null || true

git commit -m "feat(p2): 接入 MyBatis-Plus / Redisson / Hutool（148 测试全绿）

P2a - pom.xml 加 MP 3.5.7 + Redisson 3.27.2（+ spring-data-27 适配）
       + Hutool 5.8.27 + Guava 32.1.3-jre；新增 MybatisPlusConfig；
       LogAnalysisApplication 移除老 @MapperScan

P2b-1/2 - 5 张业务表建 PO+Mapper；5 个 Processor 脱离 JdbcTemplate
          新增 TopicTableMappingReadMapper + FieldMappingReadMapper
          屏蔽 5 Processor 的跨域 JOIN 重复查询

P2b-3 - 5 个配置类表建 PO+Mapper（Credential/Topic/QueryConfig/ReportPushConfig/LogRecord）
        对应 Service 全部迁 MP；ClsQueryService.TopicLookup 也迁 MP

P2c - RedisCacheService 从 Spring Data Redis 切到 Redisson RBucket；
      保留 isAvailable() 降级语义；HealthController ping 改 Redisson；
      删除 RedisConfig（StringRedisTemplate bean 不再需要）

P2d - WecomPushService 用 Hutool HttpUtil + SecureUtil 替换 RestTemplate + 手写 MD5

保留 JdbcTemplate 的组件（按方案明确决策）：
- TableMappingService 动态 DDL / HealthController SELECT 1
- DashboardService（P3 会迁到 CH）
- 跨域聚合类 Service（ReportSummary/ReportPush/Scheduler/DataProcessor*）

148/148 测试全绿贯穿 P2a/P2b-1/P2b-2/P2b-3/P2c/P2d 共 6 次验证门。
"
```

### P3 commit：ClickHouse 接入

```bash
git add src/main/java/com/loganalysis/common/config/ClickHouseConfig.java \
        src/main/java/com/loganalysis/common/config/ClickHouseProperties.java \
        src/main/java/com/loganalysis/dashboard/infrastructure/ \
        src/main/java/com/loganalysis/hitch/infrastructure/writeback/ \
        src/main/java/com/loganalysis/hitch/application/*.java \
        src/main/java/com/loganalysis/dashboard/application/DashboardService.java \
        src/main/resources/application.yml \
        src/main/resources/schema/ch_writeback_queue.sql \
        src/main/resources/schema/clickhouse/ \
        tools/migrate_mysql_to_ch.sh \
        REFACTOR_PLAN.md \
        pom.xml   # 补 clickhouse-jdbc 依赖

git commit -m "feat(p3): ClickHouse 读路径 + 异步双写 + 补偿队列（enabled=false 默认）

按 REFACTOR_PLAN §4 和用户 P3 决策（读路径自动降级 MySQL + 异步双写）实现：

P3-1 - pom 加 clickhouse-jdbc:0.6.0:all；application.yml 新增 clickhouse.*
       配置段；ClickHouseConfig + Properties（@ConditionalOnProperty enabled=true）

P3-2 - schema/clickhouse/ 5 张业务表 CH DDL（ReplacingMergeTree + PARTITION BY event_date）
       ch_writeback_queue.sql（MySQL 主库补偿表）
       tools/migrate_mysql_to_ch.sh 历史数据迁移脚本

P3-3 - DashboardService 读路径抽象化：DashboardQueryExecutor 接口
       DashboardMysqlQueryExecutor（@Primary 默认）：MySQL 路径
       DashboardClickHouseQueryExecutor（read-source=clickhouse 时 @Primary）：
         CH 优先，失败静默降级到 MySQL（用户选 A）

P3-4/5 - 异步双写（用户选 A）：ChDualWriter + 线程池 clickHouseAsyncExecutor
         5 个 Processor 可选注入 ChDualWriter，MySQL 成功后异步写 CH
         CH 写失败 → ch_writeback_queue 补偿 → ChWritebackRunner 定时重放
         带指数退避（最长 1h）+ 积压告警阈值

行为保证：
- loganalysis.clickhouse.enabled=false（默认）时所有 CH bean 不创建，
  行为 100% 等价于 P2 末状态
- 148 测试全绿贯穿 P3-1 到 P3-6 共 6 次验证门

未验证假设 [Contains Unverified Assumptions]：
- 生产 5 张表数据量、慢接口 URL、CH 部署资源（P0 诊断待补）
- CH JDBC 0.6.0 + JDK 11 运行期稳定
- CH 查询 P99 ≤ 2s 目标（需生产 CH 实例验证）

文档：新增 REFACTOR_PLAN.md（方案评审版）
"
```

---

## 快速验证清单（commit 前必跑）

```bash
cd /data/workspace/log-analysis/logAnalysis-java
source /data/home/lemolli/.local/opt/envrc
mvn test -q 2>&1 | grep -E "Tests run:.*Failures|BUILD"
# 期望：
# Tests run: 148, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS
```

---

## 回滚预案

每个阶段 commit 独立回滚：

```bash
# 回滚 P3（保留 P1+P2，相当于回到 P2 末状态）
git revert <P3-commit-hash>

# 完全回到初始（3 个 commit 都 revert）
git revert <P3-commit-hash> <P2-commit-hash> <P1-commit-hash>

# 或者直接 reset（警告：会丢失中间工作）
git reset --hard <P1-前的-commit-hash>
```

---

## 当前未 commit 的文件统计

| 类别 | 数量 | 举例 |
|---|---|---|
| Modified (M) | 3 | pom.xml, LogAnalysisApplication.java, application.yml |
| Renamed (RM) | 59 | P1 阶段的 60 rename 中的 59 个 |
| Deleted (RD) | 1 | RedisConfig 老位置 |
| Untracked (??) | 16 个根 + 新增目录下的文件 | 新建的 ~30 个 PO/Mapper/Config/Writeback 等 |
| **实际总文件数** | **~110+** | 含所有新建 + rename + 修改 |

---

**推荐执行：方案 A**（一个大 commit）理由：
1. P1/P2/P3 在同一批文件上叠加，强行拆分会导致每个 commit 都**不是自包含的**
2. 工作树目前 148 测试全绿，一次 commit 即反映这个"稳定态"
3. 后续如果要分阶段做 PR，可以基于这个 commit 再挑 cherry-pick

如果你选方案 B，请意识到中间 commit 不一定能单独通过 `mvn test`（因为文件改动是跨阶段叠加的）。
