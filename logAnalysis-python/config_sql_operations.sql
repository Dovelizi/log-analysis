-- ============================================
-- MySQL配置数据操作SQL语句示例
-- 数据库: config_db
-- 表: sys_config
-- ============================================

USE config_db;

-- ============================================
-- 1. 基本插入语句
-- ============================================

-- 插入单条配置
INSERT INTO sys_config (config_name, config_key, config_value, config_type, description)
VALUES ('新配置项', 'new.config.key', 'value123', 'string', '这是一个新的配置项');

-- 插入多条配置
INSERT INTO sys_config (config_name, config_key, config_value, config_type, description) VALUES
('配置A', 'config.key.a', 'valueA', 'string', '配置A的描述'),
('配置B', 'config.key.b', '100', 'number', '配置B的描述'),
('配置C', 'config.key.c', 'true', 'boolean', '配置C的描述');


-- ============================================
-- 2. 处理重复配置 - INSERT ... ON DUPLICATE KEY UPDATE
-- (推荐方式：存在则更新，不存在则插入)
-- ============================================

-- 单条配置的插入或更新
INSERT INTO sys_config (config_name, config_key, config_value, config_type, description)
VALUES ('系统名称', 'system.name', 'CLS日志分析系统V2', 'string', '系统显示名称-已更新')
ON DUPLICATE KEY UPDATE
    config_name = VALUES(config_name),
    config_value = VALUES(config_value),
    config_type = VALUES(config_type),
    description = VALUES(description);

-- 批量插入或更新多条配置
INSERT INTO sys_config (config_name, config_key, config_value, config_type, description) VALUES
('日志保留天数', 'log.retention.days', '60', 'number', '日志数据保留天数-已延长'),
('最大查询条数', 'query.max.limit', '2000', 'number', '单次查询最大返回条数-已增加'),
('新增配置X', 'config.new.x', 'newValue', 'string', '这是新增的配置')
ON DUPLICATE KEY UPDATE
    config_name = VALUES(config_name),
    config_value = VALUES(config_value),
    config_type = VALUES(config_type),
    description = VALUES(description);


-- ============================================
-- 3. 处理重复配置 - REPLACE INTO
-- (注意：会删除旧记录再插入新记录，id会变化)
-- ============================================

-- 使用REPLACE INTO (如果存在则删除后重新插入)
REPLACE INTO sys_config (config_name, config_key, config_value, config_type, description)
VALUES ('替换测试', 'replace.test.key', 'replaced_value', 'string', '使用REPLACE INTO插入或替换');


-- ============================================
-- 4. 更新语句
-- ============================================

-- 根据config_key更新配置值
UPDATE sys_config 
SET config_value = 'new_value', 
    description = '更新后的描述'
WHERE config_key = 'system.name';

-- 批量更新同类型的配置
UPDATE sys_config 
SET is_enabled = 1 
WHERE config_type = 'number';

-- 条件更新
UPDATE sys_config 
SET config_value = '90'
WHERE config_key = 'log.retention.days' AND is_enabled = 1;


-- ============================================
-- 5. 查询语句
-- ============================================

-- 查询所有启用的配置
SELECT * FROM sys_config WHERE is_enabled = 1;

-- 根据config_key查询单个配置
SELECT config_value FROM sys_config WHERE config_key = 'system.name';

-- 模糊查询配置名称
SELECT * FROM sys_config WHERE config_name LIKE '%日志%';

-- 查询指定类型的配置
SELECT config_key, config_value FROM sys_config WHERE config_type = 'number';

-- 查询最近更新的配置
SELECT * FROM sys_config ORDER BY updated_at DESC LIMIT 10;


-- ============================================
-- 6. 删除语句
-- ============================================

-- 根据config_key删除配置
DELETE FROM sys_config WHERE config_key = 'replace.test.key';

-- 软删除(禁用配置)
UPDATE sys_config SET is_enabled = 0 WHERE config_key = 'config.to.disable';


-- ============================================
-- 7. 存储过程：安全的配置插入/更新
-- ============================================

DELIMITER //

CREATE PROCEDURE IF NOT EXISTS upsert_config(
    IN p_config_name VARCHAR(100),
    IN p_config_key VARCHAR(100),
    IN p_config_value TEXT,
    IN p_config_type VARCHAR(20),
    IN p_description VARCHAR(500)
)
BEGIN
    INSERT INTO sys_config (config_name, config_key, config_value, config_type, description)
    VALUES (p_config_name, p_config_key, p_config_value, p_config_type, p_description)
    ON DUPLICATE KEY UPDATE
        config_name = p_config_name,
        config_value = p_config_value,
        config_type = p_config_type,
        description = p_description;
END //

DELIMITER ;

-- 调用存储过程示例
-- CALL upsert_config('测试配置', 'test.config', 'test_value', 'string', '通过存储过程插入的配置');


-- ============================================
-- 8. 查看表结构和索引
-- ============================================

-- 查看表结构
DESCRIBE sys_config;

-- 查看索引
SHOW INDEX FROM sys_config;

-- 查看建表语句
SHOW CREATE TABLE sys_config;
