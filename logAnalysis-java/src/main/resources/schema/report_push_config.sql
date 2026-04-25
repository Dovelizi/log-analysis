-- 对齐 Python routes/report_routes.py init_report_config_table
-- 含所有 ALTER 后的最新字段
CREATE TABLE IF NOT EXISTS report_push_config (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    push_type VARCHAR(64) NOT NULL COMMENT 'wecom=企微, email=邮箱',
    webhook_url TEXT COMMENT '企微机器人webhook地址',
    email_config TEXT COMMENT '邮箱配置JSON',
    schedule_enabled TINYINT DEFAULT 0 COMMENT '是否启用定时推送',
    schedule_cron VARCHAR(125) DEFAULT NULL COMMENT 'Cron表达式',
    schedule_time VARCHAR(64) DEFAULT NULL COMMENT '每日推送时间 HH:MM',
    push_mode VARCHAR(32) DEFAULT 'daily' COMMENT '推送模式: daily / date / relative',
    push_date DATE NULL COMMENT '指定推送日期（push_mode=date 时使用）',
    relative_days INT DEFAULT 0 COMMENT '相对天数（push_mode=relative 时使用，T-N 的 N 值）',
    last_push_time TIMESTAMP NULL DEFAULT NULL COMMENT '上次推送时间',
    last_scheduled_push_time TIMESTAMP NULL DEFAULT NULL COMMENT '最后一次定时推送时间',
    create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表定时推送配置';

CREATE TABLE IF NOT EXISTS report_push_log (
    id INT NOT NULL AUTO_INCREMENT,
    config_id INT DEFAULT NULL,
    config_name VARCHAR(255) DEFAULT NULL,
    push_type VARCHAR(64) DEFAULT NULL,
    push_mode VARCHAR(64) DEFAULT NULL COMMENT 'image / markdown',
    report_date VARCHAR(32) DEFAULT NULL,
    status VARCHAR(32) DEFAULT NULL COMMENT 'pending/success/failed',
    webhook_url TEXT,
    image_data LONGTEXT,
    response_text TEXT,
    create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_config (config_id),
    KEY idx_ct (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表推送日志';

CREATE TABLE IF NOT EXISTS hitch_error_log_insert_record (
    id INT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    log_from INT DEFAULT 0 COMMENT '1-control 2-gw 3-supplier_sp 4-supplier_total 5-cost_time',
    sp_id INT DEFAULT 0 COMMENT '服务商ID（log_from=3/4 时有值）',
    method_name VARCHAR(255) DEFAULT NULL,
    content VARCHAR(10240) DEFAULT NULL,
    count INT DEFAULT 0,
    create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_mn (method_name),
    KEY idx_ct (create_time),
    KEY idx_logfrom_ct (log_from, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='顺风车错误日志新增记录表';
