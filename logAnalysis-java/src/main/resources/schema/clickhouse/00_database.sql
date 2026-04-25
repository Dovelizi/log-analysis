-- ClickHouse 数据库初始化（手动执行一次）
--
-- 执行方式：
--   clickhouse-client --multiquery < src/main/resources/schema/clickhouse/00_database.sql
--   clickhouse-client --multiquery < src/main/resources/schema/clickhouse/gw_hitch_error_mothod.sql
--   ... 其他 4 个表同理
--
-- 设计决策（REFACTOR_PLAN §4.3）：
--   - 引擎统一用 ReplacingMergeTree(update_time)：同 id 记录按 update_time 新值覆盖，
--     对齐 MySQL UPSERT 语义；前端查询可带 FINAL 关键字强制去重
--   - PARTITION BY event_date：按天分区，便于 TTL / DROP PARTITION 清理
--   - ORDER BY 主键选择覆盖 90% 查询模式（时间 + 聚合维度）
--   - LowCardinality(String)：枚举值少的字段（method_name、sp_name）字典压缩 5-10x
--
-- 重要约束：
--   - CH 表结构必须与 MySQL 对应业务表字段一一对应（Processor 双写时用同样的字段名）
--   - id 字段与 MySQL 主键共用，Processor 写 MySQL 成功拿到 id 后再异步写 CH

CREATE DATABASE IF NOT EXISTS cls_logs_ch;
