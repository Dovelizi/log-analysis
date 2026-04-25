-- 顺风车控制层方法耗时监控日志表（ClickHouse 镜像）。
-- 对应 MySQL 表：hitch_control_cost_time。
--
-- 特殊性：该表每条日志独立 INSERT，不做聚合也不做去重，因此用普通 MergeTree
-- 而非 ReplacingMergeTree；OLAP 场景下该表受益最大（每日增量最高）。

CREATE TABLE IF NOT EXISTS cls_logs_ch.hitch_control_cost_time
(
    id          UInt64,
    trace_id    String,
    method_name LowCardinality(String),
    content     String,
    time_cost   Int32,
    log_time    String,
    create_time DateTime('Asia/Shanghai'),
    event_date  Date MATERIALIZED toDate(create_time)
)
ENGINE = MergeTree
PARTITION BY event_date
ORDER BY (event_date, method_name, time_cost, id)
SETTINGS index_granularity = 8192;
