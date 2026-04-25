-- 为query_configs表添加processor_type和target_table字段
-- 用于支持自定义数据处理器绑定

-- 添加processor_type字段（如果已存在会报错，可忽略）
ALTER TABLE query_configs ADD COLUMN processor_type VARCHAR(50) DEFAULT NULL COMMENT '数据处理器类型: gw_hitch_error 等';

-- 添加target_table字段（如果已存在会报错，可忽略）
ALTER TABLE query_configs ADD COLUMN target_table VARCHAR(255) DEFAULT NULL COMMENT '目标数据表名';

-- 创建gw_hitch_error_mothod表（如果不存在）
CREATE TABLE IF NOT EXISTS gw_hitch_error_mothod (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    method_name VARCHAR(255) DEFAULT NULL COMMENT '发生异常的接口或方法名称',
    error_code INT DEFAULT NULL COMMENT '错误码',
    error_message VARCHAR(1024) DEFAULT NULL COMMENT '错误信息',
    content VARCHAR(10240) DEFAULT NULL COMMENT '响应内容',
    count INT DEFAULT 0 COMMENT '单次聚合周期内的错误次数',
    total_count BIGINT DEFAULT 0 COMMENT '历史累计错误总次数',
    create_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    KEY idx_ct (create_time) COMMENT '按创建时间查询的索引',
    KEY idx_ut (update_time) COMMENT '按更新时间查询的索引',
    KEY idx_error_code (error_code) COMMENT '按错误码快速筛选的索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='网关顺风车业务错误方法聚合监控表';
