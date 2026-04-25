-- 添加推送模式相关字段到 report_push_config 表
-- 执行前请备份数据库

-- 检查并添加 push_mode 字段
ALTER TABLE report_push_config 
ADD COLUMN IF NOT EXISTS push_mode VARCHAR(32) DEFAULT 'daily' COMMENT '推送模式: daily=每日定时, date=指定日期, relative=相对日期';

-- 检查并添加 push_date 字段  
ALTER TABLE report_push_config 
ADD COLUMN IF NOT EXISTS push_date DATE NULL COMMENT '指定推送日期(push_mode=date时使用)';

-- 检查并添加 relative_days 字段
ALTER TABLE report_push_config 
ADD COLUMN IF NOT EXISTS relative_days INT DEFAULT 0 COMMENT '相对天数(push_mode=relative时使用，T-N的N值)';

-- 为已存在的配置设置默认值
UPDATE report_push_config 
SET push_mode = 'daily' 
WHERE push_mode IS NULL OR push_mode = '';

UPDATE report_push_config 
SET relative_days = 0 
WHERE relative_days IS NULL;