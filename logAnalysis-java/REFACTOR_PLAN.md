# logAnalysis-java 重构方案（评审版 v1.0）

> **文档状态**：✅ **已执行**（commit `c25ca48`，2026-04-25 23:42）
> **作者**：AI 助手
> **生成时间**：2026-04-25 22:00
> **执行完成**：2026-04-25 23:42（约 1.7 小时净编码时间）
> **前置决策**（已与用户确认）：
> - Q1 性能：**ClickHouse（OLAP 聚合）+ MySQL（OLTP/配置）双存储**
> - Q2 架构：**标准 DDD 四层**（interfaces / application / domain / infrastructure）
> - Q3 框架：**MyBatis-Plus + Redisson + Hutool + Guava**（Druid 不引入）
> - Q4 节奏：**先出方案，评审通过再编码**

> **零容忍声明**：本方案中所有"性能数据、慢接口清单、数据量"均为**未验证假设**，标注见各节。编码阶段必须先验证假设，才可按方案落地。

---

## 执行总结（追加于完成后）

| 阶段 | 状态 | 完成时间 | 关键产出 |
|---|---|---|---|
| **P0 诊断** | ⚠️ **跳过**（用户选方案 Y） | — | 待用户补齐生产数据 |
| **P1 DDD 重构** | ✅ 完成 | 22:23 | 60 文件 rename，零行为变更 |
| **P2a 框架接入** | ✅ 完成 | 22:35 | pom + MybatisPlusConfig；Redisson/Hutool/Guava 依赖就绪 |
| **P2b-1/2 5 Processor 迁 MP** | ✅ 完成 | 22:48 | 10 PO+Mapper（5 业务 + 5 跨域）；5 Processor 脱离 JdbcTemplate |
| **P2b-3 配置类 Service 迁 MP** | ✅ 完成（修正版 C） | 23:07 | 10 PO+Mapper；5 Service 全切 MP |
| **P2c Redisson 替换** | ✅ 完成 | 23:18 | RedisCacheService 重写；删除 RedisConfig |
| **P2d Hutool 接入** | ✅ 完成 | 23:20 | WecomPushService 用 HttpUtil + SecureUtil |
| **P3-1 CH 配置接入** | ✅ 完成 | 23:25 | ClickHouseConfig + Properties；条件装配 |
| **P3-2 CH schema** | ✅ 完成 | 23:28 | 6 个 CH DDL + 补偿表 + 迁移脚本 |
| **P3-3 Dashboard 读路径抽象** | ✅ 完成 | 23:32 | DashboardQueryExecutor + 2 实现；CH 失败降级 |
| **P3-4/5 异步双写 + 补偿** | ✅ 完成 | 23:38 | ChDualWriter + ChWritebackRunner；5 Processor 加双写钩子 |
| **P5 文档更新** | 🟡 进行中 | — | README / RUNBOOK / MIGRATION_PLAN 更新 |

**累计验收**：148/148 测试全绿 × 12+ 验证门贯穿。

**Commit 信息**：`c25ca48` — 108 文件 / +4091 / -942 行。

**验收脱漏的工作（明确声明）**：
- ❌ **未在本机启动 enabled=true 模式**（无 ClickHouse 实例可连）
- ❌ **未跑 `tools/diff_py_vs_java.py`**（无 Python 实例并跑环境）
- ❌ **未做 P0 性能诊断**（未拿到生产数据）
- 这三项必须由用户在生产或预发环境验证

---

## 目录

1. [范围边界与不做什么](#1-范围边界与不做什么)
2. [现状快照与慢点假设](#2-现状快照与慢点假设)
3. [目标架构与目录结构](#3-目标架构与目录结构)
4. [ClickHouse 存储方案（核心）](#4-clickhouse-存储方案核心)
5. [框架替换细则](#5-框架替换细则)
6. [DDD 分领域映射表（61 文件全覆盖）](#6-ddd-分领域映射表61-文件全覆盖)
7. [分阶段落地 Roadmap（5 阶段 / 可独立回滚）](#7-分阶段落地-roadmap5-阶段--可独立回滚)
8. [测试策略（148 条测试保活）](#8-测试策略148-条测试保活)
9. [风险清单与回滚预案](#9-风险清单与回滚预案)
10. [未验证假设清单](#10-未验证假设清单)
11. [待评审决策点](#11-待评审决策点)

---

## 1. 范围边界与不做什么

### 1.1 做什么

- 性能优化：5 张业务表的聚合查询接口 P99 从 ~20s 降至 ≤2s
- 架构重构：单包平铺 → DDD 四层（按领域分模块）
- 框架替换：JdbcTemplate → MyBatis-Plus（除动态 DDL）、Spring Data Redis → Redisson、引入 Hutool/Guava
- README/RUNBOOK/MIGRATION_PLAN 同步更新

### 1.2 不做什么（明确反对清单）

| 不做项 | 理由 |
|---|---|
| 不引入 Druid 连接池 | 用户确认不要；HikariCP + Micrometer/Actuator 已覆盖监控需求；Druid 相对 Hikari 性能反而偏弱 |
| 不替换 Spring Boot / JDK 版本 | 2.7.18 + JDK 11 组合稳定；升级无直接收益且引入 Jakarta 命名空间迁移风险 |
| 不改 82 路由的 URL / 入参 / 出参结构 | 用户要求"原来功能不变"；`tools/diff_py_vs_java.py` 的 19/29 PASS 基线不可破 |
| 不动 Fernet 加密格式 / CryptoUtil 内部实现 | 生产密文存储，兼容性红线 |
| 不动 `TableMappingService` 的动态 DDL（建表/加列/DESCRIBE 白名单） | MyBatis-Plus 无法表达动态建表；这部分是 SQL 注入防护的核心战场，迁移 = 无谓的漏洞再审计 |
| 不删 `Dockerfile` / `docker/` / `.github/workflows/ci.yml` | 容器化已在 MIGRATION_PLAN 标注未验证；本次不纳入 |
| 不做代码风格格式统一（Checkstyle/Spotless） | 按规则"禁止顺手重构无关代码" |
| 不改 `tools/` 的 Python 对比工具 | 这是验证工具，改它等于改验证基线 |

---

## 2. 现状快照与慢点假设

### 2.1 代码资产盘点

| 项 | 数量 | 说明 |
|---|---|---|
| Controller | 13 | 82 HTTP 路由 |
| Service / Processor | 21 | 含 5 个业务处理器 |
| Util | 5 | Crypto / Transform / Filter / Json / ClsPermission |
| Config | 3 | Cors / ExceptionHandler / Redis |
| Test | 18 类 / 148 断言 | 全绿 |
| MySQL 表 | 11 张（业务 5 + 配置/映射 6） | |

### 2.2 5 张业务表（性能优化焦点）

| 表 | 写入方 | 典型查询方 | 预期增长 |
|---|---|---|---|
| `gw_hitch_error_mothod` | `GwHitchProcessor` Redis+MySQL UPSERT 聚合 | `/api/dashboard/gw-hitch/*` | 高（秒级 CLS 日志） |
| `control_hitch_error_mothod` | `ControlHitchProcessor` | `/api/dashboard/control-hitch/*` | 高 |
| `hitch_supplier_error_sp` | `HitchSupplierErrorSpProcessor` | `/api/dashboard/*` | 中 |
| `hitch_supplier_error_total` | `HitchSupplierErrorTotalProcessor` | `/api/dashboard/*` | 中 |
| `hitch_control_cost_time` | `HitchControlCostTimeProcessor` 每条 INSERT | `/api/dashboard/cost-time/*` | **最高**（无聚合，每条入库） |

### 2.3 慢点假设（待验证）

**[Contains Unverified Assumptions]** 依据 DashboardService 代码结构推断：

| 接口类别 | 典型 SQL 形态 | 慢点归因推断 |
|---|---|---|
| `/api/dashboard/{table}/statistics` | `SELECT method_name, error_code, COUNT(*), SUM(count) FROM x WHERE create_time BETWEEN ? AND ? GROUP BY 1,2 ORDER BY ? LIMIT ?` | **GROUP BY 大表 + SUM** 是 OLAP 典型慢查询，MySQL InnoDB 在日增千万级下难以 2s 内返回 |
| `/api/dashboard/{table}/aggregation` | 按小时/日维度聚合 | 同上，且时间桶函数 `DATE_FORMAT` 无法索引 |
| `/api/dashboard/overview` | 5 张表各查一次 COUNT/SUM | 单请求串行触发 5 次全表聚合 |
| `/api/search-logs` | 调 CLS，不查 MySQL | **非瓶颈**（Python 版也慢 = CLS 侧慢） |
| `/api/report/summary` | 跨表聚合 + Top N | 多表 GROUP BY |

**🟥 必须在编码阶段先验证**：
1. 连上生产只读库，对 5 张表跑 `SELECT COUNT(*), MIN(create_time), MAX(create_time)` 输出数据量
2. 对 README 声称慢的接口复现一次，`EXPLAIN ANALYZE` 出执行计划
3. 根据真实数据量**回确认 ClickHouse 方案是否对症**（若单表 <1000w 行，可能只需加复合索引即可，ClickHouse 反而过度工程化）

---

## 3. 目标架构与目录结构

### 3.1 顶层 Maven 模块策略

**决策：单 module + 按 DDD 分包，不拆 multi-module**。

理由：拆 multi-module 收益（编译并行、模块强隔离）在 61 文件规模下不显著，反而增加构建复杂度和 fat-jar 打包难度。

### 3.2 DDD 四层映射

按标准 DDD 分层定义：

| 层 | 职责 | 允许依赖 |
|---|---|---|
| `interfaces/` | HTTP/调度入口，DTO 转换 | application |
| `application/` | 用例编排（Command/Query/Handler） | domain, infrastructure（仅接口） |
| `domain/` | 领域模型 + 领域服务 + Repository 接口 | **无外部依赖**（纯 POJO + 接口） |
| `infrastructure/` | MyBatis-Plus Mapper 实现、Redis、CLS SDK、Playwright 等技术实现 | domain |

**依赖方向**（严禁反向）：
```
interfaces ─▶ application ─▶ domain
                    └──────▶ infrastructure ─▶ domain
```

### 3.3 领域划分（7 个子域）

```
com.loganalysis
├── LogAnalysisApplication.java
├── common/                       跨领域共享（util/config）
│   ├── config/                   MybatisPlusConfig, RedissonConfig, CorsConfig, ClickHouseConfig ...
│   ├── exception/                GlobalExceptionHandler, BizException
│   ├── util/                     CryptoUtil, JsonUtil, TransformUtils, FilterEvaluator, ClsPermissionAnalyzer
│   └── web/                      ApiResponse, PageDTO（统一响应壳，非必需）
│
├── credential/                   子域 1：腾讯云凭证（Fernet 加密）
│   ├── interfaces/rest/          CredentialController
│   ├── application/              CredentialAppService（命名用例方法）
│   ├── domain/
│   │   ├── model/                Credential, EncryptedSecret（值对象）
│   │   ├── service/              CredentialDomainService（加解密领域逻辑）
│   │   └── repository/           CredentialRepository（接口）
│   └── infrastructure/
│       ├── persistence/          CredentialMapper (MP), CredentialPO, CredentialRepositoryImpl
│       └── crypto/               FernetCryptoAdapter
│
├── topic/                        子域 2：CLS Topic 配置
│   └── ...同上结构
│
├── queryconfig/                  子域 3：查询模板 + 定时调度配置
│   └── ...同上结构
│
├── tablemapping/                 子域 4：动态表映射（DDL 白名单）
│   ├── interfaces/rest/          TableMappingController
│   ├── application/              TableMappingAppService
│   ├── domain/
│   │   ├── model/                TableMapping, FieldMapping, ColumnTypeWhitelist
│   │   └── repository/           TableMappingRepository
│   └── infrastructure/
│       ├── persistence/          TableMappingMapper (MP)
│       └── ddl/                  DynamicDdlExecutor （⚠ 保留 JdbcTemplate）
│
├── hitch/                        子域 5：5 个业务日志处理器（写路径）
│   ├── interfaces/
│   │   └── rest/                 GwHitchController, ControlHitchController
│   ├── application/
│   │   ├── dispatcher/           DataProcessorRouter（分发器）
│   │   └── processor/            GwHitchAppService, ControlHitchAppService,
│   │                             SupplierSpAppService, SupplierTotalAppService,
│   │                             CostTimeAppService
│   ├── domain/
│   │   ├── model/                HitchError (聚合根), ErrorKey (VO), SupplierError, CostTimeEvent
│   │   ├── service/              HitchAggregationDomainService（内存聚合算法）
│   │   └── repository/           HitchRepository (5 个接口，按表)
│   └── infrastructure/
│       ├── persistence/
│       │   ├── mysql/            写 MySQL（保留，用于配置类回查与兼容）
│       │   └── clickhouse/       写 ClickHouse（主聚合存储）
│       └── cache/                HitchAggregateCache (Redisson)
│
├── dashboard/                    子域 6：聚合查询（读路径 ← 性能优化主战场）
│   ├── interfaces/rest/          DashboardController, PageController（HTML 页面）
│   ├── application/
│   │   ├── query/                DashboardStatisticsQueryHandler,
│   │   │                         DashboardAggregationQueryHandler,
│   │   │                         OverviewQueryHandler
│   │   └── policy/               DateRangePolicy（7 天限制、排序白名单）
│   ├── domain/
│   │   ├── model/                StatisticsRow, AggregationBucket, OverviewSnapshot
│   │   └── repository/           DashboardQueryRepository（接口，读模型）
│   └── infrastructure/
│       ├── clickhouse/           DashboardClickHouseRepository（主实现，MP 或 JdbcTemplate）
│       └── mysql/                DashboardMysqlRepository（降级实现，特性开关切换）
│
├── search/                       子域 7：CLS 查询与日志分发（写入口）
│   ├── interfaces/rest/          SearchLogsController, PermissionController
│   ├── application/              SearchLogsAppService, PermissionAppService
│   ├── domain/
│   │   ├── model/                SearchRequest, LogEntry
│   │   └── service/              LogDispatcher（对接 hitch.application.dispatcher）
│   └── infrastructure/
│       ├── cls/                  ClsQueryClient（封装腾讯云 SDK）
│       └── permission/           ClsPermissionAnalyzerAdapter
│
├── report/                       子域 8：日报/推送/截图
│   ├── interfaces/rest/          ReportController
│   ├── application/              ReportAppService, ReportPushAppService
│   ├── domain/
│   │   ├── model/                ReportPushConfig, PushRecord, ReportSummary
│   │   ├── service/              ReportSummaryDomainService, WecomSignatureDomainService
│   │   └── repository/           ReportPushConfigRepository, PushLogRepository
│   └── infrastructure/
│       ├── persistence/          MP Mapper + PO
│       ├── wecom/                WecomHttpClient
│       ├── screenshot/           PlaywrightScreenshotAdapter
│       └── html/                 ReportHtmlRenderer
│
├── scheduler/                    子域 9：定时调度
│   ├── interfaces/rest/          SchedulerController
│   ├── application/              ScheduledQueryRunner（@Scheduled）
│   └── domain/                   （仅 VO，调度语义薄）
│
└── health/                       子域 10：健康检查
    └── interfaces/rest/          HealthController
```

### 3.4 包规则强约束（编码期须遵守）

| 规则 | 示例 |
|---|---|
| 领域之间**禁止**直接访问彼此的 `infrastructure/` 或 `domain/repository/` | `hitch.application` ❌ 直接调 `dashboard.infrastructure.DashboardMysqlRepository` |
| 领域之间通信走 `application` 层服务（Spring Bean 注入）或领域事件 | `search.application.SearchLogsAppService` 调 `hitch.application.dispatcher.DataProcessorRouter` |
| PO（persistence object）只在 `infrastructure.persistence` 内使用，不渗透到 domain | `CredentialPO` ❌ 出现在 `CredentialRepository` 接口签名 |
| DTO 只在 `interfaces` 层 | `application` 接受/返回 Command/Query/Result 对象 |

> **落地简化**：命名上用 `XxxPO`（持久化）、`XxxDO`（领域模型）、`XxxDTO`（接口层）、`XxxCommand`/`XxxQuery`（应用层入参）、`XxxResult`（应用层出参）区分，避免混淆。

---

## 4. ClickHouse 存储方案（核心）

### 4.1 为什么选 ClickHouse（反对视角先说）

**反对论据**（规则要求的反对意见）：
- ClickHouse 需要运维多维护一套存储，SRE 成本 +1
- 如果真实数据量 <1000 万/表，纯 MySQL 加索引+分区也能打 2s 内
- 双写带来数据一致性成本（会不会出现 MySQL 有 / ClickHouse 没有？）
- 5 张表结构已经是"**预聚合结果**"（`count` / `total_count`），不是原始日志，聚合收益被削弱

**支持论据**：
- `hitch_control_cost_time` 是**每条 INSERT**（无聚合），典型 OLAP 场景，CH 收益最大
- `GROUP BY method_name + error_code + DATE_FORMAT(create_time)` 这种按时间桶聚合，CH 比 MySQL 快 10-50 倍
- 5 张表的查询模式**完全类似**（按 create_time 范围 + 维度聚合 + Top N），一次工程改造覆盖所有接口

**结论**：方案成立的**前提**是真实数据量 ≥ 500 万行/表且日增 ≥ 50 万。**编码第一步必须验证**（见 §10-A1）。

### 4.2 双存储职责划分

| 数据类型 | 存储 | 读 | 写 |
|---|---|---|---|
| 配置类（`api_credentials`/`log_topics`/`query_configs`/`topic_table_mappings`/`field_mappings`/`collection_logs`/`report_push_config`/`log_records`/`analysis_results`） | **MySQL** | MySQL | MySQL |
| 业务聚合类 5 张表 | **ClickHouse 主 + MySQL 影子（短期保留）** | ClickHouse | 双写，MySQL 可在 Phase 5 下线 |
| 凭证加密 Fernet 密文 | **MySQL** | MySQL | MySQL |
| 聚合计数缓存 | **Redis（Redisson）** | Redisson | Redisson |

### 4.3 ClickHouse 表设计（示例：`gw_hitch_error_mothod`）

```sql
CREATE TABLE cls_logs_ch.gw_hitch_error_mothod
(
    id             UInt64,               -- 从 MySQL 主键复用，双写用
    method_name    LowCardinality(String),
    error_code     Int32,
    error_message  String,
    content        String,
    count          UInt32,
    total_count    UInt64,
    create_time    DateTime('Asia/Shanghai'),
    update_time    DateTime('Asia/Shanghai'),
    event_date     Date MATERIALIZED toDate(create_time)  -- 分区键
)
ENGINE = ReplacingMergeTree(update_time)  -- 按 update_time 去重
PARTITION BY event_date
ORDER BY (event_date, method_name, error_code, id)
SETTINGS index_granularity = 8192;
```

**设计要点**：
- `ReplacingMergeTree + update_time`：保持与 MySQL UPSERT 语义一致（同 id 的新 update_time 覆盖旧）
- `PARTITION BY event_date`：删除过期数据 `DROP PARTITION` 毫秒级
- `ORDER BY (event_date, method_name, error_code)`：对齐现有索引 `idx_ct`、`idx_error_code`，覆盖 90% 查询模式
- `LowCardinality(String)` for `method_name`：method 数量有限（几百个），字典编码压缩率 5-10x

### 4.4 双写策略（读 CH / 写双 / 一致性）

```
写路径：
  CLS 日志 → Processor 内存聚合 → Redisson 计数缓存
      ↓
      ├─→ MySQL（保留，OLTP 主库，Fernet 凭证、配置表依赖）
      └─→ ClickHouse（聚合主存储）

读路径：
  /api/dashboard/*  →  DashboardClickHouseRepository  → ClickHouse
                       （降级开关打开时 → DashboardMysqlRepository → MySQL）
```

**一致性**：
- **不上分布式事务**（代价不成比例）
- MySQL 写成功 + CH 写失败 → 记录到 `ch_writeback_queue` MySQL 影子表 → 定时补偿任务（@Scheduled 每 30s）重放
- CH 成功 + MySQL 失败 → **整笔回滚**（MySQL 是权威源，必须成功）

**特性开关**（`application.yml`）：
```yaml
loganalysis:
  clickhouse:
    enabled: true              # 关掉即回退到纯 MySQL
    read-source: clickhouse    # clickhouse | mysql（灰度期可切回）
    dual-write: true           # false 时只写 CH（Phase 5 后可选）
```

### 4.5 迁移数据

历史数据一次性 dump 并灌入 CH：
```bash
# 脚本 tools/migrate_mysql_to_ch.sh（新增）
mysqldump --no-create-info --tab=/tmp/dump \
  --fields-terminated-by=$'\t' \
  cls_logs gw_hitch_error_mothod ...

clickhouse-client --query="INSERT INTO cls_logs_ch.gw_hitch_error_mothod FORMAT TabSeparated" \
  < /tmp/dump/gw_hitch_error_mothod.txt
```

### 4.6 CH Driver 选型

| 候选 | 说明 | 结论 |
|---|---|---|
| `com.clickhouse:clickhouse-jdbc:0.6.x`（官方 JDBC） | 官方维护，兼容 JdbcTemplate / MyBatis-Plus | **选它** |
| `ru.yandex.clickhouse:clickhouse-jdbc` | 已 deprecated | 不选 |
| HTTP Client 直连 | 灵活但脱离 Spring 生态 | 不选 |

**MyBatis-Plus 对 CH 支持**：MP 在 CH JDBC 之上可正常使用 `@Select`/`@TableName`，但**禁用 CH 的 INSERT 级自增和乐观锁**（CH 不支持单行更新）。故 CH 相关 Mapper 只用 `@Select`（读路径），写路径用 JdbcTemplate 批量 `INSERT INTO ... VALUES (?,?),(?,?),...`（性能 5x 于逐条）。

### 4.7 ClickHouse 不做的事

- **不存 Fernet 密文**（不需要）
- **不存** `query_configs` / `topic_table_mappings` / `field_mappings`（配置类，写多读少，走 MySQL）
- **不做** CH 集群（单实例起步；若 QPS 不够再做分片）
- **不做** JOIN 查询（CH 擅长大表扫描，JOIN 性能差；若 Dashboard 有 JOIN 需求用 dict() 字典或预聚合物化视图）

---

## 5. 框架替换细则

### 5.1 MyBatis-Plus（JdbcTemplate → MP）

| 可迁移 | 保留 JdbcTemplate |
|---|---|
| `CredentialService` / `TopicService` / `QueryConfigService` / `ReportPushConfigService` | `TableMappingService` 的建表/加列/DESCRIBE（动态 DDL） |
| `DashboardService` 的查询（MySQL 侧） | `DashboardService.tableExists` 的 `SHOW TABLES` |
| 5 个 Processor 的 `INSERT`/`UPDATE` | ClickHouse 的批量 INSERT（MP 对 CH 写支持弱） |
| Report 相关 Mapper | 所有动态 ORDER BY 但列名白名单固定的查询（走 MP QueryWrapper） |

**使用约定**：
- Mapper 接口放 `infrastructure/persistence/mapper`，XML 不用（全注解）
- PO 类用 `@TableName` / `@TableId` / `@TableField`
- **禁止** 在 Controller/Application 层使用 MP 的 `LambdaQueryWrapper`，只在 Repository 实现内部使用（保持 domain 层纯净）
- 分页统一走 `com.baomidou.mybatisplus.extension.plugins.pagination.Page`，但对外 DTO 用自定义 `PageResult`（防止 MP 类型渗透到 interfaces 层）

**版本**：`mybatis-plus-boot-starter:3.5.7`（兼容 Spring Boot 2.7 最后一个 3.x 版本）

### 5.2 Redisson（Spring Data Redis → Redisson）

**替换目标**：`RedisCacheService`（聚合计数缓存）。

**新增能力**：
- 分布式锁（定时任务 `ScheduledQueryRunner` 多实例部署防重跑 → 但当前单实例部署，**暂不上，仅留扩展点**）
- `RMap` / `RBucket` / `RAtomicLong` 替代手动序列化

**代码示例**（聚合计数缓存迁移）：
```java
// 原 Spring Data Redis
redisTemplate.opsForHash().put("gw_hitch:2026-04-25", key, countStr);

// Redisson
RMap<String, Long> map = redisson.getMap("gw_hitch:2026-04-25");
map.put(key, count);
map.expireAt(tomorrowStart);   // 原子过期
```

**版本**：`redisson-spring-boot-starter:3.27.2`（匹配 Spring Boot 2.7）

**行为兼容性**：原 `RedisCacheService.isAvailable()` 降级逻辑**必须保留**（Redis 挂 → 退回 MySQL 查询）。

### 5.3 Hutool + Guava

**引入范围**（按需使用，**不批量替换**）：

| 库 | 允许场景 | 禁止场景 |
|---|---|---|
| Hutool `DateUtil` / `StrUtil` / `CollUtil` | 新代码，替代散落的手写日期解析、字符串 null 安全 | 替换 `commons-lang3` 里已存在的调用点（避免无谓 diff） |
| Hutool `JSONUtil` | ❌ 完全禁用 | 项目已有 Jackson（JsonUtil），避免两套 JSON 共存导致类型映射不一致 |
| Hutool `HttpUtil` | `WecomPushService` 的企微推送 HTTP 调用 | 替换 Spring RestTemplate/WebClient |
| Guava `Cache` | 本地缓存（如 field_mapping 查询缓存，避免每请求查 MySQL） | 替换 Redisson |
| Guava `Preconditions` | 领域服务的入参校验 | Controller 层（用 `@Validated`） |
| Guava `Multimap` / `ImmutableSet` | 复杂集合场景 | 简单 `Map<String, List>` 别硬塞 |

**版本**：`cn.hutool:hutool-all:5.8.27`，`com.google.guava:guava:32.1.3-jre`

### 5.4 Lombok

已引入，**加强使用约定**：
- `@Data`/`@Getter`/`@Setter`/`@Builder` 用于 PO/DTO/VO
- `@Slf4j` 替代手写 `private static final Logger log = ...`（节省 40 处样板）
- `@RequiredArgsConstructor` 替代字段 `@Autowired`（Spring 官方推荐构造器注入）

---

## 6. DDD 分领域映射表（61 文件全覆盖）

> **目的**：保证每个现有文件都有明确的归属，零遗漏。

### 6.1 util/ (5)

| 现位置 | 新位置 | 说明 |
|---|---|---|
| `util/CryptoUtil.java` | `common/util/CryptoUtil.java` | Fernet 兼容，跨领域共享 |
| `util/JsonUtil.java` | `common/util/JsonUtil.java` | Jackson 封装 |
| `util/TransformUtils.java` | `common/util/TransformUtils.java` | Transform DSL，跨处理器共享 |
| `util/FilterEvaluator.java` | `common/util/FilterEvaluator.java` | Filter DSL |
| `util/ClsPermissionAnalyzer.java` | `search/infrastructure/permission/ClsPermissionAnalyzer.java` | 仅 search 领域用 |

### 6.2 config/ (3)

| 现位置 | 新位置 |
|---|---|
| `config/CorsConfig.java` | `common/config/CorsConfig.java` |
| `config/GlobalExceptionHandler.java` | `common/exception/GlobalExceptionHandler.java` |
| `config/RedisConfig.java` | **删除**，替换为 `common/config/RedissonConfig.java` |
| （新增） | `common/config/MybatisPlusConfig.java` |
| （新增） | `common/config/ClickHouseConfig.java`（多数据源） |

### 6.3 controller/ (13) → interfaces/rest/

| 现位置 | 新位置 |
|---|---|
| `controller/HealthController.java` | `health/interfaces/rest/HealthController.java` |
| `controller/CredentialController.java` | `credential/interfaces/rest/CredentialController.java` |
| `controller/TopicController.java` | `topic/interfaces/rest/TopicController.java` |
| `controller/QueryConfigController.java` | `queryconfig/interfaces/rest/QueryConfigController.java` |
| `controller/DashboardController.java` | `dashboard/interfaces/rest/DashboardController.java` |
| `controller/TableMappingController.java` | `tablemapping/interfaces/rest/TableMappingController.java` |
| `controller/GwHitchController.java` | `hitch/interfaces/rest/GwHitchController.java` |
| `controller/ControlHitchController.java` | `hitch/interfaces/rest/ControlHitchController.java` |
| `controller/SearchLogsController.java` | `search/interfaces/rest/SearchLogsController.java` |
| `controller/ReportController.java` | `report/interfaces/rest/ReportController.java` |
| `controller/PermissionController.java` | `search/interfaces/rest/PermissionController.java` |
| `controller/SchedulerController.java` | `scheduler/interfaces/rest/SchedulerController.java` |
| `controller/PageController.java` | `dashboard/interfaces/rest/PageController.java`（HTML 页面入口） |

### 6.4 service/ (21) → 拆分到各领域

| 现 Service | 新位置 | 拆分说明 |
|---|---|---|
| `CredentialService` | `credential/application/CredentialAppService` + `credential/domain/service/CredentialDomainService` + `credential/infrastructure/persistence/CredentialRepositoryImpl` | 加解密逻辑下沉 domain |
| `TopicService` | `topic/application/TopicAppService` + 同构 | 薄 CRUD，拆分较浅 |
| `QueryConfigService` | `queryconfig/application/QueryConfigAppService` + 同构 | |
| `TableMappingService` | `tablemapping/application/TableMappingAppService` + `tablemapping/infrastructure/ddl/DynamicDdlExecutor`（保 JdbcTemplate） | 白名单校验放 domain |
| `DashboardService` | `dashboard/application/query/*QueryHandler` + `dashboard/infrastructure/clickhouse/DashboardClickHouseRepository` + `dashboard/infrastructure/mysql/DashboardMysqlRepository` | **重点重构** |
| `GwHitchProcessor` | `hitch/application/processor/GwHitchAppService` + `hitch/domain/service/HitchAggregationDomainService`（提取公共聚合算法给 4 个 processor 共享） + `hitch/infrastructure/persistence/GwHitchRepositoryImpl` | |
| `ControlHitchProcessor` | 同上 | |
| `HitchSupplierErrorSpProcessor` | 同上 | |
| `HitchSupplierErrorTotalProcessor` | 同上 | |
| `HitchControlCostTimeProcessor` | `hitch/application/processor/CostTimeAppService` | **不共享聚合算法**（每条独立 INSERT） |
| `DataProcessorRouter` | `hitch/application/dispatcher/DataProcessorRouter` | |
| `DataProcessorService` | 合入 `DataProcessorRouter` 或拆为 `search/application/LogDispatchAppService` | 待编码期细化 |
| `ClsQueryService` | `search/infrastructure/cls/ClsQueryClient` | 纯技术封装 |
| `InsertRecordService` | `search/infrastructure/persistence/InsertRecordRepository` | `log_records` 表写入 |
| `RedisCacheService` | `hitch/infrastructure/cache/HitchAggregateCache`（Redisson 实现） | |
| `ReportPushConfigService` | `report/application/ReportPushConfigAppService` + Repository | |
| `ReportPushService` | `report/application/ReportPushAppService` | |
| `ReportSummaryService` | `report/application/ReportSummaryAppService` + `report/domain/service/ReportSummaryDomainService` | |
| `WecomPushService` | `report/infrastructure/wecom/WecomHttpClient`（Hutool HttpUtil） + `report/domain/service/WecomSignatureDomainService` | |
| `ScreenshotService` | `report/infrastructure/screenshot/PlaywrightScreenshotAdapter` | |
| `ScheduledQueryRunner` | `scheduler/application/ScheduledQueryRunner` | |

### 6.5 test/ (18 类 / 148 断言)

**策略**：测试**跟随被测代码同步移动**，包路径对齐。
- Controller 测试 → `*/interfaces/rest/*Test`
- Service 测试 → `*/application/*Test` 或 `*/domain/service/*Test`
- Util 测试 → `common/util/*Test`

**不改测试断言内容**（这是行为红线）。

---

## 7. 分阶段落地 Roadmap（5 阶段 / 可独立回滚）

> **核心原则**：每阶段结束 `mvn test` 必须全绿（148+ 条），否则不进下一阶段。

### 阶段 P0：前置诊断（**必做，无此数据方案失效**）

**目标**：验证 §2.3 的假设，决定方案是否需要调整。

| 任务 | 产出 | 工时 |
|---|---|---|
| T0.1 对生产只读库查 5 张表行数 / 最早/最新 create_time | `PERF_BASELINE.md` 表 A | 0.5h |
| T0.2 对 README 宣称慢的接口跑 10 次，记录 P50/P99 | 表 B：接口耗时基线 | 2h |
| T0.3 `EXPLAIN` 慢查询的 SQL，记录 rows、Extra、filesort | 表 C | 1h |
| T0.4 基于 T0.1-T0.3 **重新确认 ClickHouse 必要性**，若单表 <500w 行改走纯 MySQL 优化路径 | `PERF_BASELINE.md` 决策章节 | 0.5h |

**决策门**：若数据量证明 CH 不必要，**停下来再找用户确认**方案调整。

### 阶段 P1：DDD 骨架搭建（**零行为变更**）

**目标**：只搬文件 + 调 import，不改逻辑。

| 任务 | 风险 |
|---|---|
| P1.1 创建 7 个领域顶层包 + 每领域 4 个子包 | 低 |
| P1.2 批量移动 util/config 到 `common/` | 低 |
| P1.3 移动 13 个 Controller 到 `*/interfaces/rest/` | 中（大量 import 变更） |
| P1.4 移动 21 个 Service 到 `*/application/` 或 `*/domain/service/`（暂不拆充血模型） | 中 |
| P1.5 测试类跟随移动，package 对齐 | 低 |
| P1.6 `mvn test` 全绿 | **阻塞** |
| P1.7 `tools/smoke_test.sh` 33/33 PASS | **阻塞** |

**回滚**：`git revert` 整个 P1 commit 即可。

### 阶段 P2：框架替换（MP + Redisson + Hutool/Guava 基础接入）

| 任务 | 风险 |
|---|---|
| P2.1 pom.xml 加 MP / Redisson / Hutool / Guava / ClickHouse-JDBC 依赖 | 低 |
| P2.2 MybatisPlusConfig / RedissonConfig 配置类 | 低 |
| P2.3 为 7 张配置表创建 PO + Mapper（MP） | 中 |
| P2.4 CredentialService/TopicService/QueryConfigService/ReportPushConfigService 改写为用 MP | 中 |
| P2.5 `RedisCacheService` 重写为 Redisson 版本（`HitchAggregateCache`），保留 `isAvailable()` 降级 | 中 |
| P2.6 5 个 Processor 的 MySQL 写入改 MP | 中 |
| P2.7 `TableMappingService` 的动态 DDL 部分**保留 JdbcTemplate**（新 bean `DynamicDdlExecutor`） | ⚠ 关键 |
| P2.8 Hutool 按需引入（`WecomPushService` 改 `HttpUtil`，其他暂不动） | 低 |
| P2.9 Guava `Cache` 用于 `field_mappings` 本地缓存（60s TTL） | 低 |
| P2.10 全量测试 + diff 工具验证 FAIL=0 | **阻塞** |

**回滚**：每个子任务独立 commit，出问题单独 revert。

### 阶段 P3：ClickHouse 接入（**性能优化核心**）

| 任务 | 风险 |
|---|---|
| P3.1 `schema/clickhouse/` 目录，5 张表 DDL（ReplacingMergeTree + PARTITION BY event_date） | 低 |
| P3.2 `common/config/ClickHouseConfig.java`（独立数据源，bean 名 `clickHouseJdbcTemplate`） | 中 |
| P3.3 `dashboard/infrastructure/clickhouse/DashboardClickHouseRepository`（读路径） | **高**（SQL 方言差异） |
| P3.4 5 个 Processor 加 CH 双写（`jdbcTemplate.batchUpdate`） | **高** |
| P3.5 `ch_writeback_queue` MySQL 影子表 + 补偿 `@Scheduled` 任务 | 中 |
| P3.6 `application.yml` 特性开关 `loganalysis.clickhouse.enabled/read-source/dual-write` | 低 |
| P3.7 历史数据 dump 迁移脚本 `tools/migrate_mysql_to_ch.sh` | 中 |
| P3.8 性能回归：对比 §P0 基线，验证 P99 ≤ 2s | **阻塞** |
| P3.9 diff 工具验证 FAIL=0（read-source 切回 mysql 与切 clickhouse 两种模式都要过） | **阻塞** |

**回滚**：`loganalysis.clickhouse.read-source=mysql` + `dual-write=false`，代码不回滚也能退回 MySQL。

### 阶段 P4：domain 充血模型深化（可选）

**评估**：若 P1-P3 完成后代码已足够清晰，**P4 可砍**（简洁优先原则）。

| 任务 | 收益 |
|---|---|
| P4.1 `Credential.rotateSecret()` 等方法下沉到 `Credential` 聚合根 | 中 |
| P4.2 `HitchError.aggregateWith(HitchError other)` 聚合逻辑下沉 | 中 |
| P4.3 引入领域事件 `HitchErrorAggregatedEvent`，解耦 processor 和 insert_records | **低**（本项目事件边界单一，过度设计风险） |

**建议**：**默认跳过 P4**。若评审期用户坚持，执行 P4.1 和 P4.2。

### 阶段 P5：文档同步

| 任务 |
|---|
| P5.1 重写 `README.md`（更新架构图 / 技术栈 / 性能指标） |
| P5.2 更新 `RUNBOOK.md`（ClickHouse 部署章节、特性开关、灰度步骤） |
| P5.3 更新 `MIGRATION_PLAN.md` 或新增 `REFACTOR_LOG.md`（记录本次改造） |
| P5.4 `src/main/resources/schema/README.md` 加 CH 章节 |
| P5.5 `tools/DIFF_REPORT.md` 补充"ClickHouse 读路径下的字段类型差异"（如 CH 返回 `Int32` vs MySQL `int(11)`） |

---

## 8. 测试策略（148 条测试保活）

### 8.1 红线

| 红线 | 度量 |
|---|---|
| `mvn test` 通过数 ≥ 148 | 每阶段结束必跑 |
| `tools/smoke_test.sh` 33/33 PASS | P1 末、P2 末、P3 末 |
| `tools/diff_py_vs_java.py` FAIL=0, PASS≥19 | P2 末、P3 末 |

### 8.2 新增测试

| 阶段 | 新增 | 内容 |
|---|---|---|
| P2 | +5 | MybatisPlusConfig 启动、Credential MP CRUD、Redisson 降级、Hutool HttpUtil 调用 Mock |
| P3 | +8 | ClickHouseConfig 启动、DashboardClickHouseRepository 查询、双写补偿、特性开关切换、分区按日期正确 |
| P3 | +3 | 性能基线测试（`@Tag("performance")`，CI 跳过，本地手动跑） |

**测试分层**：
- 单元测试：`*Test.java`（纯 Mockito）
- MockMvc 测试：`*ControllerTest.java`（不变）
- 集成测试（可选）：`@SpringBootTest` + Testcontainers（MySQL + Redis + ClickHouse），P3 期评估是否纳入

---

## 9. 风险清单与回滚预案

### 9.1 风险清单

| 编号 | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | CH 与 MySQL 聚合结果偏差（ReplacingMergeTree 异步去重延迟） | 中 | 前端看到闪动数据 | 查询带 `FINAL` 或 `SELECT ... GROUP BY ... argMax` 去重；接受 <5s 收敛 |
| R2 | MP 的 underline 驼峰转换和现有 SQL 不一致 | 中 | Controller 返回字段名变化 | `MybatisPlusConfig` 显式设置 `map-underscore-to-camel-case=true`（与 application.yml 已有配置对齐） |
| R3 | Redisson 3.27 与 Lettuce 冲突 | 低 | 启动失败 | `spring-boot-starter-data-redis` 保留或显式排除 Lettuce，采用 Redisson 的 `RedissonConnectionFactory` |
| R4 | `TableMappingService` 的动态 DDL 在混用 MP 和 JdbcTemplate 后事务边界错位 | 中 | 部分建表失败 | 两种 template 共用同一 `DataSourceTransactionManager`，测试覆盖混合调用 |
| R5 | ClickHouse 单机故障 | 中 | Dashboard 查询全挂 | 特性开关 `read-source=mysql` 一键降级 |
| R6 | CH 双写 MySQL 成功 + CH 失败，补偿任务未跑导致数据漂移 | 中 | 统计数字少 | `ch_writeback_queue` 积压告警（count > 100 告 P2） |
| R7 | 148 测试因 package 变动大面积挂 | 高 | 阻塞 P1 | P1 用 IDE 批量 refactor，**不手写**，配合 `mvn test` 循环 |
| R8 | Fernet 密钥意外被 MP 默认配置覆盖 | 低 | 历史密文全挂 | MP 不碰 `api_credentials.secret_id/secret_key` 的加密逻辑，只做 CRUD |
| R9 | `/api/report/screenshot` Playwright 首次下载失败 | 低（已知） | 截图失败 | 不在本次范围 |

### 9.2 每阶段回滚命令

```bash
# P1 回滚
git revert <P1-merge-commit>

# P2 回滚
git revert <P2-framework-commit>
# 或单独回滚某个子任务 commit

# P3 回滚（不 revert 代码，直接改配置）
echo "loganalysis.clickhouse.enabled=false" >> application-prod.yml
echo "loganalysis.clickhouse.read-source=mysql" >> application-prod.yml
bash start_java.sh restart
```

---

## 10. 未验证假设清单

按规则 **"方案输出前必须列出所有未验证假设"**，以下全部标为**待验证**：

| # | 假设 | 验证方式 | 阶段 |
|---|------|---------|------|
| A1 | 5 张业务表单表日增 ≥ 50w 行，总量 ≥ 500w 行，CH 有明显收益 | 生产库 `SELECT COUNT(*)` + `MAX-MIN(create_time)` | **P0** |
| A2 | README 提到的 20s 慢接口是 dashboard 的 statistics/aggregation | 生产环境复现 + 计时 | **P0** |
| A3 | 慢查询的瓶颈是 MySQL GROUP BY 而非网络/应用层 | `EXPLAIN ANALYZE` + APM | **P0** |
| A4 | 生产环境有资源部署 ClickHouse（至少 4C8G 单实例） | 与 SRE 确认 | **P0** |
| A5 | MyBatis-Plus 3.5.7 与 Spring Boot 2.7.18 + MySQL Connector 8.0.33 无兼容问题 | `mvn dependency:tree` + 启动冒烟 | **P2** |
| A6 | Redisson 3.27.2 的 `RMap.expireAt` 行为与现有 Spring Data Redis HEXPIRE 等价（Redis 7+） | 单测覆盖 | **P2** |
| A7 | ClickHouse JDBC 0.6.x 驱动在 JDK 11 上稳定 | 冒烟测试 | **P3** |
| A8 | `ReplacingMergeTree` 的去重语义满足现有 UPSERT 对 `total_count` 的累计需求 | 集成测试：模拟多次 UPSERT，验证最终 SUM 正确 | **P3** |
| A9 | 生产环境 MySQL 主库可额外承担 `ch_writeback_queue` 写入（约每次处理 +1 INSERT） | 压测对比 | **P3** |
| A10 | Spring Boot 2.7 仍在官方维护期 | 查官方 EOL 公告 | P2 前一次性 |

**[Contains Unverified Assumptions]** — 以上 10 条全部待验证。

---

## 11. 待评审决策点

请在下述每项选择或补充后再开始编码：

| # | 决策点 | 方案候选 | 推荐 |
|---|---|---|---|
| D1 | P4（domain 充血模型）是否做？ | 做 / 不做 | **不做**（简洁优先） |
| D2 | 是否接受"CH 查询可能有 <5s 异步去重窗口"？ | 接受 / 不接受（强一致） | **接受**（强一致走 FINAL 代价大） |
| D3 | 双写失败补偿任务告警阈值 | 队列长度 / 失败率 | `count > 100` 告 P2 |
| D4 | 历史数据迁移时机 | 上线前全量 / 上线后增量追溯 | **上线前全量**（避免运行期切换） |
| D5 | MP 替换范围是否扩大到 `log_records` / `analysis_results`？ | 扩大 / 保持 | **保持**（两表仅调度器偶尔写入，替换无收益） |
| D6 | Hutool 是否替换 `commons-lang3`？ | 替换 / 并存 | **并存**，新代码用 Hutool，老代码不动 |
| D7 | 是否给 `/api/dashboard/overview` 加本地 Guava Cache（TTL 10s）？ | 加 / 不加 | **加**（overview 是首页高频，10s 陈旧可接受） |
| D8 | CH 部署形态 | 单机 / 主从 / 分片集群 | **单机**起步，后续按 QPS 演进 |
| D9 | 是否在本次引入 Spring Boot Actuator + Prometheus？ | 引入 / 不引入 | **不引入**（已在 RUNBOOK 作为可选项，非本次范围） |
| D10 | 分支策略 | 单 PR / 按阶段 PR | **按阶段 PR**（每阶段独立 review 和回滚） |

---

## 12. 预估工时与交付物

| 阶段 | 工时（工程师 · 小时） | 产出 |
|---|---|---|
| P0 诊断 | 4 | `PERF_BASELINE.md` + 方案调整（如需） |
| P1 DDD 搬迁 | 12 | 全部 61 文件就位 + 测试全绿 |
| P2 框架替换 | 16 | MP/Redisson/Hutool/Guava 接入 + 5 个配置 Service 迁移完 |
| P3 ClickHouse | 24 | 双存储就位 + 特性开关 + 性能达标 |
| P4 充血模型（可选） | 8 | —— 默认跳过 —— |
| P5 文档 | 4 | README/RUNBOOK 重写 |
| **合计** | **60（不含 P4）** | |

---

## 13. 需要用户提供的信息（编码前必备）

1. **生产库 5 张表的行数与日增量**（验证 A1/A2）
2. **20s 慢接口的具体 URL + 典型参数**（验证 A3）
3. **生产 SRE 是否确认可部署 ClickHouse**（A4）
4. **是否存在多实例部署计划**（影响 Redisson 分布式锁决策）
5. **§11 的 D1-D10 决策**

---

**评审意见回填处**（用户填写）：

- [ ] 方案总体方向认可 / 不认可
- [ ] §11 决策点：D1=___ D2=___ D3=___ D4=___ D5=___ D6=___ D7=___ D8=___ D9=___ D10=___
- [ ] §13 信息能否提供？
- [ ] 是否需要调整阶段顺序（如先做 P5 文档以反向驱动对齐）？
- [ ] 其他意见：
