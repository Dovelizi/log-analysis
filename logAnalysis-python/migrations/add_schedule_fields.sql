-- 为query_configs表添加定时查询相关字段
-- 用于支持配置定时查询的频率

-- 添加schedule_enabled字段（是否启用定时查询）
ALTER TABLE query_configs ADD COLUMN schedule_enabled TINYINT DEFAULT 0 COMMENT '是否启用定时查询: 0=禁用, 1=启用';

-- 添加schedule_interval字段（定时查询间隔，单位秒）
ALTER TABLE query_configs ADD COLUMN schedule_interval INT DEFAULT 300 COMMENT '定时查询间隔(秒)';

-- 注意：如果字段已存在会报错，可忽略
-- 执行方式：mysql -u用户名 -p密码 数据库名 < migrations/add_schedule_fields.sql
