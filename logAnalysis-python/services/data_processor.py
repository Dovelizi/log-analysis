# -*- coding: utf-8 -*-
"""
数据流转处理服务
负责日志数据的查询、转换和入库操作
支持MySQL和SQLite
"""
import json
import re
from datetime import datetime
from typing import List, Dict, Any, Optional


class DataProcessorService:
    """数据流转处理服务"""
    
    def __init__(self, db_config):
        """
        初始化服务
        
        Args:
            db_config: MySQL配置字典或SQLite数据库路径
        """
        if isinstance(db_config, dict):
            self.db_type = 'mysql'
            self.db_config = db_config
        else:
            self.db_type = 'sqlite'
            self.db_path = db_config
    
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
    
    def process_log_data(self, mapping_id: int, log_data: List[Dict], raw_log_ids: Optional[List[int]] = None, query_transform_config: Optional[Dict] = None, query_filter_config: Optional[Dict] = None) -> Dict:
        """
        处理日志数据并入库
        
        Args:
            mapping_id: 映射配置ID
            log_data: 日志数据列表
            raw_log_ids: 原始日志ID列表（可选）
            query_transform_config: 查询配置的独立转换规则（可选），优先于表映射的规则
                格式: {target_column: {source_field, transform_rule, ...}, ...}
            query_filter_config: 查询配置的入库条件（可选），优先于表映射的过滤条件
                格式: {enabled: bool, logic: 'and'|'or', conditions: [...]}
                注意: 过滤条件基于转换后的字段值进行判断，字段路径应使用目标表的列名
        
        Returns:
            处理结果统计
        """
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        # 获取映射配置
        cursor.execute(f'''
            SELECT m.*, t.topic_id as cls_topic_id
            FROM topic_table_mappings m
            JOIN log_topics t ON m.topic_id = t.id
            WHERE m.id = {ph}
        ''', (mapping_id,))
        
        mapping = cursor.fetchone()
        if not mapping:
            conn.close()
            raise ValueError(f"映射配置不存在: {mapping_id}")
        
        mapping = self._row_to_dict(mapping)
        table_name = mapping['table_name']
        field_config = mapping['field_config']
        if isinstance(field_config, str):
            field_config = json.loads(field_config)
        
        # 获取入库过滤条件配置（查询配置优先，否则使用表映射配置）
        if query_filter_config:
            filter_config = query_filter_config
        else:
            filter_config = mapping.get('filter_config')
            if filter_config and isinstance(filter_config, str):
                filter_config = json.loads(filter_config)
        
        # 获取字段映射
        cursor.execute(f'SELECT * FROM field_mappings WHERE mapping_id = {ph}', (mapping_id,))
        field_mappings = [self._row_to_dict(row) for row in cursor.fetchall()]
        
        # 构建字段映射字典（用target_column作为key，避免source_field重复导致覆盖）
        field_map = {fm['target_column']: fm for fm in field_mappings}
        
        # 如果有查询配置的转换规则，合并到 field_map 中（查询配置优先）
        if query_transform_config:
            for target_col, config in query_transform_config.items():
                if target_col in field_map:
                    # 合并配置，查询配置的非空值覆盖表映射的值
                    for key, value in config.items():
                        if value is not None and value != '':
                            field_map[target_col][key] = value
                else:
                    # 新增字段配置
                    field_map[target_col] = config
        
        # 获取数据库表的实际列（用于过滤不存在的字段）
        actual_columns = set()
        try:
            if self.db_type == 'mysql':
                cursor.execute(f"SHOW COLUMNS FROM `{table_name}`")
                for row in cursor.fetchall():
                    row_dict = self._row_to_dict(row)
                    actual_columns.add(row_dict.get('Field'))
            else:
                cursor.execute(f"PRAGMA table_info({table_name})")
                for row in cursor.fetchall():
                    row_dict = self._row_to_dict(row)
                    actual_columns.add(row_dict.get('name'))
        except Exception as e:
            print(f"获取表 {table_name} 结构失败: {e}")
            actual_columns = None
        
        # 过滤 field_config，只保留数据库中实际存在的字段
        if actual_columns:
            field_config = [f for f in field_config if f.get('name') in actual_columns]
        
        # 导入过滤器
        from services.transform_utils import FilterEvaluator
        
        success_count = 0
        error_count = 0
        filtered_count = 0  # 被过滤掉的数量
        errors = []
        
        for idx, log in enumerate(log_data):
            try:
                # 先转换数据
                row_data = self._transform_log_to_row(log, field_map, field_config)
                
                # 最终过滤：确保只插入数据库中实际存在的列
                if actual_columns:
                    row_data = {k: v for k, v in row_data.items() if k in actual_columns}
                
                # 检查入库条件（基于转换后的值过滤）
                if filter_config and filter_config.get('enabled', False):
                    if not FilterEvaluator.evaluate(row_data, filter_config):
                        filtered_count += 1
                        continue  # 不满足条件，跳过该条数据
                
                # 插入数据
                self._insert_row(cursor, table_name, row_data)
                success_count += 1
                
            except Exception as e:
                error_count += 1
                errors.append({
                    'index': idx,
                    'error': str(e),
                    'data': log
                })
        
        conn.commit()
        
        # 记录采集日志
        now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        cursor.execute(f'''
            INSERT INTO collection_logs 
            (mapping_id, collected_count, success_count, error_count, error_message, started_at, finished_at)
            VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph})
        ''', (
            mapping_id,
            len(log_data),
            success_count,
            error_count,
            json.dumps(errors[:10], ensure_ascii=False) if errors else None,
            now,
            now
        ))
        
        conn.commit()
        conn.close()
        
        return {
            'total': len(log_data),
            'success': success_count,
            'filtered': filtered_count,
            'error': error_count,
            'errors': errors[:10]
        }
    
    def _transform_log_to_row(self, log: Dict, field_map: Dict, field_config: List) -> Dict:
        """
        将日志数据转换为表行数据
        
        使用统一的TransformUtils工具类，确保与测试转换使用同一套逻辑
        """
        from services.transform_utils import TransformUtils
        return TransformUtils.transform_log_to_row(log, field_map, field_config)
    
    def _extract_value(self, data: Dict, field_path: str) -> Any:
        """
        从数据中提取字段值，支持嵌套路径
        
        使用统一的TransformUtils工具类
        """
        from services.transform_utils import TransformUtils
        return TransformUtils.extract_value(data, field_path)
    
    def _apply_transform(self, value: Any, transform_rule: str, full_data: Dict = None) -> Any:
        """
        应用转换规则
        
        使用统一的TransformUtils工具类
        """
        from services.transform_utils import TransformUtils
        return TransformUtils.apply_transform(value, transform_rule, full_data)
    
    def _convert_type(self, value: Any, field_type: str) -> Any:
        """
        类型转换
        
        使用统一的TransformUtils工具类
        """
        from services.transform_utils import TransformUtils
        return TransformUtils.convert_type(value, field_type)
    
    def _insert_row(self, cursor, table_name: str, row_data: Dict):
        """插入数据行"""
        if not row_data:
            return
        
        ph = self._get_placeholder()
        columns = list(row_data.keys())
        placeholders = [ph for _ in columns]
        values = [row_data[col] for col in columns]
        
        if self.db_type == 'mysql':
            col_str = ', '.join([f'`{c}`' for c in columns])
            sql = f"INSERT INTO `{table_name}` ({col_str}) VALUES ({', '.join(placeholders)})"
        else:
            col_str = ', '.join(columns)
            sql = f"INSERT INTO {table_name} ({col_str}) VALUES ({', '.join(placeholders)})"
        
        cursor.execute(sql, values)
    
    def process_cls_response(self, mapping_id: int, cls_response: Dict) -> Dict:
        """处理CLS API响应数据"""
        if 'Response' not in cls_response:
            raise ValueError("无效的CLS响应格式")
        
        response = cls_response['Response']
        
        if 'Error' in response:
            raise ValueError(f"CLS API错误: {response['Error'].get('Message', '未知错误')}")
        
        results = response.get('Results', [])
        if not results:
            return {'total': 0, 'success': 0, 'error': 0, 'errors': []}
        
        log_data = []
        for result in results:
            if result.get('LogJson'):
                try:
                    log_json = json.loads(result['LogJson'])
                    log_data.append(log_json)
                except:
                    log_data.append({'raw': result.get('RawLog', '')})
            else:
                log_data.append({'raw': result.get('RawLog', '')})
        
        return self.process_log_data(mapping_id, log_data)
    
    def batch_process(self, mapping_ids: List[int], cls_caller, time_range: int = 3600) -> Dict:
        """批量处理多个映射的数据采集"""
        results = {}
        ph = self._get_placeholder()
        
        for mapping_id in mapping_ids:
            try:
                conn = self._get_connection()
                cursor = self._get_cursor(conn)
                
                cursor.execute(f'''
                    SELECT m.*, t.topic_id as cls_topic_id, t.credential_id, t.region
                    FROM topic_table_mappings m
                    JOIN log_topics t ON m.topic_id = t.id
                    WHERE m.id = {ph} AND m.auto_collect = 1 AND m.status = 1
                ''', (mapping_id,))
                
                mapping = cursor.fetchone()
                conn.close()
                
                if not mapping:
                    results[mapping_id] = {'status': 'skipped', 'message': '映射不存在或未启用'}
                    continue
                
                mapping = self._row_to_dict(mapping)
                
                import time
                now = int(time.time() * 1000)
                from_time = now - time_range * 1000
                
                cls_response = cls_caller(
                    credential_id=mapping['credential_id'],
                    topic_id=mapping['cls_topic_id'],
                    query='*',
                    from_time=from_time,
                    to_time=now,
                    region=mapping.get('region')
                )
                
                process_result = self.process_cls_response(mapping_id, cls_response)
                results[mapping_id] = {
                    'status': 'success',
                    'result': process_result
                }
                
            except Exception as e:
                results[mapping_id] = {
                    'status': 'error',
                    'message': str(e)
                }
        
        return results
    
    def get_statistics(self, mapping_id: Optional[int] = None) -> Dict:
        """获取数据处理统计信息"""
        conn = self._get_connection()
        cursor = self._get_cursor(conn)
        ph = self._get_placeholder()
        
        if mapping_id:
            cursor.execute(f'''
                SELECT 
                    COUNT(*) as total_collections,
                    SUM(collected_count) as total_collected,
                    SUM(success_count) as total_success,
                    SUM(error_count) as total_errors,
                    MAX(finished_at) as last_collection
                FROM collection_logs
                WHERE mapping_id = {ph}
            ''', (mapping_id,))
        else:
            cursor.execute('''
                SELECT 
                    COUNT(*) as total_collections,
                    SUM(collected_count) as total_collected,
                    SUM(success_count) as total_success,
                    SUM(error_count) as total_errors,
                    MAX(finished_at) as last_collection
                FROM collection_logs
            ''')
        
        row = cursor.fetchone()
        stats = self._row_to_dict(row)
        conn.close()
        
        # 处理NULL值
        for key in ['total_collected', 'total_success', 'total_errors']:
            if stats.get(key) is None:
                stats[key] = 0
        
        return stats
