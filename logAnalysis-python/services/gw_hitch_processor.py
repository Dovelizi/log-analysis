# -*- coding: utf-8 -*-
"""
GW Hitch日志专用数据处理器
负责将GW日志数据按照特定规则转换并写入gw_hitch_error_mothod表
"""
import json
import re
from datetime import datetime
from typing import List, Dict, Any, Optional
from collections import Counter

from services.redis_cache_service import redis_cache
from services.insert_record_service import record_insert_log, LOG_FROM_GW_HITCH


class GwHitchProcessor:
    """GW Hitch日志数据处理器"""
    
    def __init__(self, db_config):
        """
        初始化处理器
        
        Args:
            db_config: MySQL配置字典或SQLite数据库路径
        """
        if isinstance(db_config, dict):
            self.db_type = 'mysql'
            self.db_config = db_config
        else:
            self.db_type = 'sqlite'
            self.db_path = db_config
        
        # 缓存映射规则配置
        self._field_config_cache = None
        
        # 初始化表结构
        self._init_table()
    
    def clear_config_cache(self):
        """清除字段配置缓存，用于在映射规则更新后重新加载"""
        self._field_config_cache = None
    
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
    
    def _init_table(self):
        """初始化gw_hitch_error_mothod表"""
        conn = self._get_connection()
        cursor = conn.cursor()
        
        if self.db_type == 'mysql':
            cursor.execute('''
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='网关顺风车业务错误方法聚合监控表'
            ''')
        else:
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS gw_hitch_error_mothod (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    method_name TEXT,
                    error_code INTEGER,
                    error_message TEXT,
                    content TEXT,
                    count INTEGER DEFAULT 0,
                    total_count INTEGER DEFAULT 0,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            ''')
        
        conn.commit()
        conn.close()
    
    def _get_field_config(self) -> List[Dict]:
        """
        获取字段映射配置
        优先从数据库读取，如果没有则使用默认配置
        
        Returns:
            字段配置列表
        """
        if self._field_config_cache is not None:
            return self._field_config_cache
        
        # 默认配置 - 适配新表结构
        default_config = [
            {'name': 'method_name', 'source': 'path', 'transform': 'regex:/.*:0'},
            {'name': 'error_code', 'source': 'response_body', 'transform': 'json_path:resData.code|fallback:errCode'},
            {'name': 'error_message', 'source': 'response_body', 'transform': 'json_path:resData.message|fallback:errMsg'},
            {'name': 'content', 'source': 'response_body', 'transform': 'substr:10240'},
        ]
        
        # 尝试从数据库读取配置
        try:
            conn = self._get_connection()
            cursor = self._get_cursor(conn)
            ph = self._get_placeholder()
            
            cursor.execute(f'''
                SELECT field_config FROM topic_table_mappings 
                WHERE table_name = {ph}
            ''', ('gw_hitch_error_mothod',))
            
            row = cursor.fetchone()
            conn.close()
            
            if row:
                row_dict = self._row_to_dict(row)
                field_config = row_dict.get('field_config')
                if field_config:
                    if isinstance(field_config, str):
                        field_config = json.loads(field_config)
                    # 过滤掉自动生成的字段
                    self._field_config_cache = [f for f in field_config if f.get('source')]
                    return self._field_config_cache
        except Exception as e:
            print(f"读取映射配置失败: {e}")
        
        self._field_config_cache = default_config
        return self._field_config_cache
    
    def _get_nested_value(self, obj: Any, path: str) -> Any:
        """
        从嵌套对象中获取值
        
        Args:
            obj: 源对象
            path: 路径，如 'resData.code'
        
        Returns:
            获取到的值
        """
        if not path or obj is None:
            return obj
        
        keys = path.split('.')
        value = obj
        for key in keys:
            if value is None:
                return None
            if isinstance(value, dict):
                value = value.get(key)
            else:
                return None
        return value
    
    def _apply_transform(self, value: Any, transform: str, full_data: Dict) -> Any:
        """
        应用转换规则
        
        Args:
            value: 原始值
            transform: 转换规则字符串
            full_data: 完整的日志数据（用于fallback等操作）
        
        Returns:
            转换后的值
        """
        if not transform:
            return value
        
        # 处理多个转换规则（用|分隔）
        transforms = transform.split('|')
        result = value
        
        for t in transforms:
            t = t.strip()
            
            if t == 'trim' and isinstance(result, str):
                result = result.strip()
            elif t == 'lower' and isinstance(result, str):
                result = result.lower()
            elif t == 'upper' and isinstance(result, str):
                result = result.upper()
            elif t.startswith('json_path:'):
                path = t[10:]
                try:
                    json_data = result
                    if isinstance(result, str):
                        json_data = json.loads(result)
                    result = self._get_nested_value(json_data, path)
                except (json.JSONDecodeError, TypeError):
                    pass
            elif t.startswith('regex:'):
                # 正则表达式可能包含冒号，最后一个部分如果是纯数字则为捕获组索引
                parts = t.split(':')
                if len(parts) >= 2 and isinstance(result, str):
                    try:
                        last_part = parts[-1]
                        if re.match(r'^\d+$', last_part):
                            group = int(last_part)
                            pattern = ':'.join(parts[1:-1])
                        else:
                            group = 0
                            pattern = ':'.join(parts[1:])
                        
                        match = re.search(pattern, result)
                        if match:
                            result = match.group(group) if group <= len(match.groups()) else match.group(0)
                        else:
                            result = None
                    except Exception:
                        result = None
            elif t.startswith('split:'):
                parts = t.split(':')
                if len(parts) >= 3 and isinstance(result, str):
                    sep = parts[1]
                    idx = int(parts[2]) if parts[2].isdigit() else 0
                    arr = result.split(sep)
                    result = arr[idx] if idx < len(arr) else None
            elif t.startswith('fallback:'):
                # 如果当前值为空，从备选字段取值
                if result is None or result == '':
                    fallback_field = t[9:]
                    try:
                        # 尝试从response_body等JSON字段中获取
                        source_field = full_data.get('response_body')
                        if source_field and isinstance(source_field, str):
                            json_data = json.loads(source_field)
                            result = self._get_nested_value(json_data, fallback_field)
                        else:
                            result = self._get_nested_value(full_data, fallback_field)
                    except (json.JSONDecodeError, TypeError):
                        result = self._get_nested_value(full_data, fallback_field)
            elif t.startswith('datetime_parse:'):
                # 日期时间处理：去除毫秒部分
                if isinstance(result, str):
                    # 处理 "2025-12-30 15:01:53.378" 格式
                    if '.' in result:
                        result = result.split('.')[0]
                    # 处理 "2025-12-30 15:01:53 378" 格式
                    match = re.match(r'^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+\d+$', result)
                    if match:
                        result = match.group(1)
            elif t.startswith('default_if_empty:'):
                if result is None or result == '':
                    result = t[17:]
            elif t.startswith('default_code:'):
                if result is None or result == '':
                    default_code = t[13:]
                    result = int(default_code) if default_code.isdigit() else 500
        
        return result
    
    def _parse_response_body(self, response_body: str) -> Dict:
        """
        解析response_body JSON字符串（兼容旧逻辑，现在通过映射规则处理）
        
        优先级：
        1. 如果 resData 存在且有 code 和 message，使用 resData.code 和 resData.message
        2. 否则使用外层的 errCode 和 errMsg
        
        Args:
            response_body: response_body JSON字符串
        
        Returns:
            包含code和message的字典
        """
        result = {'code': None, 'message': None}
        
        if not response_body:
            return result
        
        try:
            data = json.loads(response_body)
            
            # 优先从resData中获取code和message
            res_data = data.get('resData', {})
            if res_data and (res_data.get('code') is not None or res_data.get('message')):
                result['code'] = res_data.get('code')
                result['message'] = res_data.get('message')
            else:
                # 如果resData不存在或没有code/message，使用外层的errCode和errMsg
                result['code'] = data.get('errCode')
                result['message'] = data.get('errMsg')
        except (json.JSONDecodeError, TypeError):
            pass
        
        return result
    
    def _extract_interface_name(self, path: str) -> str:
        """
        从path中提取interface_name
        去掉前缀，从第一个/开始取值
        例如: "POST /hitchride/order/addition" -> "/hitchride/order/addition"
        
        Args:
            path: 原始path字符串
        
        Returns:
            处理后的interface_name
        """
        if not path:
            return ''
        
        # 查找第一个/的位置
        slash_index = path.find('/')
        if slash_index >= 0:
            return path[slash_index:]
        return path
    
    def _parse_log_time(self, time_str: str) -> Optional[str]:
        """
        解析日志时间
        
        Args:
            time_str: 时间字符串，格式如 "2025-12-30 09:24:58.564"
        
        Returns:
            格式化的时间字符串
        """
        if not time_str:
            return None
        
        try:
            # 尝试解析带毫秒的时间格式
            if '.' in time_str:
                dt = datetime.strptime(time_str, '%Y-%m-%d %H:%M:%S.%f')
            else:
                dt = datetime.strptime(time_str, '%Y-%m-%d %H:%M:%S')
            return dt.strftime('%Y-%m-%d %H:%M:%S')
        except ValueError:
            return time_str
    
    def transform_log(self, log_data: Dict, query_transform_config: Dict = None) -> Dict:
        """
        将单条日志数据转换为gw_hitch_error_mothod表格式
        严格使用 field_mappings 表中配置的 transform_rule 进行转换
        确保与测试转换使用同一套逻辑
        
        Args:
            log_data: 原始日志数据
            query_transform_config: 查询配置的独立转换规则（优先于表映射规则）
        
        Returns:
            转换后的数据字典
        """
        # 使用统一的转换工具，确保与测试转换使用同一套逻辑
        from services.transform_utils import transform_for_table
        
        try:
            # 获取数据库配置
            if self.db_type == 'mysql':
                db_config = self.db_config
            else:
                db_config = self.db_path
            
            # 使用统一的转换函数，严格按照 field_mappings.transform_rule 转换，传入查询配置的转换规则
            result = transform_for_table('gw_hitch_error_mothod', log_data, db_config, query_transform_config)
            
            # 确保必要字段存在（兜底逻辑）- 适配新表结构
            # 注意：检查 None 和空字符串
            if not result.get('method_name'):
                result['method_name'] = self._extract_interface_name(log_data.get('path', ''))
            if result.get('error_code') is None:
                parsed = self._parse_response_body(log_data.get('response_body', ''))
                result['error_code'] = parsed['code']
            if not result.get('error_message'):
                parsed = self._parse_response_body(log_data.get('response_body', ''))
                result['error_message'] = parsed['message']
            if not result.get('content'):
                response_body = log_data.get('response_body', '')
                result['content'] = response_body[:10240] if response_body else None
            # 默认 count 为 1
            if not result.get('count'):
                result['count'] = 1
            
            return result
            
        except ValueError:
            # 如果没有映射配置，使用默认转换逻辑
            return self._transform_log_default(log_data)
    
    def _transform_log_default(self, log_data: Dict) -> Dict:
        """
        默认的日志转换逻辑（当没有数据库映射配置时使用）
        
        Args:
            log_data: 原始日志数据
        
        Returns:
            转换后的数据字典
        """
        # 获取字段映射配置
        field_config = self._get_field_config()
        
        result = {}
        for field in field_config:
            field_name = field.get('name')
            source = field.get('source')
            transform = field.get('transform')
            default = field.get('default')
            
            # 获取源字段值
            value = log_data.get(source) if source else None
            
            # 应用转换规则
            if transform:
                value = self._apply_transform(value, transform, log_data)
            
            # 如果值为空且有默认值
            if (value is None or value == '') and default is not None:
                value = default
            
            result[field_name] = value
        
        # 确保必要字段存在 - 适配新表结构
        # 注意：检查 None 和空字符串
        if not result.get('method_name'):
            result['method_name'] = self._extract_interface_name(log_data.get('path', ''))
        if result.get('error_code') is None:
            parsed = self._parse_response_body(log_data.get('response_body', ''))
            result['error_code'] = parsed['code']
        if not result.get('error_message'):
            parsed = self._parse_response_body(log_data.get('response_body', ''))
            result['error_message'] = parsed['message']
        # 截断 error_message 以匹配数据库字段长度 VARCHAR(1024)
        if result.get('error_message') and len(result['error_message']) > 1024:
            result['error_message'] = result['error_message'][:1024]
        if not result.get('content'):
            response_body = log_data.get('response_body', '')
            result['content'] = response_body[:10240] if response_body else None
        # 默认 count 为 1
        if not result.get('count'):
            result['count'] = 1
        
        return result
    
    def process_logs(self, log_data_list: List[Dict], query_transform_config: Dict = None, query_filter_config: Dict = None) -> Dict:
        """
        处理日志数据列表并写入数据库
        
        处理流程：
        1. 转换阶段：将所有原始日志转换为目标格式
        2. 过滤阶段：根据入库条件过滤不满足条件的数据
        3. 聚合阶段：在内存中按 method_name + error_code + error_message 分组聚合
        4. 入库阶段：先查Redis，有则更新Redis和MySQL；无则查MySQL再写入Redis和MySQL
        
        Args:
            log_data_list: 日志数据列表
            query_transform_config: 查询配置的独立转换规则（优先于表映射规则）
            query_filter_config: 查询配置的入库条件（可选）
                格式: {enabled: bool, logic: 'and'|'or', conditions: [...]}
                注意: 过滤条件基于转换后的字段值进行判断，字段路径应使用目标表的列名
        
        Returns:
            处理结果统计
        """
        if not log_data_list:
            return {'total': 0, 'success': 0, 'error': 0, 'filtered': 0, 'errors': []}
        
        # 导入过滤器
        from services.transform_utils import FilterEvaluator
        
        # ========== 阶段1：转换所有日志数据 ==========
        transformed_logs = []
        filtered_count = 0
        for log in log_data_list:
            try:
                transformed = self.transform_log(log, query_transform_config)
                
                # ========== 阶段2：检查入库条件（基于转换后的值过滤） ==========
                if query_filter_config and query_filter_config.get('enabled', False):
                    if not FilterEvaluator.evaluate(transformed, query_filter_config):
                        filtered_count += 1
                        continue  # 不满足条件，跳过该条数据
                
                transformed_logs.append(transformed)
            except Exception as e:
                pass  # 忽略转换失败的日志
        
        if not transformed_logs:
            return {'total': len(log_data_list), 'success': 0, 'error': 0, 'filtered': filtered_count, 'errors': [], 'aggregated': 0}
        
        # ========== 阶段2：在内存中按唯一性字段分组聚合 ==========
        # 使用字典存储聚合结果，key 为唯一性字段组合（统一转字符串避免类型问题）
        aggregated_dict = {}
        
        for log in transformed_logs:
            # 构建聚合 key：统一转为字符串，避免 120010 和 "120010" 被认为是不同的 key
            method_name_str = str(log.get('method_name') or '')
            error_code_str = str(log.get('error_code') or '')
            error_message_str = str(log.get('error_message') or '')
            agg_key = (method_name_str, error_code_str, error_message_str)
            
            # 获取原始日志中的 count 值，默认为 1
            current_count = log.get('count', 1) or 1
            
            if agg_key in aggregated_dict:
                # 已存在相同 key，累加 count（使用原始日志的 count 值）
                aggregated_dict[agg_key]['count'] += current_count
            else:
                # 新的 key，初始化聚合数据（使用原始日志的 count 值）
                aggregated_dict[agg_key] = {
                    'method_name': log.get('method_name'),
                    'error_code': log.get('error_code'),
                    'error_message': log.get('error_message'),
                    'content': log.get('content'),
                    'count': current_count
                }
        
        # ========== 阶段3：将聚合后的数据写入数据库（Redis缓存优化） ==========
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        success_count = 0
        error_count = 0
        errors = []
        
        # 表名用于Redis缓存key
        table_name = 'gw_hitch_error_mothod'
        
        # 使用当天的时间范围（00:00:00 到 23:59:59）来判断是否更新已有记录
        today = datetime.now().strftime('%Y-%m-%d')
        today_start = f'{today} 00:00:00'
        today_end = f'{today} 23:59:59'
        
        for idx, (agg_key, agg_data) in enumerate(aggregated_dict.items()):
            try:
                # 本次聚合的 count（即本批次中相同数据的条数）
                batch_count = agg_data['count']
                
                # 获取字段值
                method_name = agg_data['method_name']
                error_code = agg_data['error_code']
                error_message = agg_data['error_message']
                content = agg_data['content']
                
                # 构建Redis缓存key的唯一性字段
                unique_fields = {
                    'method_name': method_name,
                    'error_code': error_code,
                    'error_message': error_message
                }
                
                # 步骤1：先查询Redis缓存
                cached_data = redis_cache.get_cached_data(table_name, **unique_fields)
                
                if cached_data:
                    # Redis中有数据，更新Redis和MySQL
                    # 更新Redis: count直接覆盖，total_count累加
                    old_total = cached_data.get('total_count') or 0
                    new_total = old_total + batch_count
                    
                    cached_data['count'] = batch_count
                    cached_data['total_count'] = new_total
                    cached_data['content'] = content
                    cached_data['update_time'] = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                    
                    # 写回Redis
                    redis_cache.set_cached_data(table_name, cached_data, **unique_fields)
                    
                    # 更新MySQL
                    existing_id = cached_data.get('id')
                    if existing_id:
                        if self.db_type == 'mysql':
                            update_sql = f'''
                                UPDATE `gw_hitch_error_mothod` 
                                SET `count` = {ph}, `total_count` = {ph}, `content` = {ph}
                                WHERE id = {ph}
                            '''
                        else:
                            update_sql = f'''
                                UPDATE gw_hitch_error_mothod 
                                SET count = {ph}, total_count = {ph}, content = {ph}, update_time = CURRENT_TIMESTAMP
                                WHERE id = {ph}
                            '''
                        cursor.execute(update_sql, (batch_count, new_total, content, existing_id))
                else:
                    # Redis中没有数据，查询MySQL（查询当天的数据）
                    if self.db_type == 'mysql':
                        check_sql = f'''
                            SELECT id, count, total_count, create_time, update_time FROM `gw_hitch_error_mothod` 
                            WHERE method_name <=> {ph} AND error_code <=> {ph} AND error_message <=> {ph}
                            AND create_time >= {ph} AND create_time <= {ph}
                            ORDER BY create_time DESC
                            LIMIT 1
                        '''
                    else:
                        check_sql = f'''
                            SELECT id, count, total_count, create_time, update_time FROM gw_hitch_error_mothod 
                            WHERE method_name IS {ph} AND error_code IS {ph} AND error_message IS {ph}
                            AND create_time >= {ph} AND create_time <= {ph}
                            ORDER BY create_time DESC
                            LIMIT 1
                        '''
                    
                    cursor.execute(check_sql, (method_name, error_code, error_message, today_start, today_end))
                    existing = cursor.fetchone()
                    
                    if existing:
                        # MySQL中存在记录
                        existing_row = self._row_to_dict(existing)
                        existing_id = existing_row['id']
                        existing_total = existing_row['total_count'] or 0
                        new_total = existing_total + batch_count
                        
                        # 先写入Redis缓存
                        cache_data = {
                            'id': existing_id,
                            'method_name': method_name,
                            'error_code': error_code,
                            'error_message': error_message,
                            'content': content,
                            'count': batch_count,
                            'total_count': new_total,
                            'create_time': str(existing_row.get('create_time', '')),
                            'update_time': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                        }
                        redis_cache.set_cached_data(table_name, cache_data, **unique_fields)
                        
                        # 更新MySQL
                        if self.db_type == 'mysql':
                            update_sql = f'''
                                UPDATE `gw_hitch_error_mothod` 
                                SET `count` = {ph}, `total_count` = {ph}, `content` = {ph}
                                WHERE id = {ph}
                            '''
                        else:
                            update_sql = f'''
                                UPDATE gw_hitch_error_mothod 
                                SET count = {ph}, total_count = {ph}, content = {ph}, update_time = CURRENT_TIMESTAMP
                                WHERE id = {ph}
                            '''
                        cursor.execute(update_sql, (batch_count, new_total, content, existing_id))
                    else:
                        # MySQL中也不存在，插入新记录
                        if self.db_type == 'mysql':
                            sql = f'''
                                INSERT INTO `gw_hitch_error_mothod` 
                                (`method_name`, `error_code`, `error_message`, `content`, `count`, `total_count`)
                                VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph})
                            '''
                        else:
                            sql = f'''
                                INSERT INTO gw_hitch_error_mothod 
                                (method_name, error_code, error_message, content, count, total_count)
                                VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph})
                            '''
                        
                        cursor.execute(sql, (
                            method_name,
                            error_code,
                            error_message,
                            content,
                            batch_count,
                            batch_count  # total_count 初始值等于 count
                        ))
                        
                        # 获取新插入记录的ID并写入Redis
                        new_id = cursor.lastrowid
                        cache_data = {
                            'id': new_id,
                            'method_name': method_name,
                            'error_code': error_code,
                            'error_message': error_message,
                            'content': content,
                            'count': batch_count,
                            'total_count': batch_count,
                            'create_time': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                            'update_time': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                        }
                        redis_cache.set_cached_data(table_name, cache_data, **unique_fields)
                
                # 记录入库日志
                record_insert_log(LOG_FROM_GW_HITCH, method_name, content, batch_count)
                
                success_count += 1
            except Exception as e:
                error_count += 1
                errors.append({
                    'index': idx,
                    'error': str(e),
                    'data': agg_data
                })
        
        conn.commit()
        conn.close()
        
        return {
            'total': len(log_data_list),
            'transformed': len(transformed_logs),
            'aggregated': len(aggregated_dict),
            'success': success_count,
            'error': error_count,
            'filtered': filtered_count,
            'errors': errors[:10]
        }
    
    def process_cls_response(self, cls_response: Dict, query_transform_config: Dict = None, query_filter_config: Dict = None) -> Dict:
        """
        处理CLS API响应数据
        
        Args:
            cls_response: CLS API响应
            query_transform_config: 查询配置的独立转换规则（优先于表映射规则）
            query_filter_config: 查询配置的入库条件（可选）
        
        Returns:
            处理结果统计
        """
        if 'Response' not in cls_response:
            raise ValueError("无效的CLS响应格式")
        
        response = cls_response['Response']
        
        if 'Error' in response:
            raise ValueError(f"CLS API错误: {response['Error'].get('Message', '未知错误')}")
        
        results = response.get('Results', [])
        if not results:
            return {'total': 0, 'success': 0, 'error': 0, 'filtered': 0, 'errors': []}
        
        # 解析日志数据
        log_data_list = []
        for result in results:
            if result.get('LogJson'):
                try:
                    log_json = json.loads(result['LogJson'])
                    log_data_list.append(log_json)
                except json.JSONDecodeError:
                    pass
        
        return self.process_logs(log_data_list, query_transform_config, query_filter_config)
    
    def get_table_data(self, limit: int = 100, offset: int = 0, 
                       order_by: str = 'id', order_dir: str = 'DESC') -> Dict:
        """
        获取表数据
        
        Args:
            limit: 返回数量限制
            offset: 偏移量
            order_by: 排序字段
            order_dir: 排序方向
        
        Returns:
            数据结果
        """
        if order_dir.upper() not in ('ASC', 'DESC'):
            order_dir = 'DESC'
        
        allowed_columns = ['id', 'method_name', 'error_code', 'error_message', 
                          'content', 'count', 'total_count', 'create_time', 'update_time']
        if order_by not in allowed_columns:
            order_by = 'id'
        
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        # 查询数据
        if self.db_type == 'mysql':
            cursor.execute(f'''
                SELECT * FROM `gw_hitch_error_mothod` 
                ORDER BY `{order_by}` {order_dir} 
                LIMIT {ph} OFFSET {ph}
            ''', (limit, offset))
        else:
            cursor.execute(f'''
                SELECT * FROM gw_hitch_error_mothod 
                ORDER BY {order_by} {order_dir} 
                LIMIT {ph} OFFSET {ph}
            ''', (limit, offset))
        
        rows = cursor.fetchall()
        
        # 获取总数
        if self.db_type == 'mysql':
            cursor.execute('SELECT COUNT(*) as total FROM `gw_hitch_error_mothod`')
        else:
            cursor.execute('SELECT COUNT(*) as total FROM gw_hitch_error_mothod')
        
        total_row = cursor.fetchone()
        total = self._row_to_dict(total_row)['total']
        
        conn.close()
        
        # 处理数据
        data = []
        for row in rows:
            item = self._row_to_dict(row)
            # 处理时间格式
            for key in ['create_time', 'update_time']:
                if item.get(key) and hasattr(item[key], 'isoformat'):
                    item[key] = item[key].isoformat()
            data.append(item)
        
        return {
            'columns': ['id', 'method_name', 'error_code', 'error_message',
                       'content', 'count', 'total_count', 'create_time', 'update_time'],
            'data': data,
            'total': total,
            'limit': limit,
            'offset': offset
        }
    
    def get_error_statistics(self) -> Dict:
        """
        获取错误统计信息
        
        Returns:
            错误统计数据
        """
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        
        # 按error_code和error_message分组统计
        cursor.execute('''
            SELECT error_code, error_message, method_name, SUM(total_count) as count, MAX(update_time) as last_time
            FROM gw_hitch_error_mothod
            GROUP BY error_code, error_message, method_name
            ORDER BY count DESC
            LIMIT 50
        ''')
        
        rows = cursor.fetchall()
        conn.close()
        
        result = []
        for row in rows:
            item = self._row_to_dict(row)
            if item.get('last_time') and hasattr(item['last_time'], 'isoformat'):
                item['last_time'] = item['last_time'].isoformat()
            result.append(item)
        
        return {'statistics': result}
