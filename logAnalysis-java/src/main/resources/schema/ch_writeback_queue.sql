-- ClickHouse 双写补偿队列（MySQL 主库）。
--
-- 当 Processor 写 MySQL 成功但异步写 ClickHouse 失败时，失败任务会被记录到本表；
-- ChWritebackRunner（@Scheduled 每 30s）会重放失败记录，成功则删除行。
--
-- target_table：被写的 CH 表名（gw_hitch_error_mothod / control_hitch_error_mothod / ...）
-- operation：insert / update（决定重放时用哪种 SQL）
-- payload_json：Processor 构造 PO 时的原始字段 JSON，补偿任务按此重建 INSERT/UPDATE
-- target_id：MySQL 主键 id（update 时用来定位 CH 行；insert 时也写入以保持 id 一致）

CREATE TABLE IF NOT EXISTS ch_writeback_queue (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    target_table  VARCHAR(64) NOT NULL COMMENT 'CH 目标表名',
    operation     VARCHAR(16) NOT NULL COMMENT 'insert / update',
    target_id     BIGINT NOT NULL COMMENT 'MySQL 主键 id',
    payload_json  LONGTEXT NOT NULL COMMENT '操作载荷 JSON',
    retry_count   INT DEFAULT 0 COMMENT '重试次数',
    last_error    VARCHAR(1024) DEFAULT NULL COMMENT '最近一次失败原因',
    create_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '入队时间',
    next_retry_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    PRIMARY KEY (id),
    KEY idx_next_retry (next_retry_at),
    KEY idx_target_table (target_table, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ClickHouse 双写补偿队列';
