-- 对齐 Python services/control_hitch_processor.py 的 _init_table
CREATE TABLE IF NOT EXISTS control_hitch_error_mothod (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    method_name VARCHAR(255) DEFAULT NULL COMMENT '出错的方法名',
    error_code VARCHAR(255) DEFAULT NULL COMMENT '错误码',
    error_message VARCHAR(1024) DEFAULT NULL COMMENT '错误信息',
    content VARCHAR(10240) DEFAULT NULL COMMENT '响应内容',
    count INT DEFAULT 0 COMMENT '单次聚合周期内的错误次数',
    total_count BIGINT DEFAULT 0 COMMENT '累计错误总数',
    create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    KEY idx_ct (create_time),
    KEY idx_ut (update_time),
    KEY idx_ct_method (create_time, method_name),
    KEY idx_ct_errcode (create_time, error_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='顺风车错误方法监控表';
