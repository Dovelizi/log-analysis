-- 对齐 Python services/hitch_supplier_error_total_processor.py
CREATE TABLE IF NOT EXISTS hitch_supplier_error_total (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    sp_id INT DEFAULT NULL COMMENT '供应商Id',
    method_name VARCHAR(255) DEFAULT NULL COMMENT '发生异常的接口或方法名称',
    error_code INT DEFAULT NULL COMMENT '错误码',
    error_message VARCHAR(255) DEFAULT NULL COMMENT '简要错误信息摘要',
    content VARCHAR(10240) DEFAULT NULL COMMENT '响应内容',
    count INT DEFAULT 0 COMMENT '单次聚合周期内的错误次数',
    total_count BIGINT DEFAULT 0 COMMENT '当天累计错误总次数',
    create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录首次创建时间',
    update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='顺风车供应商维度错误聚合统计表';
