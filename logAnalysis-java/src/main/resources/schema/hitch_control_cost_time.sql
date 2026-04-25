-- 对齐 Python services/hitch_control_cost_time_processor.py
CREATE TABLE IF NOT EXISTS hitch_control_cost_time (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    trace_id VARCHAR(255) NOT NULL COMMENT '链路追踪ID',
    method_name VARCHAR(255) NOT NULL COMMENT '方法或接口名称',
    content VARCHAR(10240) NOT NULL COMMENT '响应内容',
    time_cost INT NOT NULL COMMENT '方法执行耗时（毫秒）',
    log_time VARCHAR(255) NOT NULL COMMENT '日志记录时间',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录入库时间',
    PRIMARY KEY (id),
    KEY idx_mn_tc (method_name, time_cost),
    KEY idx_ct (create_time),
    KEY idx_timecost (time_cost),
    KEY idx_ct_method (create_time, method_name),
    KEY idx_ct_timecost (create_time, time_cost),
    KEY idx_ct_method_tc (create_time, method_name, time_cost)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='顺风车控制层方法耗时监控日志表';
