-- 核心配置与日志表，对齐 app.py init_db + models/table_mapping.py 的 _init_mapping_tables

CREATE TABLE IF NOT EXISTS api_credentials (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL UNIQUE,
    secret_id TEXT NOT NULL,
    secret_key TEXT NOT NULL,
    region VARCHAR(50) DEFAULT 'ap-guangzhou',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS log_topics (
    id INT PRIMARY KEY AUTO_INCREMENT,
    credential_id INT NOT NULL,
    region VARCHAR(50) DEFAULT 'ap-guangzhou',
    topic_id VARCHAR(255) NOT NULL,
    topic_name VARCHAR(255),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (credential_id) REFERENCES api_credentials(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS query_configs (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    topic_id INT NOT NULL,
    query_statement TEXT NOT NULL,
    time_range INT DEFAULT 3600,
    limit_count INT DEFAULT 100,
    sort_order VARCHAR(10) DEFAULT 'desc',
    syntax_rule INT DEFAULT 1,
    processor_type VARCHAR(50) DEFAULT NULL COMMENT '数据处理器类型',
    target_table VARCHAR(255) DEFAULT NULL COMMENT '目标数据表名',
    transform_config TEXT DEFAULT NULL COMMENT '字段转换规则JSON',
    filter_config TEXT DEFAULT NULL COMMENT '入库条件配置JSON',
    schedule_enabled TINYINT DEFAULT 0 COMMENT '是否启用定时查询',
    schedule_interval INT DEFAULT 300 COMMENT '定时查询间隔(秒)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (topic_id) REFERENCES log_topics(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS log_records (
    id INT PRIMARY KEY AUTO_INCREMENT,
    query_config_id INT,
    topic_id VARCHAR(255),
    log_time TIMESTAMP NULL,
    log_content LONGTEXT,
    log_json LONGTEXT,
    source VARCHAR(255),
    collected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (query_config_id) REFERENCES query_configs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS analysis_results (
    id INT PRIMARY KEY AUTO_INCREMENT,
    query_config_id INT,
    analysis_type VARCHAR(50),
    result_data LONGTEXT,
    columns TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (query_config_id) REFERENCES query_configs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS topic_table_mappings (
    id INT PRIMARY KEY AUTO_INCREMENT,
    topic_id INT NOT NULL,
    table_name VARCHAR(255) NOT NULL UNIQUE,
    table_display_name VARCHAR(255),
    description TEXT,
    field_config JSON NOT NULL,
    filter_config JSON,
    auto_collect TINYINT DEFAULT 1,
    status TINYINT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_topic_id (topic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS field_mappings (
    id INT PRIMARY KEY AUTO_INCREMENT,
    mapping_id INT NOT NULL,
    source_field VARCHAR(255) NOT NULL,
    target_column VARCHAR(255) NOT NULL,
    field_type VARCHAR(50) DEFAULT 'TEXT',
    is_required TINYINT DEFAULT 0,
    default_value TEXT,
    transform_rule TEXT,
    empty_handler VARCHAR(50) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_mapping_id (mapping_id),
    FOREIGN KEY (mapping_id) REFERENCES topic_table_mappings(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS collection_logs (
    id INT PRIMARY KEY AUTO_INCREMENT,
    mapping_id INT NOT NULL,
    collected_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    error_count INT DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_mapping_id (mapping_id),
    FOREIGN KEY (mapping_id) REFERENCES topic_table_mappings(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
