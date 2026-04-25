"""
CLS日志采集与分析系统 - 后端服务
"""
import os
import json
import time
import re
from datetime import datetime, timedelta
from functools import wraps
from flask import Flask, request, jsonify, g
from flask_cors import CORS
from cryptography.fernet import Fernet

# 导入日志配置
from config.logging_config import setup_logging, get_logger, get_api_logger, log_api_call

# 导入数据库配置
from config.database import (
    get_db_connection, get_db_type, dict_cursor,
    row_to_dict, get_placeholder, MYSQL_CONFIG
)

# 导入腾讯云官方SDK
from tencentcloud.common import credential
from tencentcloud.common.profile.client_profile import ClientProfile
from tencentcloud.common.profile.http_profile import HttpProfile
from tencentcloud.cls.v20201016 import cls_client, models
from tencentcloud.common.exception.tencent_cloud_sdk_exception import TencentCloudSDKException

app = Flask(__name__, static_folder='static', static_url_path='/static')
CORS(app, resources={r"/*": {"origins": "*"}})

# 初始化日志系统
import logging
log_level_str = os.environ.get('LOG_LEVEL', 'INFO').upper()
log_level = getattr(logging, log_level_str, logging.INFO)

logger = setup_logging(
    app_name="loganalysis",
    log_level=log_level,
    log_dir=os.path.join(os.path.dirname(__file__), 'logs')
)
logger.info("日志分析系统启动")

# 获取API日志记录器
api_logger = get_api_logger()

# 注册蓝图
from routes.table_mapping_routes import table_mapping_bp
from routes.gw_hitch_routes import gw_hitch_bp
from routes.control_hitch_routes import control_hitch_bp
from routes.dashboard_routes import dashboard_bp
from routes.report_routes import report_bp
app.register_blueprint(table_mapping_bp)
app.register_blueprint(gw_hitch_bp)
app.register_blueprint(control_hitch_bp)
app.register_blueprint(dashboard_bp)
app.register_blueprint(report_bp)


# ==================== 健康检查接口 ====================
@app.route('/api/health', methods=['GET'])
@log_api_call(api_logger)
def health_check():
    """健康检查接口，用于容器和负载均衡器检测服务状态"""
    health_status = {
        'status': 'healthy',
        'timestamp': datetime.now().isoformat(),
        'version': '1.0.0',
        'checks': {}
    }
    
    # 检查数据库连接
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        cursor.execute('SELECT 1')
        cursor.close()
        conn.close()
        health_status['checks']['database'] = 'ok'
    except Exception as e:
        health_status['checks']['database'] = f'error: {str(e)}'
        health_status['status'] = 'unhealthy'
    
    # 检查Redis连接（可选）
    try:
        import redis
        from config.redis_config import REDIS_CONFIG
        r = redis.Redis(**REDIS_CONFIG)
        r.ping()
        health_status['checks']['redis'] = 'ok'
    except Exception as e:
        health_status['checks']['redis'] = f'error: {str(e)}'
        # Redis不可用不影响整体健康状态
    
    status_code = 200 if health_status['status'] == 'healthy' else 503
    return jsonify(health_status), status_code


# 加密密钥文件
KEY_FILE = os.path.join(os.path.dirname(__file__), '.encryption_key')

# SQL占位符
PH = get_placeholder()


def get_encryption_key():
    """获取或生成加密密钥"""
    if os.path.exists(KEY_FILE):
        with open(KEY_FILE, 'rb') as f:
            return f.read()
    else:
        key = Fernet.generate_key()
        with open(KEY_FILE, 'wb') as f:
            f.write(key)
        return key


def encrypt_value(value):
    """加密敏感数据"""
    if not value:
        return ''
    f = Fernet(get_encryption_key())
    return f.encrypt(value.encode()).decode()


def decrypt_value(encrypted_value):
    """解密敏感数据"""
    if not encrypted_value:
        return ''
    f = Fernet(get_encryption_key())
    return f.decrypt(encrypted_value.encode()).decode()


def get_db():
    """获取数据库连接"""
    if 'db' not in g:
        g.db = get_db_connection()
    return g.db


@app.teardown_appcontext
def close_db(exception):
    """关闭数据库连接"""
    db = g.pop('db', None)
    if db is not None:
        db.close()


def init_db():
    """初始化数据库表结构"""
    db_type = get_db_type()
    conn = get_db_connection()
    cursor = conn.cursor()
    
    if db_type == 'mysql':
        # MySQL表结构
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS api_credentials (
                id INT PRIMARY KEY AUTO_INCREMENT,
                name VARCHAR(255) NOT NULL UNIQUE,
                secret_id TEXT NOT NULL,
                secret_key TEXT NOT NULL,
                region VARCHAR(50) DEFAULT 'ap-guangzhou',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS log_topics (
                id INT PRIMARY KEY AUTO_INCREMENT,
                credential_id INT NOT NULL,
                region VARCHAR(50) DEFAULT 'ap-guangzhou',
                topic_id VARCHAR(255) NOT NULL,
                topic_name VARCHAR(255),
                description TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (credential_id) REFERENCES api_credentials(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS query_configs (
                id INT PRIMARY KEY AUTO_INCREMENT,
                name VARCHAR(255) NOT NULL,
                topic_id INT NOT NULL,
                query_statement TEXT NOT NULL,
                time_range INT DEFAULT 3600,
                limit_count INT DEFAULT 100,
                sort_order VARCHAR(10) DEFAULT 'desc',
                syntax_rule INT DEFAULT 1,
                processor_type VARCHAR(50) DEFAULT NULL COMMENT '数据处理器类型: gw_hitch_error 等',
                target_table VARCHAR(255) DEFAULT NULL COMMENT '目标数据表名',
                transform_config TEXT DEFAULT NULL COMMENT '字段转换规则配置JSON',
                schedule_enabled TINYINT DEFAULT 0 COMMENT '是否启用定时查询: 0=禁用, 1=启用',
                schedule_interval INT DEFAULT 300 COMMENT '定时查询间隔(秒)',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                FOREIGN KEY (topic_id) REFERENCES log_topics(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ''')
        
        cursor.execute('''
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS analysis_results (
                id INT PRIMARY KEY AUTO_INCREMENT,
                query_config_id INT,
                analysis_type VARCHAR(50),
                result_data LONGTEXT,
                columns TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (query_config_id) REFERENCES query_configs(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ''')
    else:
        # SQLite表结构
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS api_credentials (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                secret_id TEXT NOT NULL,
                secret_key TEXT NOT NULL,
                region TEXT DEFAULT 'ap-guangzhou',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS log_topics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                credential_id INTEGER NOT NULL,
                region TEXT DEFAULT 'ap-guangzhou',
                topic_id TEXT NOT NULL,
                topic_name TEXT,
                description TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (credential_id) REFERENCES api_credentials(id)
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS query_configs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                topic_id INTEGER NOT NULL,
                query_statement TEXT NOT NULL,
                time_range INTEGER DEFAULT 3600,
                limit_count INTEGER DEFAULT 100,
                sort_order TEXT DEFAULT 'desc',
                syntax_rule INTEGER DEFAULT 1,
                processor_type TEXT DEFAULT NULL,
                target_table TEXT DEFAULT NULL,
                transform_config TEXT DEFAULT NULL,
                schedule_enabled INTEGER DEFAULT 0,
                schedule_interval INTEGER DEFAULT 300,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (topic_id) REFERENCES log_topics(id)
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS log_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query_config_id INTEGER,
                topic_id TEXT,
                log_time TIMESTAMP,
                log_content TEXT,
                log_json TEXT,
                source TEXT,
                collected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (query_config_id) REFERENCES query_configs(id)
            )
        ''')
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS analysis_results (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query_config_id INTEGER,
                analysis_type TEXT,
                result_data TEXT,
                columns TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (query_config_id) REFERENCES query_configs(id)
            )
        ''')
    
    conn.commit()
    
    # 数据库迁移：为已存在的表添加新列
    cursor = conn.cursor()
    try:
        if get_db_type() == 'mysql':
            # 检查 query_configs 表是否有 transform_config 列
            cursor.execute("SHOW COLUMNS FROM query_configs LIKE 'transform_config'")
            if not cursor.fetchone():
                cursor.execute("ALTER TABLE query_configs ADD COLUMN transform_config TEXT DEFAULT NULL COMMENT '字段转换规则配置JSON'")
                conn.commit()
            
            # 检查 query_configs 表是否有 filter_config 列
            cursor.execute("SHOW COLUMNS FROM query_configs LIKE 'filter_config'")
            if not cursor.fetchone():
                cursor.execute("ALTER TABLE query_configs ADD COLUMN filter_config TEXT DEFAULT NULL COMMENT '入库条件配置JSON'")
                conn.commit()
            
            # 迁移 gw_hitch_error_mothod 表的 collected_at 列为 create_time
            cursor.execute("SHOW COLUMNS FROM gw_hitch_error_mothod LIKE 'collected_at'")
            if cursor.fetchone():
                cursor.execute("ALTER TABLE gw_hitch_error_mothod CHANGE COLUMN collected_at create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
                conn.commit()
            
            # 迁移 gw_hitch_error_mothod 表的 error_count 列为 time_cost
            cursor.execute("SHOW COLUMNS FROM gw_hitch_error_mothod LIKE 'error_count'")
            if cursor.fetchone():
                cursor.execute("ALTER TABLE gw_hitch_error_mothod CHANGE COLUMN error_count time_cost INT DEFAULT NULL")
                conn.commit()
            
            # 迁移 control_hitch_error_mothod 表的 collected_at 列为 create_time
            try:
                cursor.execute("SHOW COLUMNS FROM control_hitch_error_mothod LIKE 'collected_at'")
                if cursor.fetchone():
                    cursor.execute("ALTER TABLE control_hitch_error_mothod CHANGE COLUMN collected_at create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
                    conn.commit()
            except Exception as e:
                print(f"control_hitch_error_mothod 迁移警告: {e}")
            
            # 修正表名拼写错误: hithc_supplier_error_total -> hitch_supplier_error_total
            try:
                # 修正 query_configs 表中的 target_table 字段
                cursor.execute("UPDATE query_configs SET target_table = 'hitch_supplier_error_total' WHERE target_table = 'hithc_supplier_error_total'")
                conn.commit()
                # 修正 topic_table_mappings 表中的 table_name 字段
                cursor.execute("UPDATE topic_table_mappings SET table_name = 'hitch_supplier_error_total' WHERE table_name = 'hithc_supplier_error_total'")
                conn.commit()
                # 重命名实际数据表（如果存在错误名称的表）
                cursor.execute("SHOW TABLES LIKE 'hithc_supplier_error_total'")
                if cursor.fetchone():
                    cursor.execute("RENAME TABLE hithc_supplier_error_total TO hitch_supplier_error_total")
                    conn.commit()
            except Exception as e:
                print(f"修正表名拼写错误警告: {e}")
            
            # 删除 hitch_supplier_error_total 表的 collected_at 和 raw_log_id 列
            try:
                cursor.execute("SHOW TABLES LIKE 'hitch_supplier_error_total'")
                if cursor.fetchone():
                    cursor.execute("SHOW COLUMNS FROM hitch_supplier_error_total LIKE 'collected_at'")
                    if cursor.fetchone():
                        cursor.execute("ALTER TABLE hitch_supplier_error_total DROP COLUMN collected_at")
                        conn.commit()
                    cursor.execute("SHOW COLUMNS FROM hitch_supplier_error_total LIKE 'raw_log_id'")
                    if cursor.fetchone():
                        cursor.execute("ALTER TABLE hitch_supplier_error_total DROP COLUMN raw_log_id")
                        conn.commit()
            except Exception as e:
                print(f"hitch_supplier_error_total 迁移警告: {e}")
            
            # 删除 hitch_control_cost_time 表的 collected_at 和 raw_log_id 列
            try:
                cursor.execute("SHOW TABLES LIKE 'hitch_control_cost_time'")
                if cursor.fetchone():
                    cursor.execute("SHOW COLUMNS FROM hitch_control_cost_time LIKE 'collected_at'")
                    if cursor.fetchone():
                        cursor.execute("ALTER TABLE hitch_control_cost_time DROP COLUMN collected_at")
                        conn.commit()
                    cursor.execute("SHOW COLUMNS FROM hitch_control_cost_time LIKE 'raw_log_id'")
                    if cursor.fetchone():
                        cursor.execute("ALTER TABLE hitch_control_cost_time DROP COLUMN raw_log_id")
                        conn.commit()
            except Exception as e:
                print(f"hitch_control_cost_time 迁移警告: {e}")
    except Exception as e:
        print(f"数据库迁移警告: {e}")
    
    # 数据迁移：将 field_mappings 中的转换规则迁移到 query_configs.transform_config
    try:
        migrate_field_mappings_to_query_configs(conn)
    except Exception as e:
        print(f"转换规则迁移警告: {e}")
    
    conn.close()


def migrate_field_mappings_to_query_configs(conn):
    """
    将 field_mappings 表中的转换规则迁移到 query_configs.transform_config
    只迁移那些 transform_config 为空的查询配置
    """
    cursor = conn.cursor()
    ph = '%s'
    
    # 获取所有有目标表但没有 transform_config 的查询配置
    cursor.execute('''
        SELECT id, target_table, transform_config 
        FROM query_configs 
        WHERE target_table IS NOT NULL AND target_table != ''
    ''')
    
    query_configs = cursor.fetchall()
    
    for config in query_configs:
        config_id = config[0]
        target_table = config[1]
        existing_transform_config = config[2]
        
        # 如果已经有 transform_config，跳过
        if existing_transform_config:
            try:
                existing = json.loads(existing_transform_config) if isinstance(existing_transform_config, str) else existing_transform_config
                if existing and len(existing) > 0:
                    continue
            except:
                pass
        
        # 查找对应的映射配置
        cursor.execute(f'''
            SELECT id FROM topic_table_mappings WHERE table_name = {ph}
        ''', (target_table,))
        mapping_row = cursor.fetchone()
        
        if not mapping_row:
            continue
        
        mapping_id = mapping_row[0]
        
        # 获取 field_mappings 中的转换规则
        cursor.execute(f'''
            SELECT target_column, source_field, transform_rule, default_value, empty_handler
            FROM field_mappings 
            WHERE mapping_id = {ph}
        ''', (mapping_id,))
        
        field_mappings = cursor.fetchall()
        
        if not field_mappings:
            continue
        
        # 构建 transform_config
        transform_config = {}
        for fm in field_mappings:
            target_column = fm[0]
            source_field = fm[1]
            transform_rule = fm[2]
            default_value = fm[3]
            empty_handler = fm[4] if len(fm) > 4 else ''
            
            # 只有当有实际配置时才添加
            if source_field or transform_rule or default_value or empty_handler:
                transform_config[target_column] = {
                    'source_field': source_field or '',
                    'transform_rule': transform_rule or '',
                    'default_value': default_value or '',
                    'empty_handler': empty_handler or ''
                }
        
        # 更新 query_configs
        if transform_config:
            transform_config_json = json.dumps(transform_config, ensure_ascii=False)
            cursor.execute(f'''
                UPDATE query_configs SET transform_config = {ph} WHERE id = {ph}
            ''', (transform_config_json, config_id))
    
    conn.commit()
    print(f"转换规则迁移完成，处理了 {len(query_configs)} 个查询配置")


# ==================== API密钥管理 ====================

def mask_secret(value, show_chars=4):
    """脱敏显示密钥，只显示前后几个字符"""
    if not value or len(value) <= show_chars * 2:
        return '*' * 8
    return value[:show_chars] + '*' * 8 + value[-show_chars:]


@app.route('/api/credentials', methods=['GET'])
def get_credentials():
    """获取所有API密钥配置（包含脱敏后的密钥信息）"""
    db = get_db()
    cursor = dict_cursor(db)
    cursor.execute('SELECT id, name, secret_id, secret_key, created_at, updated_at FROM api_credentials')
    credentials = cursor.fetchall()
    result = []
    for row in credentials:
        cred = dict(row) if not isinstance(row, dict) else row
        # 解密并脱敏显示
        try:
            decrypted_id = decrypt_value(cred['secret_id'])
            decrypted_key = decrypt_value(cred['secret_key'])
            cred['secret_id_masked'] = mask_secret(decrypted_id)
            cred['secret_key_masked'] = mask_secret(decrypted_key, 3)
        except:
            cred['secret_id_masked'] = '********'
            cred['secret_key_masked'] = '********'
        # 删除原始加密值，不返回给前端
        del cred['secret_id']
        del cred['secret_key']
        # 处理时间格式
        if cred.get('created_at') and hasattr(cred['created_at'], 'isoformat'):
            cred['created_at'] = cred['created_at'].isoformat()
        if cred.get('updated_at') and hasattr(cred['updated_at'], 'isoformat'):
            cred['updated_at'] = cred['updated_at'].isoformat()
        result.append(cred)
    return jsonify(result)


@app.route('/api/credentials/<int:cred_id>', methods=['GET'])
def get_credential_detail(cred_id):
    """获取单个密钥详情（用于编辑回显，包含脱敏信息）"""
    db = get_db()
    cursor = dict_cursor(db)
    cursor.execute(f'SELECT id, name, secret_id, secret_key, created_at, updated_at FROM api_credentials WHERE id = {PH}', (cred_id,))
    cred = cursor.fetchone()
    if not cred:
        return jsonify({'error': '凭证不存在'}), 404
    
    result = dict(cred) if not isinstance(cred, dict) else cred
    try:
        decrypted_id = decrypt_value(result['secret_id'])
        decrypted_key = decrypt_value(result['secret_key'])
        result['secret_id_masked'] = mask_secret(decrypted_id)
        result['secret_key_masked'] = mask_secret(decrypted_key, 3)
        result['secret_id_full'] = decrypted_id
    except:
        result['secret_id_masked'] = '********'
        result['secret_key_masked'] = '********'
        result['secret_id_full'] = ''
    
    del result['secret_id']
    del result['secret_key']
    return jsonify(result)


@app.route('/api/credentials', methods=['POST'])
def create_credential():
    """创建API密钥配置"""
    data = request.json
    if not data.get('name') or not data.get('secret_id') or not data.get('secret_key'):
        return jsonify({'error': '缺少必要参数'}), 400
    
    db = get_db()
    cursor = db.cursor()
    try:
        encrypted_id = encrypt_value(data['secret_id'])
        encrypted_key = encrypt_value(data['secret_key'])
        cursor.execute(
            f'INSERT INTO api_credentials (name, secret_id, secret_key) VALUES ({PH}, {PH}, {PH})',
            (data['name'], encrypted_id, encrypted_key)
        )
        db.commit()
        return jsonify({'message': '创建成功'}), 201
    except Exception as e:
        db.rollback()
        if 'Duplicate' in str(e) or 'UNIQUE' in str(e):
            return jsonify({'error': '名称已存在'}), 400
        return jsonify({'error': str(e)}), 500


@app.route('/api/credentials/<int:cred_id>', methods=['PUT'])
def update_credential(cred_id):
    """更新API密钥配置"""
    data = request.json
    db = get_db()
    cursor = db.cursor()
    
    updates = []
    params = []
    
    if data.get('name'):
        updates.append(f'name = {PH}')
        params.append(data['name'])
    if data.get('secret_id'):
        updates.append(f'secret_id = {PH}')
        params.append(encrypt_value(data['secret_id']))
    if data.get('secret_key'):
        updates.append(f'secret_key = {PH}')
        params.append(encrypt_value(data['secret_key']))
    
    if updates:
        params.append(cred_id)
        cursor.execute(f'UPDATE api_credentials SET {", ".join(updates)} WHERE id = {PH}', params)
        db.commit()
    
    return jsonify({'message': '更新成功'})


@app.route('/api/credentials/<int:cred_id>', methods=['DELETE'])
def delete_credential(cred_id):
    """删除API密钥配置"""
    db = get_db()
    cursor = db.cursor()
    cursor.execute(f'DELETE FROM api_credentials WHERE id = {PH}', (cred_id,))
    db.commit()
    return jsonify({'message': '删除成功'})


# ==================== Topic配置管理 ====================

@app.route('/api/topics', methods=['GET'])
def get_topics():
    """获取所有Topic配置"""
    db = get_db()
    cursor = dict_cursor(db)
    cursor.execute('''
        SELECT t.*, c.name as credential_name 
        FROM log_topics t 
        JOIN api_credentials c ON t.credential_id = c.id
    ''')
    topics = cursor.fetchall()
    result = []
    for row in topics:
        item = dict(row) if not isinstance(row, dict) else row
        if item.get('created_at') and hasattr(item['created_at'], 'isoformat'):
            item['created_at'] = item['created_at'].isoformat()
        result.append(item)
    return jsonify(result)


@app.route('/api/topics', methods=['POST'])
def create_topic():
    """创建Topic配置"""
    data = request.json
    if not data.get('credential_id') or not data.get('topic_id'):
        return jsonify({'error': '缺少必要参数'}), 400
    
    db = get_db()
    cursor = db.cursor()
    cursor.execute(
        f'INSERT INTO log_topics (credential_id, region, topic_id, topic_name, description) VALUES ({PH}, {PH}, {PH}, {PH}, {PH})',
        (data['credential_id'], data.get('region', 'ap-guangzhou'), data['topic_id'], data.get('topic_name', ''), data.get('description', ''))
    )
    db.commit()
    return jsonify({'message': '创建成功'}), 201


@app.route('/api/topics/<int:topic_id>', methods=['PUT'])
def update_topic(topic_id):
    """更新Topic配置"""
    data = request.json
    db = get_db()
    cursor = db.cursor()
    
    cursor.execute(
        f'UPDATE log_topics SET region = {PH}, topic_name = {PH}, description = {PH} WHERE id = {PH}',
        (data.get('region', 'ap-guangzhou'), data.get('topic_name', ''), data.get('description', ''), topic_id)
    )
    db.commit()
    return jsonify({'message': '更新成功'})


@app.route('/api/topics/<int:topic_id>', methods=['DELETE'])
def delete_topic(topic_id):
    """删除Topic配置"""
    db = get_db()
    cursor = db.cursor()
    cursor.execute(f'DELETE FROM log_topics WHERE id = {PH}', (topic_id,))
    db.commit()
    return jsonify({'message': '删除成功'})


# ==================== 查询配置管理 ====================

@app.route('/api/query-configs', methods=['GET'])
def get_query_configs():
    """获取所有查询配置"""
    db = get_db()
    cursor = dict_cursor(db)
    cursor.execute('''
        SELECT q.*, t.topic_id as cls_topic_id, t.topic_name, c.name as credential_name, c.region
        FROM query_configs q
        JOIN log_topics t ON q.topic_id = t.id
        JOIN api_credentials c ON t.credential_id = c.id
    ''')
    configs = cursor.fetchall()
    result = []
    for row in configs:
        item = dict(row) if not isinstance(row, dict) else row
        if item.get('created_at') and hasattr(item['created_at'], 'isoformat'):
            item['created_at'] = item['created_at'].isoformat()
        if item.get('updated_at') and hasattr(item['updated_at'], 'isoformat'):
            item['updated_at'] = item['updated_at'].isoformat()
        # 解析 transform_config JSON 字段
        if item.get('transform_config'):
            if isinstance(item['transform_config'], str):
                try:
                    item['transform_config'] = json.loads(item['transform_config'])
                except:
                    pass
        result.append(item)
    return jsonify(result)


@app.route('/api/query-configs', methods=['POST'])
def create_query_config():
    """创建查询配置"""
    data = request.json
    if not data.get('name') or not data.get('topic_id') or not data.get('query_statement'):
        return jsonify({'error': '缺少必要参数'}), 400
    
    db = get_db()
    cursor = db.cursor()
    
    # 处理 transform_config
    transform_config = data.get('transform_config')
    if transform_config and isinstance(transform_config, dict):
        transform_config = json.dumps(transform_config, ensure_ascii=False)
    
    # 处理 filter_config
    filter_config = data.get('filter_config')
    if filter_config and isinstance(filter_config, dict):
        filter_config = json.dumps(filter_config, ensure_ascii=False)
    
    cursor.execute(f'''
        INSERT INTO query_configs (name, topic_id, query_statement, time_range, limit_count, sort_order, syntax_rule, processor_type, target_table, transform_config, filter_config, schedule_enabled, schedule_interval)
        VALUES ({PH}, {PH}, {PH}, {PH}, {PH}, {PH}, {PH}, {PH}, {PH}, {PH}, {PH}, {PH}, {PH})
    ''', (
        data['name'],
        data['topic_id'],
        data['query_statement'],
        data.get('time_range', 3600),
        data.get('limit_count', 100),
        data.get('sort_order', 'desc'),
        data.get('syntax_rule', 1),
        data.get('processor_type'),
        data.get('target_table'),
        transform_config,
        filter_config,
        data.get('schedule_enabled', 0),
        data.get('schedule_interval', 300)
    ))
    db.commit()
    return jsonify({'message': '创建成功'}), 201


@app.route('/api/query-configs/<int:config_id>', methods=['PUT'])
def update_query_config(config_id):
    """更新查询配置"""
    data = request.json
    db = get_db()
    cursor = db.cursor()
    
    # 处理 transform_config
    transform_config = data.get('transform_config')
    if transform_config and isinstance(transform_config, dict):
        transform_config = json.dumps(transform_config, ensure_ascii=False)
    
    # 处理 filter_config
    filter_config = data.get('filter_config')
    if filter_config and isinstance(filter_config, dict):
        filter_config = json.dumps(filter_config, ensure_ascii=False)
    
    if get_db_type() == 'mysql':
        cursor.execute(f'''
            UPDATE query_configs 
            SET name = {PH}, query_statement = {PH}, time_range = {PH}, limit_count = {PH}, sort_order = {PH}, syntax_rule = {PH}, processor_type = {PH}, target_table = {PH}, transform_config = {PH}, filter_config = {PH}, schedule_enabled = {PH}, schedule_interval = {PH}
            WHERE id = {PH}
        ''', (
            data.get('name'),
            data.get('query_statement'),
            data.get('time_range', 3600),
            data.get('limit_count', 100),
            data.get('sort_order', 'desc'),
            data.get('syntax_rule', 1),
            data.get('processor_type'),
            data.get('target_table'),
            transform_config,
            filter_config,
            data.get('schedule_enabled', 0),
            data.get('schedule_interval', 300),
            config_id
        ))
    else:
        cursor.execute(f'''
            UPDATE query_configs 
            SET name = {PH}, query_statement = {PH}, time_range = {PH}, limit_count = {PH}, sort_order = {PH}, syntax_rule = {PH}, processor_type = {PH}, target_table = {PH}, transform_config = {PH}, filter_config = {PH}, schedule_enabled = {PH}, schedule_interval = {PH}, updated_at = CURRENT_TIMESTAMP
            WHERE id = {PH}
        ''', (
            data.get('name'),
            data.get('query_statement'),
            data.get('time_range', 3600),
            data.get('limit_count', 100),
            data.get('sort_order', 'desc'),
            data.get('syntax_rule', 1),
            data.get('processor_type'),
            data.get('target_table'),
            transform_config,
            filter_config,
            data.get('schedule_enabled', 0),
            data.get('schedule_interval', 300),
            config_id
        ))
    db.commit()
    return jsonify({'message': '更新成功'})


@app.route('/api/query-configs/<int:config_id>', methods=['DELETE'])
def delete_query_config(config_id):
    """删除查询配置"""
    db = get_db()
    cursor = db.cursor()
    
    try:
        # 先删除关联的日志记录
        cursor.execute(f'DELETE FROM log_records WHERE query_config_id = {PH}', (config_id,))
        # 删除关联的分析结果
        cursor.execute(f'DELETE FROM analysis_results WHERE query_config_id = {PH}', (config_id,))
        # 最后删除查询配置
        cursor.execute(f'DELETE FROM query_configs WHERE id = {PH}', (config_id,))
        db.commit()
        return jsonify({'message': '删除成功'})
    except Exception as e:
        db.rollback()
        return jsonify({'error': f'删除失败: {str(e)}'}), 500


# ==================== 定时查询调度器 ====================

@app.route('/api/scheduler/status', methods=['GET'])
def get_scheduler_status():
    """获取调度器状态"""
    from services.scheduler import scheduler
    
    db = get_db()
    cursor = dict_cursor(db)
    
    # 获取启用定时查询的配置数量
    cursor.execute('SELECT COUNT(*) as cnt FROM query_configs WHERE schedule_enabled = 1')
    row = cursor.fetchone()
    enabled_count = row['cnt'] if isinstance(row, dict) else row[0]
    
    # 获取启用定时查询的配置列表
    cursor.execute(f'''
        SELECT q.id, q.name, q.schedule_interval, q.time_range, q.limit_count
        FROM query_configs q
        WHERE q.schedule_enabled = 1
    ''')
    configs = [dict(row) if not isinstance(row, dict) else row for row in cursor.fetchall()]
    
    # 添加上次执行时间
    for config in configs:
        last_exec = scheduler.last_execution.get(config['id'], 0)
        config['last_execution'] = datetime.fromtimestamp(last_exec).strftime('%Y-%m-%d %H:%M:%S') if last_exec > 0 else '尚未执行'
    
    return jsonify({
        'running': scheduler.running,
        'enabled_count': enabled_count,
        'configs': configs
    })


@app.route('/api/scheduler/trigger/<int:config_id>', methods=['POST'])
def trigger_scheduled_query(config_id):
    """手动触发定时查询"""
    from services.scheduler import scheduler
    
    db = get_db()
    cursor = dict_cursor(db)
    
    # 获取配置
    cursor.execute(f'''
        SELECT q.*, t.topic_id as cls_topic_id, t.credential_id, t.region
        FROM query_configs q
        JOIN log_topics t ON q.topic_id = t.id
        WHERE q.id = {PH}
    ''', (config_id,))
    
    config = cursor.fetchone()
    if not config:
        return jsonify({'error': '查询配置不存在'}), 404
    
    config = dict(config) if not isinstance(config, dict) else config
    
    try:
        scheduler._execute_query(config['id'], config['name'])
        scheduler.last_execution[config_id] = time.time()
        return jsonify({'message': f'查询 {config["name"]} 执行成功'})
    except Exception as e:
        return jsonify({'error': f'执行失败: {str(e)}'}), 500


@app.route('/api/scheduler/push-status', methods=['GET'])
def get_push_scheduler_status():
    """获取推送调度器状态"""
    from services.scheduler import scheduler
    
    db = get_db()
    cursor = dict_cursor(db)
    
    # 获取启用定时推送的配置数量
    cursor.execute('SELECT COUNT(*) as cnt FROM report_push_config WHERE schedule_enabled = 1')
    row = cursor.fetchone()
    enabled_count = row['cnt'] if isinstance(row, dict) else row[0]
    
    # 获取启用定时推送的配置列表
    cursor.execute('''
        SELECT id, name, schedule_time, last_push_time
        FROM report_push_config
        WHERE schedule_enabled = 1
    ''')
    configs = [dict(row) if not isinstance(row, dict) else row for row in cursor.fetchall()]
    
    # 添加上次执行时间
    for config in configs:
        last_exec = scheduler.last_push_execution.get(config['id'])
        config['last_execution_date'] = last_exec if last_exec else '尚未执行'
        if config.get('last_push_time') and hasattr(config['last_push_time'], 'strftime'):
            config['last_push_time'] = config['last_push_time'].strftime('%Y-%m-%d %H:%M:%S')
    
    return jsonify({
        'running': scheduler.running,
        'enabled_count': enabled_count,
        'configs': configs
    })


# ==================== CLS日志查询 ====================

def call_cls_api(credential_id, topic_id, query, from_time, to_time, limit=100, sort='desc', syntax_rule=1, region=None):
    """
    调用腾讯云CLS SearchLog API（使用官方SDK）
    """
    db = get_db()
    cursor = dict_cursor(db)
    
    # 获取凭证信息
    cursor.execute(f'SELECT * FROM api_credentials WHERE id = {PH}', (credential_id,))
    cred = cursor.fetchone()
    if not cred:
        raise Exception('凭证不存在')
    
    cred = dict(cred) if not isinstance(cred, dict) else cred
    secret_id = decrypt_value(cred['secret_id'])
    secret_key = decrypt_value(cred['secret_key'])
    
    # 如果没有指定region，尝试从log_topics表获取
    if not region:
        cursor.execute(f'SELECT region FROM log_topics WHERE topic_id = {PH}', (topic_id,))
        topic_info = cursor.fetchone()
        if topic_info:
            topic_info = dict(topic_info) if not isinstance(topic_info, dict) else topic_info
            region = topic_info.get('region', 'ap-guangzhou')
        else:
            region = 'ap-guangzhou'
    
    # 确保参数类型正确
    from_time = int(from_time)
    to_time = int(to_time)
    limit = int(limit)
    syntax_rule = int(syntax_rule)
    
    try:
        # 使用腾讯云官方SDK
        cred_obj = credential.Credential(secret_id, secret_key)
        
        # 配置HTTP选项（使用内网endpoint）
        http_profile = HttpProfile()
        http_profile.endpoint = "cls.internal.tencentcloudapi.com"
        http_profile.reqMethod = "POST"
        
        # 配置客户端
        client_profile = ClientProfile()
        client_profile.httpProfile = http_profile
        
        # 初始化客户端
        client = cls_client.ClsClient(cred_obj, region, client_profile)
        
        # 构建请求
        req = models.SearchLogRequest()
        req.TopicId = topic_id
        req.From = from_time
        req.To = to_time
        req.Query = query
        req.Limit = limit
        req.Sort = sort
        req.SyntaxRule = syntax_rule
        req.UseNewAnalysis = True
        
        # 调用接口
        resp = client.SearchLog(req)
        
        # 将响应转换为字典格式
        result = json.loads(resp.to_json_string())
        return {"Response": result}
        
    except TencentCloudSDKException as e:
        return {
            "Response": {
                "Error": {
                    "Code": e.code,
                    "Message": e.message
                },
                "RequestId": e.requestId
            },
            "_debug": {
                'region': region,
                'topic_id': topic_id,
                'from_time': from_time,
                'to_time': to_time,
                'query': query
            }
        }
    except Exception as e:
        return {
            "Response": {
                "Error": {
                    "Code": "InternalError",
                    "Message": str(e)
                }
            },
            "_debug": {
                'region': region,
                'topic_id': topic_id
            }
        }


@app.route('/api/search-logs', methods=['POST'])
def search_logs():
    """执行日志查询"""
    data = request.json
    
    config_id = data.get('config_id')
    custom_query = data.get('query')
    custom_from = data.get('from_time')
    custom_to = data.get('to_time')
    
    db = get_db()
    cursor = dict_cursor(db)
    region = None
    processor_type = None
    target_table = None
    query_transform_config = None  # 查询配置的独立转换规则
    query_filter_config = None  # 查询配置的入库条件
    
    if config_id:
        # 使用预配置的查询
        cursor.execute(f'''
            SELECT q.*, t.topic_id as cls_topic_id, t.credential_id, t.region
            FROM query_configs q
            JOIN log_topics t ON q.topic_id = t.id
            WHERE q.id = {PH}
        ''', (config_id,))
        
        config = cursor.fetchone()
        if not config:
            return jsonify({'error': '查询配置不存在'}), 404
        
        config = dict(config) if not isinstance(config, dict) else config
        credential_id = config['credential_id']
        topic_id = config['cls_topic_id']
        region = config['region']
        query = custom_query or config['query_statement']
        time_range = config['time_range']
        limit = config['limit_count']
        sort = config['sort_order']
        syntax_rule = config['syntax_rule']
        # 优先使用前端传入的参数，否则使用配置中的值
        processor_type = data.get('processor_type') or config.get('processor_type')
        target_table = data.get('target_table') or config.get('target_table')
        # 获取查询配置的独立转换规则
        if config.get('transform_config'):
            transform_cfg = config['transform_config']
            if isinstance(transform_cfg, str):
                query_transform_config = json.loads(transform_cfg)
            else:
                query_transform_config = transform_cfg
        # 获取查询配置的入库条件
        if config.get('filter_config'):
            filter_cfg = config['filter_config']
            if isinstance(filter_cfg, str):
                query_filter_config = json.loads(filter_cfg)
            else:
                query_filter_config = filter_cfg
        
        # 计算时间范围
        to_time = custom_to or int(time.time() * 1000)
        from_time = custom_from or (to_time - time_range * 1000)
    else:
        # 使用自定义参数
        if not all([data.get('credential_id'), data.get('topic_id'), data.get('query')]):
            return jsonify({'error': '缺少必要参数'}), 400
        
        credential_id = data['credential_id']
        topic_id = data['topic_id']
        query = data['query']
        from_time = data.get('from_time', int((time.time() - 3600) * 1000))
        to_time = data.get('to_time', int(time.time() * 1000))
        limit = data.get('limit', 100)
        sort = data.get('sort', 'desc')
        syntax_rule = data.get('syntax_rule', 1)
        processor_type = data.get('processor_type')
        target_table = data.get('target_table')
        # 从log_topics获取region
        cursor.execute(f'SELECT region FROM log_topics WHERE topic_id = {PH}', (topic_id,))
        topic_info = cursor.fetchone()
        if topic_info:
            topic_info = dict(topic_info) if not isinstance(topic_info, dict) else topic_info
            region = topic_info.get('region')
    
    try:
        result = call_cls_api(credential_id, topic_id, query, from_time, to_time, limit, sort, syntax_rule, region)
        
        # 添加调试信息
        result['_debug_params'] = {
            'processor_type': processor_type,
            'target_table': target_table,
            'config_id': config_id
        }
        
        print(f"[DEBUG] processor_type={processor_type}, target_table={target_table}")
        
        # 存储查询结果
        has_results = 'Response' in result and 'Results' in result['Response']
        results_count = len(result.get('Response', {}).get('Results', [])) if has_results else 0
        print(f"[DEBUG] has_results={has_results}, results_count={results_count}")
        
        if has_results and results_count > 0:
            write_cursor = db.cursor()
            
            # 需要使用专用处理器（带Redis缓存和聚合逻辑）的表
            SPECIALIZED_PROCESSOR_TABLES = {
                'control_hitch_error_mothod': 'control_hitch_error',
                'gw_hitch_error_mothod': 'gw_hitch_error',
                'hitch_supplier_error_sp': 'hitch_supplier_error_sp',
                'hitch_supplier_error_total': 'hitch_supplier_error_total',
                'hitch_control_cost_time': 'hitch_control_cost_time'
            }
            
            # 检查是否需要使用专用处理器
            use_specialized_processor = target_table in SPECIALIZED_PROCESSOR_TABLES
            if use_specialized_processor:
                # 强制使用专用处理器
                processor_type = SPECIALIZED_PROCESSOR_TABLES[target_table]
                print(f"[DEBUG] Using specialized processor for {target_table}: {processor_type}")
            
            # 优先检查是否有映射配置，如果有则使用通用数据处理器（用户自定义转换规则）
            # 但对于需要聚合的表，跳过通用处理器
            use_generic_processor = False
            mapping_id = None
            if target_table and not use_specialized_processor:
                cursor.execute(f'SELECT id FROM topic_table_mappings WHERE table_name = {PH}', (target_table,))
                mapping_row = cursor.fetchone()
                if mapping_row:
                    use_generic_processor = True
                    mapping_row = dict(mapping_row) if not isinstance(mapping_row, dict) else mapping_row
                    mapping_id = mapping_row['id']
                    print(f"[DEBUG] Found mapping config for {target_table}, mapping_id={mapping_id}")
            
            if use_generic_processor and mapping_id:
                # 使用通用数据处理器，根据用户配置的映射规则处理
                from services.data_processor import DataProcessorService
                processor = DataProcessorService(MYSQL_CONFIG if get_db_type() == 'mysql' else os.path.join(os.path.dirname(__file__), 'data.db'))
                
                # 解析日志数据
                log_data_list = []
                for log in result['Response'].get('Results', []):
                    log_json = log.get('LogJson', '')
                    if log_json:
                        try:
                            log_data = json.loads(log_json)
                            # 添加时间戳
                            if log.get('Time'):
                                log_data['_timestamp'] = log.get('Time')
                            log_data_list.append(log_data)
                        except:
                            pass
                
                if log_data_list:
                    process_result = processor.process_log_data(mapping_id, log_data_list, query_transform_config=query_transform_config, query_filter_config=query_filter_config)
                    result['_process_result'] = process_result
                else:
                    result['_process_error'] = '没有有效的日志数据'
            elif processor_type == 'gw_hitch_error':
                # 使用GW Hitch专用处理器（仅在没有映射配置时）
                from services.gw_hitch_processor import GwHitchProcessor
                gw_processor = GwHitchProcessor(MYSQL_CONFIG if get_db_type() == 'mysql' else os.path.join(os.path.dirname(__file__), 'data.db'))
                process_result = gw_processor.process_cls_response(result, query_transform_config, query_filter_config)
                result['_process_result'] = process_result
            elif processor_type == 'control_hitch_error':
                # 使用Control Hitch专用处理器（仅在没有映射配置时）
                from services.control_hitch_processor import ControlHitchProcessor
                control_processor = ControlHitchProcessor(MYSQL_CONFIG if get_db_type() == 'mysql' else os.path.join(os.path.dirname(__file__), 'data.db'))
                process_result = control_processor.process_cls_response(result, query_transform_config, query_filter_config)
                result['_process_result'] = process_result
            elif processor_type == 'hitch_supplier_error_sp':
                # 使用Hitch Supplier Error SP专用处理器
                from services.hitch_supplier_error_sp_processor import HitchSupplierErrorSpProcessor
                sp_processor = HitchSupplierErrorSpProcessor(MYSQL_CONFIG if get_db_type() == 'mysql' else os.path.join(os.path.dirname(__file__), 'data.db'))
                process_result = sp_processor.process_cls_response(result, query_transform_config, query_filter_config)
                result['_process_result'] = process_result
            elif processor_type == 'hitch_supplier_error_total':
                # 使用Hitch Supplier Error Total专用处理器
                from services.hitch_supplier_error_total_processor import HitchSupplierErrorTotalProcessor
                total_processor = HitchSupplierErrorTotalProcessor(MYSQL_CONFIG if get_db_type() == 'mysql' else os.path.join(os.path.dirname(__file__), 'data.db'))
                process_result = total_processor.process_cls_response(result, query_transform_config, query_filter_config)
                result['_process_result'] = process_result
            elif processor_type == 'hitch_control_cost_time':
                # 使用Hitch Control Cost Time专用处理器
                from services.hitch_control_cost_time_processor import HitchControlCostTimeProcessor
                cost_time_processor = HitchControlCostTimeProcessor(MYSQL_CONFIG if get_db_type() == 'mysql' else os.path.join(os.path.dirname(__file__), 'data.db'))
                process_result = cost_time_processor.process_cls_response(result, query_transform_config, query_filter_config)
                result['_process_result'] = process_result
            else:
                # 默认处理：写入log_records表
                for log in result['Response'].get('Results', []):
                    log_time = None
                    if log.get('Time'):
                        log_time = datetime.fromtimestamp(log.get('Time', 0) / 1000).strftime('%Y-%m-%d %H:%M:%S')
                    write_cursor.execute(f'''
                        INSERT INTO log_records (query_config_id, topic_id, log_time, log_content, log_json, source)
                        VALUES ({PH}, {PH}, {PH}, {PH}, {PH}, {PH})
                    ''', (
                        config_id,
                        topic_id,
                        log_time,
                        log.get('RawLog', ''),
                        log.get('LogJson', ''),
                        log.get('Source', '')
                    ))
            
            # 存储分析结果
            if result['Response'].get('Analysis'):
                write_cursor.execute(f'''
                    INSERT INTO analysis_results (query_config_id, analysis_type, result_data, columns)
                    VALUES ({PH}, {PH}, {PH}, {PH})
                ''', (
                    config_id,
                    'sql_analysis',
                    json.dumps(result['Response'].get('AnalysisRecords', [])),
                    json.dumps(result['Response'].get('Columns', []))
                ))
            
            db.commit()
        
        return jsonify(result)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 数据展示 ====================

@app.route('/api/log-records', methods=['GET'])
def get_log_records():
    """获取存储的日志记录"""
    config_id = request.args.get('config_id')
    limit = request.args.get('limit', 100, type=int)
    offset = request.args.get('offset', 0, type=int)
    
    db = get_db()
    cursor = dict_cursor(db)
    
    if config_id:
        cursor.execute(f'''
            SELECT * FROM log_records WHERE query_config_id = {PH} 
            ORDER BY log_time DESC LIMIT {PH} OFFSET {PH}
        ''', (config_id, limit, offset))
        records = cursor.fetchall()
        cursor.execute(f'SELECT COUNT(*) as cnt FROM log_records WHERE query_config_id = {PH}', (config_id,))
        total_row = cursor.fetchone()
        total = total_row['cnt'] if isinstance(total_row, dict) else total_row[0]
    else:
        cursor.execute(f'''
            SELECT * FROM log_records ORDER BY log_time DESC LIMIT {PH} OFFSET {PH}
        ''', (limit, offset))
        records = cursor.fetchall()
        cursor.execute('SELECT COUNT(*) as cnt FROM log_records')
        total_row = cursor.fetchone()
        total = total_row['cnt'] if isinstance(total_row, dict) else total_row[0]
    
    result = []
    for row in records:
        item = dict(row) if not isinstance(row, dict) else row
        if item.get('log_time') and hasattr(item['log_time'], 'isoformat'):
            item['log_time'] = item['log_time'].isoformat()
        if item.get('collected_at') and hasattr(item['collected_at'], 'isoformat'):
            item['collected_at'] = item['collected_at'].isoformat()
        result.append(item)
    
    return jsonify({
        'records': result,
        'total': total,
        'limit': limit,
        'offset': offset
    })


@app.route('/api/analysis-results', methods=['GET'])
def get_analysis_results():
    """获取分析结果"""
    config_id = request.args.get('config_id')
    
    db = get_db()
    cursor = dict_cursor(db)
    
    if config_id:
        cursor.execute(f'''
            SELECT * FROM analysis_results WHERE query_config_id = {PH} ORDER BY created_at DESC
        ''', (config_id,))
    else:
        cursor.execute('SELECT * FROM analysis_results ORDER BY created_at DESC')
    
    results = cursor.fetchall()
    return jsonify([dict(row) if not isinstance(row, dict) else row for row in results])


@app.route('/api/statistics', methods=['GET'])
def get_statistics():
    """获取统计数据"""
    db = get_db()
    cursor = dict_cursor(db)
    
    # 获取各种统计数据
    cursor.execute('SELECT COUNT(*) as cnt FROM log_records')
    row = cursor.fetchone()
    total_logs = row['cnt'] if isinstance(row, dict) else row[0]
    
    cursor.execute('SELECT COUNT(*) as cnt FROM query_configs')
    row = cursor.fetchone()
    total_configs = row['cnt'] if isinstance(row, dict) else row[0]
    
    cursor.execute('SELECT COUNT(*) as cnt FROM log_topics')
    row = cursor.fetchone()
    total_topics = row['cnt'] if isinstance(row, dict) else row[0]
    
    # 按时间分组的日志数量
    if get_db_type() == 'mysql':
        cursor.execute('''
            SELECT DATE(log_time) as date, COUNT(*) as count 
            FROM log_records 
            WHERE log_time IS NOT NULL
            GROUP BY DATE(log_time) 
            ORDER BY date DESC 
            LIMIT 30
        ''')
    else:
        cursor.execute('''
            SELECT DATE(log_time) as date, COUNT(*) as count 
            FROM log_records 
            WHERE log_time IS NOT NULL
            GROUP BY DATE(log_time) 
            ORDER BY date DESC 
            LIMIT 30
        ''')
    time_distribution = [dict(row) if not isinstance(row, dict) else row for row in cursor.fetchall()]
    
    # 按Topic分组的日志数量
    cursor.execute('''
        SELECT topic_id, COUNT(*) as count 
        FROM log_records 
        GROUP BY topic_id
    ''')
    topic_distribution = [dict(row) if not isinstance(row, dict) else row for row in cursor.fetchall()]
    
    return jsonify({
        'total_logs': total_logs,
        'total_configs': total_configs,
        'total_topics': total_topics,
        'time_distribution': time_distribution,
        'topic_distribution': topic_distribution
    })


# ==================== 权限检查与诊断工具 ====================

class CLSPermissionAnalyzer:
    """腾讯云CLS权限错误分析器"""
    
    CLS_ACTIONS = {
        'cls:SearchLog': '搜索日志',
        'cls:GetLog': '获取日志',
        'cls:DescribeTopics': '查询日志主题',
        'cls:DescribeLogsets': '查询日志集',
        'cls:CreateTopic': '创建日志主题',
        'cls:DeleteTopic': '删除日志主题',
        'cls:ModifyTopic': '修改日志主题',
        'cls:UploadLog': '上传日志',
        'cls:DescribeIndex': '查询索引配置',
        'cls:CreateIndex': '创建索引配置',
        'cls:ModifyIndex': '修改索引配置',
        'cls:DescribeAlarms': '查询告警策略',
        'cls:CreateAlarm': '创建告警策略',
        'cls:DescribeShippers': '查询投递任务',
        'cls:CreateShipper': '创建投递任务',
    }
    
    CONDITION_OPERATORS = {
        'ip_equal': 'IP地址等于',
        'ip_not_equal': 'IP地址不等于',
        'string_equal': '字符串等于',
        'string_not_equal': '字符串不等于',
        'null_equal': '值为空',
        'null_not_equal': '值不为空',
        'string_like': '字符串匹配',
        'string_not_like': '字符串不匹配',
    }
    
    REGIONS = {
        'ap-guangzhou': '广州',
        'ap-shanghai': '上海',
        'ap-nanjing': '南京',
        'ap-beijing': '北京',
        'ap-chengdu': '成都',
        'ap-chongqing': '重庆',
        'ap-hongkong': '香港',
        'ap-singapore': '新加坡',
        'ap-tokyo': '东京',
        'ap-seoul': '首尔',
        'ap-bangkok': '曼谷',
        'ap-mumbai': '孟买',
        'eu-frankfurt': '法兰克福',
        'na-siliconvalley': '硅谷',
        'na-ashburn': '弗吉尼亚',
    }
    
    @staticmethod
    def parse_error_message(error_message):
        """解析权限错误信息"""
        result = {
            'request_id': None,
            'operation': None,
            'operation_desc': None,
            'resource': None,
            'resource_type': None,
            'region': None,
            'region_name': None,
            'uin': None,
            'topic_id': None,
            'conditions': [],
            'strategy_ids': [],
            'raw_message': error_message
        }
        
        req_id_match = re.search(r'\[request id:([^\]]+)\]|RequestId:\[([^\]]+)\]', error_message, re.IGNORECASE)
        if req_id_match:
            result['request_id'] = req_id_match.group(1) or req_id_match.group(2)
        
        op_match = re.search(r'operation \(([^)]+)\)', error_message)
        if op_match:
            result['operation'] = op_match.group(1)
            result['operation_desc'] = CLSPermissionAnalyzer.CLS_ACTIONS.get(
                op_match.group(1), '未知操作'
            )
        
        resource_match = re.search(r'resource \(([^)]+)\)', error_message)
        if resource_match:
            resource = resource_match.group(1)
            result['resource'] = resource
            
            qcs_match = re.search(
                r'qcs::(\w+):([^:]*):uin/(\d+):(\w+)/([^\s]+)',
                resource
            )
            if qcs_match:
                result['resource_type'] = qcs_match.group(1)
                result['region'] = qcs_match.group(2)
                result['region_name'] = CLSPermissionAnalyzer.REGIONS.get(
                    qcs_match.group(2), qcs_match.group(2)
                )
                result['uin'] = qcs_match.group(3)
                resource_subtype = qcs_match.group(4)
                result['topic_id'] = qcs_match.group(5) if resource_subtype == 'topic' else None
        
        condition_start = error_message.find('condition:[')
        if condition_start != -1:
            try:
                start_idx = condition_start + len('condition:')
                bracket_count = 0
                end_idx = start_idx
                for i, char in enumerate(error_message[start_idx:]):
                    if char == '[':
                        bracket_count += 1
                    elif char == ']':
                        bracket_count -= 1
                        if bracket_count == 0:
                            end_idx = start_idx + i + 1
                            break
                
                conditions_str = error_message[start_idx:end_idx]
                conditions_data = json.loads(conditions_str)
                
                for cond_group in conditions_data:
                    if isinstance(cond_group, dict):
                        effect = cond_group.get('effect', 'unknown')
                        strategy_id = cond_group.get('strategyId')
                        if strategy_id:
                            result['strategy_ids'].append(strategy_id)
                        
                        for cond in cond_group.get('condition', []):
                            parsed_cond = {
                                'key': cond.get('key', ''),
                                'value': cond.get('value', ''),
                                'operator': cond.get('ope', ''),
                                'operator_desc': CLSPermissionAnalyzer.CONDITION_OPERATORS.get(
                                    cond.get('ope', ''), cond.get('ope', '')
                                ),
                                'effect': effect,
                                'strategy_id': strategy_id
                            }
                            result['conditions'].append(parsed_cond)
            except (json.JSONDecodeError, IndexError):
                pass
        
        return result
    
    @staticmethod
    def generate_fix_suggestions(parsed_error):
        """生成修复建议"""
        suggestions = []
        
        if parsed_error['operation']:
            suggestions.append({
                'type': 'action_permission',
                'title': '添加操作权限',
                'description': f"需要为当前账号或子账号授予 {parsed_error['operation']} ({parsed_error['operation_desc']}) 权限",
                'priority': 'high'
            })
        
        if parsed_error['topic_id']:
            suggestions.append({
                'type': 'resource_permission',
                'title': '资源级别授权',
                'description': f"需要对Topic {parsed_error['topic_id']} 进行授权",
                'priority': 'high'
            })
        
        for cond in parsed_error['conditions']:
            if cond['key'] == 'qcs:ip' and cond['operator'] == 'ip_not_equal':
                suggestions.append({
                    'type': 'ip_restriction',
                    'title': 'IP访问限制',
                    'description': f"当前策略限制了IP访问，您的IP可能不在允许列表中。策略ID: {cond['strategy_id']}",
                    'priority': 'high',
                    'fix_action': '联系管理员将您的IP添加到允许列表，或修改/删除IP限制策略'
                })
            elif cond['key'] == 'vpc:requester_vpc' and cond['operator'] == 'null_equal':
                suggestions.append({
                    'type': 'vpc_restriction',
                    'title': 'VPC访问限制',
                    'description': f"当前策略要求通过VPC内网访问，但您可能是从公网访问。策略ID: {cond['strategy_id']}",
                    'priority': 'high',
                    'fix_action': '通过VPC内网访问CLS服务，或修改策略允许公网访问'
                })
        
        if parsed_error['strategy_ids']:
            suggestions.append({
                'type': 'policy_modification',
                'title': '修改限制策略',
                'description': f"涉及的策略ID: {', '.join(map(str, parsed_error['strategy_ids']))}",
                'priority': 'medium',
                'fix_action': '在腾讯云CAM控制台查看并修改相关策略'
            })
        
        return suggestions
    
    @staticmethod
    def generate_iam_policy(parsed_error, include_conditions=False):
        """生成建议的IAM策略配置"""
        policy = {
            'version': '2.0',
            'statement': []
        }
        
        statement = {
            'effect': 'allow',
            'action': [parsed_error['operation']] if parsed_error['operation'] else ['cls:*'],
            'resource': ['*']
        }
        
        if parsed_error['resource']:
            statement['resource'] = [parsed_error['resource']]
        elif parsed_error['topic_id'] and parsed_error['region'] and parsed_error['uin']:
            statement['resource'] = [
                f"qcs::cls:{parsed_error['region']}:uin/{parsed_error['uin']}:topic/{parsed_error['topic_id']}"
            ]
        
        policy['statement'].append(statement)
        
        return policy
    
    @staticmethod
    def generate_fix_steps(parsed_error, suggestions):
        """生成详细的修复步骤"""
        steps = []
        
        steps.append({
            'step': 1,
            'title': '确认权限问题',
            'description': f"操作 {parsed_error['operation_desc']} 被拒绝",
            'details': [
                f"Request ID: {parsed_error['request_id']}",
                f"资源: {parsed_error['resource']}",
                f"地域: {parsed_error['region_name']} ({parsed_error['region']})"
            ]
        })
        
        if parsed_error['conditions']:
            step2_details = []
            for cond in parsed_error['conditions']:
                step2_details.append(
                    f"条件: {cond['key']} {cond['operator_desc']} (效果: {cond['effect']})"
                )
            steps.append({
                'step': 2,
                'title': '检查访问条件限制',
                'description': '以下条件可能阻止了您的访问',
                'details': step2_details
            })
        
        steps.append({
            'step': 3 if parsed_error['conditions'] else 2,
            'title': '登录腾讯云CAM控制台',
            'description': '访问 https://console.cloud.tencent.com/cam/policy 管理策略',
            'details': [
                '使用主账号或具有CAM管理权限的子账号登录',
                '在策略管理页面搜索相关策略'
            ]
        })
        
        next_step = 4 if parsed_error['conditions'] else 3
        if parsed_error['strategy_ids']:
            steps.append({
                'step': next_step,
                'title': '修改限制策略',
                'description': f"找到并修改策略ID: {', '.join(map(str, parsed_error['strategy_ids']))}",
                'details': [
                    '如果是IP限制，添加您的IP到允许列表',
                    '如果是VPC限制，考虑添加公网访问条件或通过VPC访问',
                    '或者删除该限制策略'
                ]
            })
            next_step += 1
        
        steps.append({
            'step': next_step,
            'title': '添加CLS操作权限',
            'description': '为用户/角色添加必要的CLS权限',
            'details': [
                f"添加 {parsed_error['operation']} 权限",
                '可以使用预设策略 QcloudCLSFullAccess 或 QcloudCLSReadOnlyAccess',
                '或创建自定义策略精确控制权限范围'
            ]
        })
        
        steps.append({
            'step': next_step + 1,
            'title': '验证修复效果',
            'description': '重新执行操作，确认权限问题已解决',
            'details': [
                '等待策略生效（通常几秒到几分钟）',
                '重新执行之前失败的操作',
                '如仍有问题，检查是否有其他限制策略'
            ]
        })
        
        return steps


@app.route('/api/permission/analyze', methods=['POST'])
def analyze_permission_error():
    """分析权限错误信息"""
    data = request.json
    error_message = data.get('error_message', '')
    
    if not error_message:
        return jsonify({'error': '请提供错误信息'}), 400
    
    parsed = CLSPermissionAnalyzer.parse_error_message(error_message)
    suggestions = CLSPermissionAnalyzer.generate_fix_suggestions(parsed)
    iam_policy = CLSPermissionAnalyzer.generate_iam_policy(parsed)
    fix_steps = CLSPermissionAnalyzer.generate_fix_steps(parsed, suggestions)
    
    return jsonify({
        'parsed_error': parsed,
        'suggestions': suggestions,
        'iam_policy': iam_policy,
        'fix_steps': fix_steps
    })


@app.route('/api/permission/verify', methods=['POST'])
def verify_permission():
    """验证CLS权限"""
    data = request.json
    credential_id = data.get('credential_id')
    topic_id = data.get('topic_id')
    region = data.get('region', 'ap-guangzhou')
    
    if not credential_id:
        return jsonify({'error': '请选择凭证'}), 400
    
    db = get_db()
    cursor = dict_cursor(db)
    cursor.execute(f'SELECT * FROM api_credentials WHERE id = {PH}', (credential_id,))
    cred = cursor.fetchone()
    if not cred:
        return jsonify({'error': '凭证不存在'}), 404
    
    cred = dict(cred) if not isinstance(cred, dict) else cred
    secret_id = decrypt_value(cred['secret_id'])
    secret_key = decrypt_value(cred['secret_key'])
    
    results = []
    
    try:
        cred_obj = credential.Credential(secret_id, secret_key)
        http_profile = HttpProfile()
        http_profile.endpoint = "cls.internal.tencentcloudapi.com"
        client_profile = ClientProfile()
        client_profile.httpProfile = http_profile
        client = cls_client.ClsClient(cred_obj, region, client_profile)
        
        req = models.DescribeTopicsRequest()
        req.Offset = 0
        req.Limit = 1
        resp = client.DescribeTopics(req)
        
        results.append({
            'action': 'cls:DescribeTopics',
            'description': '查询日志主题',
            'status': 'success',
            'message': '权限正常'
        })
    except TencentCloudSDKException as e:
        results.append({
            'action': 'cls:DescribeTopics',
            'description': '查询日志主题',
            'status': 'failed',
            'message': e.message
        })
    except Exception as e:
        results.append({
            'action': 'cls:DescribeTopics',
            'description': '查询日志主题',
            'status': 'error',
            'message': str(e)
        })
    
    if topic_id:
        try:
            now = int(time.time() * 1000)
            req = models.SearchLogRequest()
            req.TopicId = topic_id
            req.From = now - 60000
            req.To = now
            req.Query = "*"
            req.Limit = 1
            resp = client.SearchLog(req)
            
            results.append({
                'action': 'cls:SearchLog',
                'description': '搜索日志',
                'status': 'success',
                'message': '权限正常',
                'topic_id': topic_id
            })
        except TencentCloudSDKException as e:
            results.append({
                'action': 'cls:SearchLog',
                'description': '搜索日志',
                'status': 'failed',
                'message': e.message,
                'topic_id': topic_id
            })
        except Exception as e:
            results.append({
                'action': 'cls:SearchLog',
                'description': '搜索日志',
                'status': 'error',
                'message': str(e),
                'topic_id': topic_id
            })
    
    success_count = len([r for r in results if r['status'] == 'success'])
    failed_count = len([r for r in results if r['status'] == 'failed'])
    error_count = len([r for r in results if r['status'] == 'error'])
    
    return jsonify({
        'results': results,
        'summary': {
            'total': len(results),
            'success': success_count,
            'failed': failed_count,
            'error': error_count,
            'all_passed': failed_count == 0 and error_count == 0
        }
    })


@app.route('/')
def index():
    """返回前端页面"""
    return app.send_static_file('index.html')


@app.route('/table-mapping')
def table_mapping_page():
    """重定向到主页面的映射配置"""
    from flask import redirect
    return redirect('/#table-mappings')


@app.route('/api/test-cls', methods=['POST'])
def test_cls_api():
    """测试CLS API调用"""
    data = request.json
    
    secret_id = data.get('secret_id')
    secret_key = data.get('secret_key')
    topic_id = data.get('topic_id')
    region = data.get('region', 'ap-nanjing')
    query = data.get('query', '*')
    
    if not all([secret_id, secret_key, topic_id]):
        return jsonify({'error': '缺少必要参数: secret_id, secret_key, topic_id'}), 400
    
    now = int(time.time() * 1000)
    from_time = now - 3600000
    to_time = now
    
    try:
        cred_obj = credential.Credential(secret_id, secret_key)
        http_profile = HttpProfile()
        http_profile.endpoint = "cls.internal.tencentcloudapi.com"
        http_profile.reqMethod = "POST"
        client_profile = ClientProfile()
        client_profile.httpProfile = http_profile

        client = cls_client.ClsClient(cred_obj, region, client_profile)
        
        req = models.SearchLogRequest()
        req.TopicId = topic_id
        req.From = from_time
        req.To = to_time
        req.Query = query
        req.Limit = 10
        req.Sort = "desc"
        req.SyntaxRule = 1
        req.UseNewAnalysis = True
        
        resp = client.SearchLog(req)
        print(f"请求结果=============》" + resp.to_json_string())
        result = json.loads(resp.to_json_string())
        
        return jsonify({
            'Response': result,
            '_debug': {
                'region': region,
                'topic_id': topic_id,
                'timestamp': int(time.time()),
                'from_time': from_time,
                'to_time': to_time
            }
        })
    except TencentCloudSDKException as e:
        return jsonify({
            'Response': {
                'Error': {
                    'Code': e.code,
                    'Message': e.message
                },
                'RequestId': e.requestId
            },
            '_debug': {
                'region': region,
                'topic_id': topic_id
            }
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.after_request
def after_request(response):
    """添加响应头"""
    response.headers.add('Access-Control-Allow-Origin', '*')
    response.headers.add('Access-Control-Allow-Headers', 'Content-Type,Authorization')
    response.headers.add('Access-Control-Allow-Methods', 'GET,PUT,POST,DELETE,OPTIONS')
    return response


def init_mysql_database():
    """初始化MySQL数据库（如果不存在则创建）"""
    if get_db_type() == 'mysql':
        import pymysql
        # 先连接不指定数据库，创建数据库
        config = MYSQL_CONFIG.copy()
        db_name = config.pop('database')
        conn = pymysql.connect(**config)
        cursor = conn.cursor()
        cursor.execute(f"CREATE DATABASE IF NOT EXISTS {db_name} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        conn.commit()
        conn.close()


if __name__ == '__main__':
    # 初始化MySQL数据库
    init_mysql_database()
    # 初始化表结构
    init_db()
    # 初始化表映射相关的数据表
    from models.table_mapping import TableMappingModel
    from config.database import MYSQL_CONFIG, get_db_type
    if get_db_type() == 'mysql':
        TableMappingModel(MYSQL_CONFIG)
        # 初始化GW Hitch处理器表
        from services.gw_hitch_processor import GwHitchProcessor
        GwHitchProcessor(MYSQL_CONFIG)
        # 初始化Control Hitch处理器表
        from services.control_hitch_processor import ControlHitchProcessor
        ControlHitchProcessor(MYSQL_CONFIG)
        # 初始化Hitch Supplier Error SP处理器表
        from services.hitch_supplier_error_sp_processor import HitchSupplierErrorSpProcessor
        HitchSupplierErrorSpProcessor(MYSQL_CONFIG)
        # 初始化Hitch Supplier Error Total处理器表
        from services.hitch_supplier_error_total_processor import HitchSupplierErrorTotalProcessor
        HitchSupplierErrorTotalProcessor(MYSQL_CONFIG)
        # 初始化Hitch Control Cost Time处理器表
        from services.hitch_control_cost_time_processor import HitchControlCostTimeProcessor
        HitchControlCostTimeProcessor(MYSQL_CONFIG)
    
    # 启动定时查询调度器
    from services.scheduler import start_scheduler
    start_scheduler()
    
    app.run(host='0.0.0.0', port=8080, debug=False)
