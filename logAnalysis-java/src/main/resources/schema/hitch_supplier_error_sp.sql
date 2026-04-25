-- 对齐 Python services/hitch_supplier_error_sp_processor.py 的 _init_table
CREATE TABLE IF NOT EXISTS hitch_supplier_error_sp (
    id INT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    sp_id INT DEFAULT NULL COMMENT '供应商Id',
    sp_name VARCHAR(255) DEFAULT NULL COMMENT '供应商名称',
    method_name VARCHAR(255) DEFAULT NULL COMMENT '发生异常的接口或方法名称',
    content VARCHAR(10240) DEFAULT NULL COMMENT '原始错误上下文或日志详情',
    error_code INT DEFAULT NULL COMMENT '错误码',
    error_message VARCHAR(255) DEFAULT NULL COMMENT '简要错误信息摘要',
    count INT DEFAULT 0 COMMENT '单次聚合周期内的错误次数',
    total_count BIGINT DEFAULT 0 COMMENT '当天累计错误总次数',
    create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录首次创建时间',
    update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    KEY idx_create_time (create_time),
    KEY idx_ct_sp (create_time, sp_id),
    KEY idx_ct_errcode (create_time, error_code),
    KEY idx_sp_ct (sp_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='顺风车供应商维度错误明细聚合表';
