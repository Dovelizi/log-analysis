-- 顺风车控制层错误方法聚合监控表（ClickHouse 镜像）。
-- 对应 MySQL 表：control_hitch_error_mothod。
-- 注意：error_code 是 String（与 MySQL VARCHAR(255) 对齐），区别于 gw_hitch_error_mothod 的 Int32。

CREATE TABLE IF NOT EXISTS cls_logs_ch.control_hitch_error_mothod
(
    id            UInt64,
    method_name   LowCardinality(String),
    error_code    String,
    error_message String,
    content       String,
    count         UInt32,
    total_count   UInt64,
    create_time   DateTime('Asia/Shanghai'),
    update_time   DateTime('Asia/Shanghai'),
    event_date    Date MATERIALIZED toDate(create_time)
)
ENGINE = ReplacingMergeTree(update_time)
PARTITION BY event_date
ORDER BY (event_date, method_name, error_code, id)
SETTINGS index_granularity = 8192;
