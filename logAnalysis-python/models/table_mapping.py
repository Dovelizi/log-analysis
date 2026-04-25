# -*- coding: utf-8 -*-
"""
Topic与数据表映射模型
负责Topic关联表的数据库结构定义与管理
支持MySQL和SQLite
"""
import json
import re
from datetime import datetime


class TableMappingModel:
    """Topic与数据表映射模型"""
    
    # 支持的字段类型（MySQL/SQLite通用）
    FIELD_TYPES_MYSQL = {
        'TEXT': 'TEXT',
        'VARCHAR': 'VARCHAR(255)',
        'INTEGER': 'INT',
        'REAL': 'DOUBLE',
        'BLOB': 'BLOB',
        'TIMESTAMP': 'TIMESTAMP NULL',
        'JSON': 'JSON'
    }
    
    FIELD_TYPES_SQLITE = {
        'TEXT': 'TEXT',
        'VARCHAR': 'VARCHAR(255)',
        'INTEGER': 'INTEGER',
        'REAL': 'REAL',
        'BLOB': 'BLOB',
        'TIMESTAMP': 'TIMESTAMP',
        'JSON': 'TEXT'
    }
    
    def __init__(self, db_config):
        """
        初始化模型
        
        Args:
            db_config: MySQL配置字典或SQLite数据库路径
        """
        if isinstance(db_config, dict):
            self.db_type = 'mysql'
            self.db_config = db_config
        else:
            self.db_type = 'sqlite'
            self.db_path = db_config
        
        self._init_mapping_tables()
    
    def _get_connection(self):
        """获取数据库连接"""
        if self.db_type == 'mysql':
            import pymysql
            import pymysql.cursors
            conn = pymysql.connect(**self.db_config)
            return conn
        else:
            import sqlite3
            conn = sqlite3.connect(self.db_path)
            conn.row_factory = sqlite3.Row
            return conn
    
    def _get_cursor(self, conn):
        """获取游标"""
        if self.db_type == 'mysql':
            import pymysql.cursors
            return conn.cursor(pymysql.cursors.DictCursor)
        else:
            return conn.cursor()
    
    def _get_placeholder(self):
        """获取SQL占位符"""
        return '%s' if self.db_type == 'mysql' else '?'
    
    def _row_to_dict(self, row):
        """将行转换为字典"""
        if row is None:
            return None
        if isinstance(row, dict):
            return row
        return dict(row)
    
    def _get_field_types(self):
        """获取当前数据库的字段类型映射"""
        return self.FIELD_TYPES_MYSQL if self.db_type == 'mysql' else self.FIELD_TYPES_SQLITE
    
    def _init_mapping_tables(self):
        """初始化映射相关的数据表"""
        conn = self._get_connection()
        cursor = conn.cursor()
        ph = self._get_placeholder()
        
        if self.db_type == 'mysql':
            # MySQL表结构
            cursor.execute('''
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            ''')
            
            # 检查并添加 filter_config 列（兼容已存在的表）
            try:
                cursor.execute("SELECT filter_config FROM topic_table_mappings LIMIT 1")
            except:
                cursor.execute("ALTER TABLE topic_table_mappings ADD COLUMN filter_config JSON AFTER field_config")
            
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS field_mappings (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    mapping_id INT NOT NULL,
                    source_field VARCHAR(255) NOT NULL,
                    target_column VARCHAR(255) NOT NULL,
                    field_type VARCHAR(50) DEFAULT 'TEXT',
                    is_required TINYINT DEFAULT 0,
                    default_value TEXT,
                    transform_rule TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_mapping_id (mapping_id),
                    FOREIGN KEY (mapping_id) REFERENCES topic_table_mappings(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            ''')
            
            cursor.execute('''
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            ''')
        else:
            # SQLite表结构
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS topic_table_mappings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    topic_id INTEGER NOT NULL,
                    table_name TEXT NOT NULL UNIQUE,
                    table_display_name TEXT,
                    description TEXT,
                    field_config TEXT NOT NULL,
                    filter_config TEXT,
                    auto_collect INTEGER DEFAULT 1,
                    status INTEGER DEFAULT 1,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            ''')
            
            # 检查并添加 filter_config 列（兼容已存在的表）
            cursor.execute("PRAGMA table_info(topic_table_mappings)")
            columns = [col[1] for col in cursor.fetchall()]
            if 'filter_config' not in columns:
                cursor.execute("ALTER TABLE topic_table_mappings ADD COLUMN filter_config TEXT")
            
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS field_mappings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    mapping_id INTEGER NOT NULL,
                    source_field TEXT NOT NULL,
                    target_column TEXT NOT NULL,
                    field_type TEXT DEFAULT 'TEXT',
                    is_required INTEGER DEFAULT 0,
                    default_value TEXT,
                    transform_rule TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (mapping_id) REFERENCES topic_table_mappings(id) ON DELETE CASCADE
                )
            ''')
            
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS collection_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    mapping_id INTEGER NOT NULL,
                    collected_count INTEGER DEFAULT 0,
                    success_count INTEGER DEFAULT 0,
                    error_count INTEGER DEFAULT 0,
                    error_message TEXT,
                    started_at TIMESTAMP,
                    finished_at TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (mapping_id) REFERENCES topic_table_mappings(id)
                )
            ''')
        
        conn.commit()
        conn.close()
    
    def _validate_table_name(self, table_name):
        """验证表名合法性"""
        if not re.match(r'^[a-zA-Z][a-zA-Z0-9_]*$', table_name):
            raise ValueError(f"表名 '{table_name}' 不合法，只能包含字母、数字、下划线，且必须以字母开头")
        reserved_words = ['select', 'insert', 'update', 'delete', 'drop', 'create', 'table', 'index']
        if table_name.lower() in reserved_words:
            raise ValueError(f"表名 '{table_name}' 是保留关键字")
        return True
    
    def _validate_column_name(self, column_name):
        """验证列名合法性"""
        if not re.match(r'^[a-zA-Z][a-zA-Z0-9_]*$', column_name):
            raise ValueError(f"列名 '{column_name}' 不合法")
        return True
    
    def create_mapping(self, topic_id, table_name, field_config, table_display_name=None, description=None, auto_collect=True, filter_config=None):
        """创建Topic与数据表的映射关系"""
        self._validate_table_name(table_name)
        
        if not field_config or not isinstance(field_config, list):
            raise ValueError("字段配置不能为空")
        
        for field in field_config:
            self._validate_column_name(field.get('name', ''))
            # 字段类型支持手动填写，不再限制必须在预定义类型中
        
        conn = self._get_connection()
        cursor = conn.cursor()
        ph = self._get_placeholder()
        
        table_created = False  # 标记表是否已创建
        
        try:
            # 先创建实际的数据表（DDL语句会自动提交，无法回滚）
            self._create_data_table(cursor, table_name, field_config)
            table_created = True
            
            # 创建映射记录
            cursor.execute(f'''
                INSERT INTO topic_table_mappings 
                (topic_id, table_name, table_display_name, description, field_config, filter_config, auto_collect)
                VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph})
            ''', (
                topic_id,
                table_name,
                table_display_name or table_name,
                description,
                json.dumps(field_config, ensure_ascii=False),
                json.dumps(filter_config, ensure_ascii=False) if filter_config else None,
                1 if auto_collect else 0
            ))
            
            if self.db_type == 'mysql':
                mapping_id = cursor.lastrowid
            else:
                mapping_id = cursor.lastrowid
            
            # 创建字段映射记录
            for field in field_config:
                # source_field: 优先用source，如果为空则用name
                source_field = field.get('source') or field.get('name') or ''
                cursor.execute(f'''
                    INSERT INTO field_mappings 
                    (mapping_id, source_field, target_column, field_type, is_required, default_value, transform_rule)
                    VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph})
                ''', (
                    mapping_id,
                    source_field,
                    field.get('name'),
                    field.get('type', 'TEXT'),
                    1 if field.get('required', False) else 0,
                    field.get('default'),
                    field.get('transform')
                ))
            
            conn.commit()
            return mapping_id
        except Exception as e:
            conn.rollback()
            
            # 如果表已创建但后续步骤失败，需要删除已创建的表
            if table_created:
                try:
                    if self.db_type == 'mysql':
                        cursor.execute(f"DROP TABLE IF EXISTS `{table_name}`")
                    else:
                        cursor.execute(f"DROP TABLE IF EXISTS {table_name}")
                    conn.commit()
                except:
                    pass  # 删除表失败不影响错误抛出
            
            if 'Duplicate' in str(e) or 'UNIQUE' in str(e):
                raise ValueError(f"创建映射失败: 表名已存在")
            raise e
        finally:
            conn.close()
    
    def _create_data_table(self, cursor, table_name, field_config):
        """创建实际的数据存储表"""
        field_types = self._get_field_types()
        
        # MySQL 中不能设置默认值的类型
        no_default_types = ('TEXT', 'BLOB', 'JSON', 'GEOMETRY')
        
        if self.db_type == 'mysql':
            columns = [
                'id INT PRIMARY KEY AUTO_INCREMENT'
            ]
        else:
            columns = [
                'id INTEGER PRIMARY KEY AUTOINCREMENT'
            ]
        
        for field in field_config:
            col_name = field.get('name')
            
            # 跳过系统自动生成的列
            if col_name and col_name.lower() == 'id':
                continue
            
            field_type = field.get('type', 'TEXT')
            
            # 支持手动填写的数据类型：如果类型在预定义映射中则使用映射值，否则直接使用用户填写的类型
            if field_type in field_types:
                col_type = field_types[field_type]
            else:
                # 用户手动填写的类型，直接使用（如 VARCHAR(100), INT(11), DECIMAL(10,2) 等）
                col_type = field_type
            
            col_def = f"`{col_name}` {col_type}" if self.db_type == 'mysql' else f"{col_name} {col_type}"
            
            if field.get('required', False):
                col_def += " NOT NULL"
            
            # MySQL 中 TEXT/BLOB/JSON/GEOMETRY 类型不能设置默认值
            # 检查类型是否包含不支持默认值的关键字
            field_type_upper = field_type.upper()
            is_no_default_type = any(t in field_type_upper for t in no_default_types)
            
            default_val = field.get('default')
            # 只有当默认值不为空时才设置
            if default_val is not None and default_val != '':
                # 跳过数据库特殊默认值和不支持默认值的类型
                if default_val in ('CURRENT_TIMESTAMP', 'NOW()', 'NULL'):
                    pass  # 这些由数据库处理，不在建表时设置
                elif self.db_type == 'mysql' and is_no_default_type:
                    pass  # MySQL 中这些类型不能设置默认值
                elif isinstance(default_val, str):
                    col_def += f" DEFAULT '{default_val}'"
                else:
                    col_def += f" DEFAULT {default_val}"
            columns.append(col_def)
        
        if self.db_type == 'mysql':
            create_sql = f"CREATE TABLE IF NOT EXISTS `{table_name}` ({', '.join(columns)}) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        else:
            create_sql = f"CREATE TABLE IF NOT EXISTS {table_name} ({', '.join(columns)})"
        
        cursor.execute(create_sql)
    
    def _get_table_columns(self, cursor, table_name):
        """
        从数据库获取表的实际字段结构
        
        Args:
            cursor: 数据库游标
            table_name: 表名
        
        Returns:
            字段配置列表 [{name, type, default, ...}, ...]
        """
        columns = []
        try:
            if self.db_type == 'mysql':
                cursor.execute(f"DESCRIBE `{table_name}`")
                rows = cursor.fetchall()
                for row in rows:
                    row_dict = self._row_to_dict(row) if not isinstance(row, dict) else row
                    # MySQL DESCRIBE 返回: Field, Type, Null, Key, Default, Extra
                    col_name = row_dict.get('Field', row[0] if isinstance(row, tuple) else None)
                    col_type = row_dict.get('Type', row[1] if isinstance(row, tuple) else None)
                    col_default = row_dict.get('Default', row[4] if isinstance(row, tuple) else None)
                    col_extra = row_dict.get('Extra', row[5] if isinstance(row, tuple) else None)
                    
                    columns.append({
                        'name': col_name,
                        'type': col_type.upper() if col_type else 'TEXT',
                        'default': col_default,
                        'extra': col_extra
                    })
            else:
                cursor.execute(f"PRAGMA table_info({table_name})")
                rows = cursor.fetchall()
                for row in rows:
                    # SQLite PRAGMA table_info 返回: cid, name, type, notnull, dflt_value, pk
                    col_name = row[1]
                    col_type = row[2]
                    col_default = row[4]
                    
                    columns.append({
                        'name': col_name,
                        'type': col_type.upper() if col_type else 'TEXT',
                        'default': col_default
                    })
        except Exception as e:
            print(f"获取表结构失败: {e}")
        
        return columns
    
    def _sync_field_config_with_db(self, cursor, table_name, field_config):
        """
        将field_config与数据库实际表结构同步
        以数据库表结构为准，保留field_config中的source、transform等配置
        
        Args:
            cursor: 数据库游标
            table_name: 表名
            field_config: 原始字段配置
        
        Returns:
            同步后的字段配置
        """
        # 获取数据库实际表结构
        db_columns = self._get_table_columns(cursor, table_name)
        if not db_columns:
            return field_config
        
        # 将原始field_config转为字典方便查找
        config_map = {}
        if field_config:
            for fc in field_config:
                config_map[fc.get('name')] = fc
        
        # 以数据库结构为准，合并配置
        synced_config = []
        for col in db_columns:
            col_name = col['name']
            existing_config = config_map.get(col_name, {})
            
            synced_field = {
                'name': col_name,
                'type': col['type'],
                'source': existing_config.get('source', ''),
                'transform': existing_config.get('transform'),
                'default': col.get('default') or existing_config.get('default'),
                'description': existing_config.get('description', '')
            }
            synced_config.append(synced_field)
        
        return synced_config
    
    def get_mapping(self, mapping_id):
        """获取单个映射详情"""
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        cursor.execute(f'''
            SELECT m.*, t.topic_id as cls_topic_id, t.topic_name, c.name as credential_name
            FROM topic_table_mappings m
            JOIN log_topics t ON m.topic_id = t.id
            JOIN api_credentials c ON t.credential_id = c.id
            WHERE m.id = {ph}
        ''', (mapping_id,))
        
        row = cursor.fetchone()
        
        if row:
            result = self._row_to_dict(row)
            if isinstance(result['field_config'], str):
                result['field_config'] = json.loads(result['field_config'])
            
            # 解析filter_config
            if result.get('filter_config'):
                if isinstance(result['filter_config'], str):
                    result['filter_config'] = json.loads(result['filter_config'])
            else:
                result['filter_config'] = None
            
            # 同步field_config与数据库实际表结构
            table_name = result.get('table_name')
            if table_name:
                result['field_config'] = self._sync_field_config_with_db(cursor, table_name, result['field_config'])
            
            # 处理时间格式
            for key in ['created_at', 'updated_at']:
                if result.get(key) and hasattr(result[key], 'isoformat'):
                    result[key] = result[key].isoformat()
            
            conn.close()
            return result
        
        conn.close()
        return None
    
    def get_mapping_by_topic(self, topic_id):
        """根据Topic ID获取映射"""
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        cursor.execute(f'''
            SELECT m.*, t.topic_id as cls_topic_id, t.topic_name
            FROM topic_table_mappings m
            JOIN log_topics t ON m.topic_id = t.id
            WHERE m.topic_id = {ph}
        ''', (topic_id,))
        
        rows = cursor.fetchall()
        
        result = []
        for row in rows:
            item = self._row_to_dict(row)
            if isinstance(item['field_config'], str):
                item['field_config'] = json.loads(item['field_config'])
            
            # 解析filter_config
            if item.get('filter_config'):
                if isinstance(item['filter_config'], str):
                    item['filter_config'] = json.loads(item['filter_config'])
            else:
                item['filter_config'] = None
            
            # 同步field_config与数据库实际表结构
            table_name = item.get('table_name')
            if table_name:
                item['field_config'] = self._sync_field_config_with_db(cursor, table_name, item['field_config'])
            
            result.append(item)
        
        conn.close()
        return result
    
    def get_all_mappings(self):
        """获取所有映射关系"""
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        
        cursor.execute('''
            SELECT m.*, t.topic_id as cls_topic_id, t.topic_name, c.name as credential_name
            FROM topic_table_mappings m
            JOIN log_topics t ON m.topic_id = t.id
            JOIN api_credentials c ON t.credential_id = c.id
            ORDER BY m.created_at DESC
        ''')
        
        rows = cursor.fetchall()
        
        result = []
        for row in rows:
            item = self._row_to_dict(row)
            if isinstance(item['field_config'], str):
                item['field_config'] = json.loads(item['field_config'])
            
            # 解析filter_config
            if item.get('filter_config'):
                if isinstance(item['filter_config'], str):
                    item['filter_config'] = json.loads(item['filter_config'])
            else:
                item['filter_config'] = None
            
            # 同步field_config与数据库实际表结构
            table_name = item.get('table_name')
            if table_name:
                item['field_config'] = self._sync_field_config_with_db(cursor, table_name, item['field_config'])
            
            # 处理时间格式
            for key in ['created_at', 'updated_at']:
                if item.get(key) and hasattr(item[key], 'isoformat'):
                    item[key] = item[key].isoformat()
            result.append(item)
        
        conn.close()
        return result
    
    def update_mapping(self, mapping_id, **kwargs):
        """更新映射配置"""
        conn = self._get_connection()
        cursor = conn.cursor()
        ph = self._get_placeholder()
        
        allowed_fields = ['table_display_name', 'description', 'auto_collect', 'status']
        updates = []
        params = []
        
        for field in allowed_fields:
            if field in kwargs and kwargs[field] is not None:
                updates.append(f"{field} = {ph}")
                params.append(kwargs[field])
        
        # 处理field_config更新
        if 'field_config' in kwargs and kwargs['field_config'] is not None:
            updates.append(f"field_config = {ph}")
            field_config = kwargs['field_config']
            if isinstance(field_config, list):
                params.append(json.dumps(field_config, ensure_ascii=False))
            else:
                params.append(field_config)
        
        # 处理filter_config更新（入库条件配置）
        if 'filter_config' in kwargs:
            updates.append(f"filter_config = {ph}")
            filter_config = kwargs['filter_config']
            if filter_config is None:
                params.append(None)
            elif isinstance(filter_config, (dict, list)):
                params.append(json.dumps(filter_config, ensure_ascii=False))
            else:
                params.append(filter_config)
        
        if updates:
            if self.db_type == 'sqlite':
                updates.append("updated_at = CURRENT_TIMESTAMP")
            params.append(mapping_id)
            cursor.execute(
                f"UPDATE topic_table_mappings SET {', '.join(updates)} WHERE id = {ph}",
                params
            )
            conn.commit()
        
        conn.close()
        return True
    
    def update_field_mappings(self, mapping_id, field_config):
        """更新字段映射配置，支持新增列到数据库表"""
        conn = self._get_connection()
        cursor = conn.cursor()
        ph = self._get_placeholder()
        
        try:
            # 获取表名
            cursor.execute(f'SELECT table_name FROM topic_table_mappings WHERE id = {ph}', (mapping_id,))
            row = cursor.fetchone()
            if not row:
                raise ValueError("映射配置不存在")
            table_name = row[0] if isinstance(row, tuple) else row['table_name']
            
            # 检查表是否存在
            table_exists = False
            if self.db_type == 'mysql':
                cursor.execute(f"SHOW TABLES LIKE '{table_name}'")
                table_exists = cursor.fetchone() is not None
            else:
                cursor.execute(f"SELECT name FROM sqlite_master WHERE type='table' AND name='{table_name}'")
                table_exists = cursor.fetchone() is not None
            
            # 如果表不存在，创建表
            if not table_exists:
                self._create_data_table(cursor, table_name, field_config)
                existing_columns = set()
                # 获取新创建表的列
                if self.db_type == 'mysql':
                    cursor.execute(f"DESCRIBE `{table_name}`")
                    for col in cursor.fetchall():
                        col_name = col[0] if isinstance(col, tuple) else col['Field']
                        existing_columns.add(col_name.lower())
                else:
                    cursor.execute(f"PRAGMA table_info({table_name})")
                    for col in cursor.fetchall():
                        col_name = col[1] if isinstance(col, tuple) else col['name']
                        existing_columns.add(col_name.lower())
            else:
                # 获取当前表的列
                existing_columns = set()
                if self.db_type == 'mysql':
                    cursor.execute(f"DESCRIBE `{table_name}`")
                    for col in cursor.fetchall():
                        col_name = col[0] if isinstance(col, tuple) else col['Field']
                        existing_columns.add(col_name.lower())
                else:
                    cursor.execute(f"PRAGMA table_info({table_name})")
                    for col in cursor.fetchall():
                        col_name = col[1] if isinstance(col, tuple) else col['name']
                        existing_columns.add(col_name.lower())
            
            # 检查并添加新列
            field_types = self._get_field_types()
            no_default_types = ('TEXT', 'BLOB', 'JSON', 'GEOMETRY')
            
            for field in field_config:
                col_name = field.get('name')
                if not col_name:
                    continue
                    
                # 如果是新列（不在现有列中）
                if col_name.lower() not in existing_columns:
                    field_type = field.get('type', 'TEXT')
                    
                    # 支持手动填写的数据类型
                    if field_type in field_types:
                        col_type = field_types[field_type]
                    else:
                        col_type = field_type
                    
                    # 构建 ALTER TABLE 语句
                    if self.db_type == 'mysql':
                        alter_sql = f"ALTER TABLE `{table_name}` ADD COLUMN `{col_name}` {col_type}"
                    else:
                        alter_sql = f"ALTER TABLE {table_name} ADD COLUMN {col_name} {col_type}"
                    
                    # 添加默认值（如果有且类型支持，且不为空字符串）
                    default_val = field.get('default')
                    if default_val is not None and default_val != '':
                        field_type_upper = field_type.upper()
                        is_no_default_type = any(t in field_type_upper for t in no_default_types)
                        if not (self.db_type == 'mysql' and is_no_default_type):
                            if default_val not in ('CURRENT_TIMESTAMP', 'NOW()', 'NULL'):
                                if isinstance(default_val, str):
                                    alter_sql += f" DEFAULT '{default_val}'"
                                else:
                                    alter_sql += f" DEFAULT {default_val}"
                    
                    cursor.execute(alter_sql)
                
                # 移除 isNew 标记
                if 'isNew' in field:
                    del field['isNew']
            
            # 删除旧的字段映射
            cursor.execute(f'DELETE FROM field_mappings WHERE mapping_id = {ph}', (mapping_id,))
            
            # 插入新的字段映射
            for field in field_config:
                # source_field: 优先用source，如果为空则用name
                source_field = field.get('source') or field.get('name') or ''
                cursor.execute(f'''
                    INSERT INTO field_mappings 
                    (mapping_id, source_field, target_column, field_type, is_required, default_value, transform_rule)
                    VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph})
                ''', (
                    mapping_id,
                    source_field,
                    field.get('name'),
                    field.get('type', 'TEXT'),
                    1 if field.get('required', False) else 0,
                    field.get('default'),
                    field.get('transform')
                ))
            
            # 同时更新topic_table_mappings中的field_config
            cursor.execute(f'''
                UPDATE topic_table_mappings SET field_config = {ph} WHERE id = {ph}
            ''', (json.dumps(field_config, ensure_ascii=False), mapping_id))
            
            conn.commit()
            return True
        except Exception as e:
            conn.rollback()
            raise e
        finally:
            conn.close()
    
    def delete_mapping(self, mapping_id, drop_table=False):
        """删除映射关系"""
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        # 获取表名
        cursor.execute(f'SELECT table_name FROM topic_table_mappings WHERE id = {ph}', (mapping_id,))
        row = cursor.fetchone()
        
        if row:
            row = self._row_to_dict(row)
            if drop_table:
                table_name = row['table_name']
                self._validate_table_name(table_name)
                if self.db_type == 'mysql':
                    cursor.execute(f"DROP TABLE IF EXISTS `{table_name}`")
                else:
                    cursor.execute(f"DROP TABLE IF EXISTS {table_name}")
        
        # 删除映射记录
        cursor.execute(f'DELETE FROM topic_table_mappings WHERE id = {ph}', (mapping_id,))
        
        conn.commit()
        conn.close()
        return True
    
    def get_field_mappings(self, mapping_id):
        """获取字段映射配置"""
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        cursor.execute(f'''
            SELECT * FROM field_mappings WHERE mapping_id = {ph} ORDER BY id
        ''', (mapping_id,))
        
        rows = cursor.fetchall()
        conn.close()
        
        return [self._row_to_dict(row) for row in rows]
    
    def get_table_data(self, table_name, limit=100, offset=0, order_by='id', order_dir='DESC', display_columns=None):
        """获取数据表中的数据
        
        Args:
            table_name: 表名
            limit: 每页数量
            offset: 偏移量
            order_by: 排序字段
            order_dir: 排序方向
            display_columns: 要显示的列列表，为None时显示所有列
        """
        self._validate_table_name(table_name)
        
        if order_dir.upper() not in ('ASC', 'DESC'):
            order_dir = 'DESC'
        
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        try:
            # 获取表结构
            if self.db_type == 'mysql':
                cursor.execute(f"DESCRIBE `{table_name}`")
                all_columns = [col['Field'] for col in cursor.fetchall()]
            else:
                cursor.execute(f"PRAGMA table_info({table_name})")
                all_columns = [col['name'] for col in cursor.fetchall()]
            
            # 如果指定了显示列，则过滤（保持指定顺序，但只保留存在的列）
            if display_columns:
                columns = [col for col in display_columns if col in all_columns]
                # 如果过滤后为空，则使用所有列
                if not columns:
                    columns = all_columns
            else:
                columns = all_columns
            
            # 验证排序字段
            if order_by not in all_columns:
                order_by = 'id'
            
            # 查询数据
            if self.db_type == 'mysql':
                cursor.execute(f"SELECT * FROM `{table_name}` ORDER BY `{order_by}` {order_dir} LIMIT {ph} OFFSET {ph}", (limit, offset))
            else:
                cursor.execute(f"SELECT * FROM {table_name} ORDER BY {order_by} {order_dir} LIMIT {ph} OFFSET {ph}", (limit, offset))
            rows = cursor.fetchall()
            
            # 获取总数
            if self.db_type == 'mysql':
                cursor.execute(f"SELECT COUNT(*) as total FROM `{table_name}`")
            else:
                cursor.execute(f"SELECT COUNT(*) as total FROM {table_name}")
            total_row = cursor.fetchone()
            total = self._row_to_dict(total_row)['total']
            
            conn.close()
            
            # 处理数据，只保留需要显示的列
            data = []
            for row in rows:
                item = self._row_to_dict(row)
                # 只保留需要显示的列
                filtered_item = {}
                for col in columns:
                    val = item.get(col)
                    # 处理时间格式
                    if val and hasattr(val, 'isoformat'):
                        val = val.isoformat()
                    filtered_item[col] = val
                data.append(filtered_item)
            
            return {
                'columns': columns,
                'data': data,
                'total': total,
                'limit': limit,
                'offset': offset
            }
        except Exception as e:
            conn.close()
            raise e
    
    def get_table_schema(self, table_name):
        """获取表结构信息"""
        self._validate_table_name(table_name)
        
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        
        if self.db_type == 'mysql':
            cursor.execute(f"DESCRIBE `{table_name}`")
            columns = cursor.fetchall()
            result = []
            for col in columns:
                col = self._row_to_dict(col)
                result.append({
                    'name': col['Field'],
                    'type': col['Type'],
                    'notnull': col['Null'] == 'NO',
                    'dflt_value': col['Default'],
                    'pk': col['Key'] == 'PRI'
                })
        else:
            cursor.execute(f"PRAGMA table_info({table_name})")
            columns = cursor.fetchall()
            result = [self._row_to_dict(col) for col in columns]
        
        conn.close()
        return result
    
    def log_collection(self, mapping_id, collected_count, success_count, error_count=0, error_message=None):
        """记录数据采集日志"""
        conn = self._get_connection()
        cursor = conn.cursor()
        ph = self._get_placeholder()
        
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        cursor.execute(f'''
            INSERT INTO collection_logs 
            (mapping_id, collected_count, success_count, error_count, error_message, started_at, finished_at)
            VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph})
        ''', (
            mapping_id,
            collected_count,
            success_count,
            error_count,
            error_message,
            now,
            now
        ))
        
        conn.commit()
        conn.close()
    
    def get_collection_logs(self, mapping_id=None, limit=50, offset=0):
        """获取采集日志"""
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        # 获取总数
        if mapping_id:
            cursor.execute(f'''
                SELECT COUNT(*) as total FROM collection_logs WHERE mapping_id = {ph}
            ''', (mapping_id,))
        else:
            cursor.execute('SELECT COUNT(*) as total FROM collection_logs')
        total_row = cursor.fetchone()
        total = self._row_to_dict(total_row)['total']
        
        # 获取数据
        if mapping_id:
            cursor.execute(f'''
                SELECT cl.*, m.table_name, m.table_display_name
                FROM collection_logs cl
                JOIN topic_table_mappings m ON cl.mapping_id = m.id
                WHERE cl.mapping_id = {ph}
                ORDER BY cl.created_at DESC
                LIMIT {ph} OFFSET {ph}
            ''', (mapping_id, limit, offset))
        else:
            cursor.execute(f'''
                SELECT cl.*, m.table_name, m.table_display_name
                FROM collection_logs cl
                JOIN topic_table_mappings m ON cl.mapping_id = m.id
                ORDER BY cl.created_at DESC
                LIMIT {ph} OFFSET {ph}
            ''', (limit, offset))
        
        rows = cursor.fetchall()
        conn.close()
        
        data = []
        for row in rows:
            item = self._row_to_dict(row)
            # 处理时间格式
            for key in ['started_at', 'finished_at', 'created_at']:
                if item.get(key) and hasattr(item[key], 'isoformat'):
                    item[key] = item[key].isoformat()
            data.append(item)
        
        return {
            'data': data,
            'total': total,
            'limit': limit,
            'offset': offset
        }
    
    def insert_data(self, table_name, data_list):
        """向数据表插入数据"""
        self._validate_table_name(table_name)
        
        if not data_list:
            return 0
        
        conn = self._get_connection()
        cursor = conn.cursor()
        ph = self._get_placeholder()
        
        try:
            # 获取表的列名
            if self.db_type == 'mysql':
                cursor.execute(f"DESCRIBE `{table_name}`")
                columns = [col[0] if isinstance(col, tuple) else col['Field'] for col in cursor.fetchall()]
            else:
                cursor.execute(f"PRAGMA table_info({table_name})")
                columns = [col['name'] for col in cursor.fetchall()]
            
            # 排除自动生成的列
            exclude_cols = ['id', 'collected_at']
            insert_cols = [c for c in columns if c not in exclude_cols]
            
            inserted = 0
            for data in data_list:
                values = []
                cols_to_insert = []
                for col in insert_cols:
                    if col in data:
                        cols_to_insert.append(col)
                        val = data[col]
                        # 处理JSON类型
                        if isinstance(val, (dict, list)):
                            val = json.dumps(val, ensure_ascii=False)
                        values.append(val)
                
                if cols_to_insert:
                    if self.db_type == 'mysql':
                        col_str = ', '.join([f'`{c}`' for c in cols_to_insert])
                    else:
                        col_str = ', '.join(cols_to_insert)
                    placeholders = ', '.join([ph] * len(values))
                    
                    if self.db_type == 'mysql':
                        sql = f"INSERT INTO `{table_name}` ({col_str}) VALUES ({placeholders})"
                    else:
                        sql = f"INSERT INTO {table_name} ({col_str}) VALUES ({placeholders})"
                    
                    cursor.execute(sql, values)
                    inserted += 1
            
            conn.commit()
            return inserted
        except Exception as e:
            conn.rollback()
            raise e
        finally:
            conn.close()
