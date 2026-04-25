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
    -- 二期性能补丁：Dashboard 的 hitch-control-cost-time/statistics 接口 11 条 SQL
    -- 全部按 create_time BETWEEN 过滤；原表仅有 (method_name, time_cost) 复合索引
    -- 无法覆盖，加 idx_ct 后 7s → 0.45s
    KEY idx_ct (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='顺风车控制层方法耗时监控日志表';
