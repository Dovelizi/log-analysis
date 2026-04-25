# -*- coding: utf-8 -*-
"""
Hitch Control Cost Time 日志专用数据处理器
负责将日志数据按照特定规则转换并写入hitch_control_cost_time表
注意：此表不使用聚合逻辑，每条记录独立存储
"""
import json
import re
from datetime import datetime
from typing import List, Dict, Any, Optional

from services.insert_record_service import record_insert_log, LOG_FROM_COST_TIME


class HitchControlCostTimeProcessor:
    """Hitch Control Cost Time 日志数据处理器"""
    
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
        """初始化hitch_control_cost_time表"""
        conn = self._get_connection()
        cursor = conn.cursor()
        
        if self.db_type == 'mysql':
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS hitch_control_cost_time (
                    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                    trace_id VARCHAR(255) NOT NULL COMMENT '链路追踪ID',
                    method_name VARCHAR(255) NOT NULL COMMENT '方法或接口名称',
                    content VARCHAR(10240) NOT NULL COMMENT '响应内容',
                    time_cost INT NOT NULL COMMENT '方法执行耗时（单位：毫秒）',
                    log_time VARCHAR(255) NOT NULL COMMENT '日志记录时间',
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录入库时间（自动填充）',
                    PRIMARY KEY (id),
                    KEY idx_mn_tc (method_name, time_cost) COMMENT '按方法名和耗时联合查询的索引'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='顺风车控制层方法耗时监控日志表'
            ''')
        else:
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS hitch_control_cost_time (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trace_id TEXT NOT NULL,
                    method_name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    time_cost INTEGER NOT NULL,
                    log_time TEXT NOT NULL,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
            {'name': 'trace_id', 'source': 'trace_id', 'transform': None},
            {'name': 'method_name', 'source': 'method_name', 'transform': None},
            {'name': 'content', 'source': 'content', 'transform': 'substr:10240'},
            {'name': 'time_cost', 'source': 'time_cost', 'transform': None},
            {'name': 'log_time', 'source': 'log_time', 'transform': None},
        ]
        
        # 尝试从数据库读取配置
        try:
            conn = self._get_connection()
            cursor = self._get_cursor(conn)
            ph = self._get_placeholder()
            
            cursor.execute(f'''
                SELECT field_config FROM topic_table_mappings 
                WHERE table_name = {ph}
            ''', ('hitch_control_cost_time',))
            
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
    
    def transform_log(self, log_data: Dict, query_transform_config: Dict = None) -> Dict:
        """
        将单条日志数据转换为hitch_control_cost_time表格式
        
        Args:
            log_data: 原始日志数据
            query_transform_config: 查询配置的独立转换规则（优先于表映射规则）
        
        Returns:
            转换后的数据字典
        """
        # 使用统一的转换工具
        from services.transform_utils import transform_for_table
        
        try:
            # 获取数据库配置
            if self.db_type == 'mysql':
                db_config = self.db_config
            else:
                db_config = self.db_path
            
            # 使用统一的转换函数，传入查询配置的转换规则
            result = transform_for_table('hitch_control_cost_time', log_data, db_config, query_transform_config)
            
            # 确保必要字段存在（兜底逻辑）
            # 注意：检查 None 和空字符串
            if not result.get('trace_id'):
                result['trace_id'] = log_data.get('trace_id', '')
            if not result.get('method_name'):
                result['method_name'] = log_data.get('method_name', '')
            if not result.get('content'):
                content = log_data.get('content', '')
                result['content'] = content[:10240] if content else ''
            if result.get('time_cost') is None:
                result['time_cost'] = log_data.get('time_cost') or log_data.get('cost_time') or 0
            if not result.get('log_time'):
                result['log_time'] = log_data.get('log_time', '')
            
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
        content = log_data.get('content', '')
        result = {
            'trace_id': log_data.get('trace_id', ''),
            'method_name': log_data.get('method_name', ''),
            'content': content[:10240] if content else '',
            'time_cost': log_data.get('time_cost') or log_data.get('cost_time') or 0,
            'log_time': log_data.get('log_time', ''),
        }
        return result
    
    def process_logs(self, log_data_list: List[Dict], query_transform_config: Dict = None, query_filter_config: Dict = None) -> Dict:
        """
        处理日志数据列表并写入数据库
        注意：此表不使用聚合逻辑，每条记录独立存储
        
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
        
        # 转换所有日志数据
        transformed_logs = []
        for log in log_data_list:
            try:
                transformed = self.transform_log(log, query_transform_config)
                transformed_logs.append(transformed)
            except Exception as e:
                pass  # 忽略转换失败的日志
        
        # 写入数据库
        conn = self._get_connection()
        cursor = conn.cursor()
        ph = self._get_placeholder()
        
        success_count = 0
        error_count = 0
        filtered_count = 0
        errors = []
        
        for idx, log in enumerate(transformed_logs):
            try:
                # 检查入库条件（基于转换后的值过滤）
                if query_filter_config and query_filter_config.get('enabled', False):
                    if not FilterEvaluator.evaluate(log, query_filter_config):
                        filtered_count += 1
                        continue  # 不满足条件，跳过该条数据
                
                # 直接插入新记录（不聚合）
                if self.db_type == 'mysql':
                    sql = f'''
                        INSERT INTO `hitch_control_cost_time` 
                        (`trace_id`, `method_name`, `content`, `time_cost`, `log_time`)
                        VALUES ({ph}, {ph}, {ph}, {ph}, {ph})
                    '''
                else:
                    sql = f'''
                        INSERT INTO hitch_control_cost_time 
                        (trace_id, method_name, content, time_cost, log_time)
                        VALUES ({ph}, {ph}, {ph}, {ph}, {ph})
                    '''
                
                cursor.execute(sql, (
                    log.get('trace_id', ''),
                    log.get('method_name', ''),
                    log.get('content', ''),
                    log.get('time_cost', 0),
                    log.get('log_time', ''),
                ))
                
                # 记录入库日志
                record_insert_log(LOG_FROM_COST_TIME, log.get('method_name', ''), log.get('content', ''), 1)
                
                success_count += 1
            except Exception as e:
                error_count += 1
                errors.append({
                    'index': idx,
                    'error': str(e),
                    'data': log
                })
        
        conn.commit()
        conn.close()
        
        return {
            'total': len(log_data_list),
            'success': success_count,
            'error': error_count,
            'filtered': filtered_count,
            'errors': errors[:10]  # 只返回前10个错误
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
        
        allowed_columns = ['id', 'trace_id', 'method_name', 'content', 'time_cost', 'log_time', 'create_time']
        if order_by not in allowed_columns:
            order_by = 'id'
        
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        # 查询数据
        if self.db_type == 'mysql':
            cursor.execute(f'''
                SELECT * FROM `hitch_control_cost_time` 
                ORDER BY `{order_by}` {order_dir} 
                LIMIT {ph} OFFSET {ph}
            ''', (limit, offset))
        else:
            cursor.execute(f'''
                SELECT * FROM hitch_control_cost_time 
                ORDER BY {order_by} {order_dir} 
                LIMIT {ph} OFFSET {ph}
            ''', (limit, offset))
        
        rows = cursor.fetchall()
        
        # 获取总数
        if self.db_type == 'mysql':
            cursor.execute('SELECT COUNT(*) as total FROM `hitch_control_cost_time`')
        else:
            cursor.execute('SELECT COUNT(*) as total FROM hitch_control_cost_time')
        
        total_row = cursor.fetchone()
        total = self._row_to_dict(total_row)['total']
        
        conn.close()
        
        # 处理数据
        data = []
        for row in rows:
            item = self._row_to_dict(row)
            # 处理时间格式
            if item.get('create_time') and hasattr(item['create_time'], 'isoformat'):
                item['create_time'] = item['create_time'].isoformat()
            data.append(item)
        
        return {
            'columns': ['id', 'trace_id', 'method_name', 'content', 'time_cost', 'log_time', 'create_time'],
            'data': data,
            'total': total,
            'limit': limit,
            'offset': offset
        }
    
    def get_cost_time_statistics(self) -> Dict:
        """
        获取耗时统计信息
        
        Returns:
            耗时统计数据
        """
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        
        # 按method_name分组统计
        cursor.execute('''
            SELECT 
                method_name, 
                COUNT(*) as count, 
                AVG(time_cost) as avg_cost,
                MAX(time_cost) as max_cost,
                MIN(time_cost) as min_cost,
                MAX(log_time) as last_log_time,
                MAX(create_time) as last_time
            FROM hitch_control_cost_time
            WHERE method_name IS NOT NULL AND method_name != ''
            GROUP BY method_name
            ORDER BY avg_cost DESC
            LIMIT 50
        ''')
        
        rows = cursor.fetchall()
        conn.close()
        
        result = []
        for row in rows:
            item = self._row_to_dict(row)
            if item.get('avg_cost'):
                item['avg_cost'] = round(item['avg_cost'], 2)
            if item.get('last_time') and hasattr(item['last_time'], 'isoformat'):
                item['last_time'] = item['last_time'].isoformat()
            if item.get('last_log_time') and hasattr(item['last_log_time'], 'isoformat'):
                item['last_log_time'] = item['last_log_time'].isoformat()
            result.append(item)
        
        return {'statistics': result}
