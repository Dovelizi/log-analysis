-- 创建数据库
CREATE DATABASE IF NOT EXISTS config_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户并授权
CREATE USER IF NOT EXISTS 'config_user'@'localhost' IDENTIFIED BY 'Config@123456';
GRANT ALL PRIVILEGES ON config_db.* TO 'config_user'@'localhost';
FLUSH PRIVILEGES;

-- 使用数据库
USE config_db;

-- 创建配置表
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    config_name VARCHAR(100) NOT NULL COMMENT '配置名称',
    config_key VARCHAR(100) NOT NULL COMMENT '配置键名(唯一)',
    config_value TEXT COMMENT '配置值',
    config_type VARCHAR(20) DEFAULT 'string' COMMENT '配置类型(string/number/json/boolean)',
    description VARCHAR(500) DEFAULT '' COMMENT '配置描述',
    is_enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用(0:禁用 1:启用)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_config_key (config_key),
    INDEX idx_config_name (config_name),
    INDEX idx_is_enabled (is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- 插入示例配置数据
INSERT INTO sys_config (config_name, config_key, config_value, config_type, description) VALUES
('系统名称', 'system.name', 'CLS日志分析系统', 'string', '系统显示名称'),
('系统版本', 'system.version', '1.0.0', 'string', '当前系统版本号'),
('日志保留天数', 'log.retention.days', '30', 'number', '日志数据保留天数'),
('最大查询条数', 'query.max.limit', '1000', 'number', '单次查询最大返回条数'),
('启用缓存', 'cache.enabled', 'true', 'boolean', '是否启用查询缓存'),
('默认地域', 'cls.default.region', 'ap-guangzhou', 'string', 'CLS默认地域'),
('API超时时间', 'api.timeout.seconds', '30', 'number', 'API请求超时时间(秒)'),
('告警配置', 'alert.config', '{"enabled":true,"threshold":100,"email":"admin@example.com"}', 'json', '告警相关配置');

-- 报表推送配置表
CREATE TABLE IF NOT EXISTS `report_push_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `push_type` varchar(64) NOT NULL COMMENT 'wecom=企微, email=邮箱',
  `webhook_url` text COMMENT '企微机器人webhook地址',
  `email_config` text COMMENT '邮箱配置JSON',
  `schedule_enabled` tinyint DEFAULT '0' COMMENT '是否启用定时推送',
  `schedule_cron` varchar(125) DEFAULT NULL COMMENT 'Cron表达式',
  `schedule_time` varchar(64) DEFAULT NULL COMMENT '每日推送时间 HH:MM',
  `last_push_time` timestamp NULL DEFAULT NULL COMMENT '上次推送时间',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 报表推送记录表
CREATE TABLE IF NOT EXISTS `report_push_log` (
  `id` INT PRIMARY KEY AUTO_INCREMENT,
  `config_id` INT NOT NULL COMMENT '推送配置ID',
  `config_name` VARCHAR(255) COMMENT '推送配置名称',
  `push_type` VARCHAR(64) NOT NULL COMMENT '推送类型: wecom/email',
  `push_mode` VARCHAR(64) COMMENT '推送模式: image/markdown',
  `report_date` VARCHAR(20) NOT NULL COMMENT '报表数据日期',
  `status` VARCHAR(20) DEFAULT 'pending' COMMENT '推送状态: pending/success/failed',
  `webhook_url` TEXT COMMENT 'Webhook地址',
  `image_data` LONGTEXT COMMENT '推送的图片base64',
  `response_text` TEXT COMMENT '推送响应内容',
  `error_message` TEXT COMMENT '错误信息',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '推送时间',
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
