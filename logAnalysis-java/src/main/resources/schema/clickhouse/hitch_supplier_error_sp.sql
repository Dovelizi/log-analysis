-- 顺风车供应商维度错误明细聚合表（ClickHouse 镜像）。
-- 对应 MySQL 表：hitch_supplier_error_sp。

CREATE TABLE IF NOT EXISTS cls_logs_ch.hitch_supplier_error_sp
(
    id            UInt64,
    sp_id         Int32,
    sp_name       LowCardinality(String),
    method_name   LowCardinality(String),
    content       String,
    error_code    Int32,
    error_message String,
    count         UInt32,
    total_count   UInt64,
    create_time   DateTime('Asia/Shanghai'),
    update_time   DateTime('Asia/Shanghai'),
    event_date    Date MATERIALIZED toDate(create_time)
)
ENGINE = ReplacingMergeTree(update_time)
PARTITION BY event_date
ORDER BY (event_date, sp_id, method_name, error_code, id)
SETTINGS index_granularity = 8192;
