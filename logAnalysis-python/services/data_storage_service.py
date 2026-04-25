# -*- coding: utf-8 -*-
"""
通用数据入库服务
负责处理日志数据的聚合和入库逻辑，支持以下表：
- control_hitch_error_mothod: 唯一性判断基于 method_name, error_code, error_message
- gw_hitch_error_mothod: 唯一性判断基于 method_name, error_code, error_message
- hitch_supplier_error_sp: 唯一性判断基于 sp_id, method_name, error_code, error_message (当天)
- hitch_supplier_error_total: 唯一性判断基于 sp_id, method_name, error_code, error_message (当天)

入库逻辑：
1. 先对传入数据按唯一性字段进行聚合，count = 重复条数
2. 查询数据库是否存在相同记录
   - 若存在：total_count = 数据库原有值 + 本次传入的count值
   - 若不存在：total_count = 本次传入的count值
"""
from datetime import datetime
from typing import List, Dict, Tuple, Optional, Any
from abc import ABC, abstractmethod


class BaseDataStorageService(ABC):
    """数据入库服务基类"""
    
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
    
    def _get_placeholder(self) -> str:
        """获取SQL占位符"""
        return '%s' if self.db_type == 'mysql' else '?'
    
    def _row_to_dict(self, row) -> Optional[Dict]:
        """将行转换为字典"""
        if row is None:
            return None
        if isinstance(row, dict):
            return row
        return dict(row)
    
    @abstractmethod
    def get_table_name(self) -> str:
        """获取表名"""
        pass
    
    @abstractmethod
    def get_unique_fields(self) -> List[str]:
        """获取唯一性判断字段列表"""
        pass
    
    @abstractmethod
    def get_insert_fields(self) -> List[str]:
        """获取插入字段列表（不含自动生成的字段）"""
        pass
    
    @abstractmethod
    def transform_data(self, raw_data: Dict) -> Dict:
        """
        转换原始数据为入库格式
        
        Args:
            raw_data: 原始数据
        
        Returns:
            转换后的数据
        """
        pass
    
    def use_daily_scope(self) -> bool:
        """是否使用当天范围查询（默认False，子类可覆盖）"""
        return False
    
    def _build_unique_key(self, data: Dict) -> Tuple:
        """
        构建唯一性判断的key
        统一转为字符串，避免类型不一致导致聚合失败
        
        Args:
            data: 转换后的数据
        
        Returns:
            唯一性key元组
        """
        return tuple(str(data.get(field) or '') for field in self.get_unique_fields())
    
    def _aggregate_data(self, data_list: List[Dict]) -> Dict[Tuple, Dict]:
        """
        聚合相同唯一性key的数据
        count = 重复条数
        
        Args:
            data_list: 转换后的数据列表
        
        Returns:
            聚合后的数据字典，key为唯一性元组，value为聚合后的数据
        """
        aggregated = {}
        for data in data_list:
            key = self._build_unique_key(data)
            current_count = data.get('count', 1) or 1
            
            if key in aggregated:
                # 相同key的数据，累加count
                aggregated[key]['count'] += current_count
            else:
                # 新的key，初始化数据
                aggregated[key] = dict(data)
                aggregated[key]['count'] = current_count
        
        return aggregated
    
    def _build_check_sql(self) -> str:
        """
        构建查询是否存在相同记录的SQL
        
        Returns:
            SQL语句
        """
        table_name = self.get_table_name()
        unique_fields = self.get_unique_fields()
        ph = self._get_placeholder()
        
        # 构建WHERE条件，使用 NULL 安全的比较操作符
        if self.db_type == 'mysql':
            # MySQL 使用 <=> 操作符进行 NULL 安全的比较
            conditions = [f"`{field}` <=> {ph}" for field in unique_fields]
        else:
            # SQLite 使用 IS 操作符
            conditions = [f"{field} IS {ph}" for field in unique_fields]
        where_clause = ' AND '.join(conditions)
        
        # 如果使用当天范围，添加时间条件
        if self.use_daily_scope():
            where_clause += f" AND create_time >= {ph} AND create_time <= {ph}"
        
        if self.db_type == 'mysql':
            return f"SELECT id, count, total_count FROM `{table_name}` WHERE {where_clause} LIMIT 1"
        else:
            return f"SELECT id, count, total_count FROM {table_name} WHERE {where_clause} LIMIT 1"
    
    def _build_update_sql(self) -> str:
        """
        构建更新SQL
        
        Returns:
            SQL语句
        """
        table_name = self.get_table_name()
        ph = self._get_placeholder()
        
        # 更新 count, total_count, content 字段
        if self.db_type == 'mysql':
            return f"UPDATE `{table_name}` SET `count` = {ph}, `total_count` = {ph}, `content` = {ph} WHERE id = {ph}"
        else:
            return f"UPDATE {table_name} SET count = {ph}, total_count = {ph}, content = {ph}, update_time = CURRENT_TIMESTAMP WHERE id = {ph}"
    
    def _build_insert_sql(self) -> str:
        """
        构建插入SQL
        
        Returns:
            SQL语句
        """
        table_name = self.get_table_name()
        insert_fields = self.get_insert_fields()
        ph = self._get_placeholder()
        
        # 添加 count 和 total_count 字段
        all_fields = insert_fields + ['count', 'total_count']
        
        if self.db_type == 'mysql':
            fields_str = ', '.join([f'`{f}`' for f in all_fields])
            values_str = ', '.join([ph] * len(all_fields))
            return f"INSERT INTO `{table_name}` ({fields_str}) VALUES ({values_str})"
        else:
            fields_str = ', '.join(all_fields)
            values_str = ', '.join([ph] * len(all_fields))
            return f"INSERT INTO {table_name} ({fields_str}) VALUES ({values_str})"
    
    def _get_check_params(self, data: Dict) -> Tuple:
        """
        获取查询参数
        
        Args:
            data: 聚合后的数据
        
        Returns:
            参数元组
        """
        params = [data.get(field) for field in self.get_unique_fields()]
        
        if self.use_daily_scope():
            today = datetime.now().strftime('%Y-%m-%d')
            params.extend([f'{today} 00:00:00', f'{today} 23:59:59'])
        
        return tuple(params)
    
    def _get_update_params(self, data: Dict, existing_total: int, existing_id: int) -> Tuple:
        """
        获取更新参数
        
        Args:
            data: 聚合后的数据
            existing_total: 数据库中已有的total_count
            existing_id: 记录ID
        
        Returns:
            参数元组
        """
        current_count = data.get('count', 1) or 1
        new_total = (existing_total or 0) + current_count
        
        return (current_count, new_total, data.get('content'), existing_id)
    
    def _get_insert_params(self, data: Dict) -> Tuple:
        """
        获取插入参数
        
        Args:
            data: 聚合后的数据
        
        Returns:
            参数元组
        """
        insert_fields = self.get_insert_fields()
        current_count = data.get('count', 1) or 1
        
        params = [data.get(field) for field in insert_fields]
        # 新记录：count 和 total_count 都等于本次的count值
        params.extend([current_count, current_count])
        
        return tuple(params)
    
    def store_data(self, raw_data_list: List[Dict]) -> Dict:
        """
        存储数据到数据库
        
        入库逻辑：
        1. 先将原始数据转换为入库格式
        2. 按唯一性字段聚合数据，count = 重复条数
        3. 查询数据库是否存在相同记录
           - 若存在：total_count = 数据库原有值 + 本次传入的count值
           - 若不存在：total_count = 本次传入的count值
        
        Args:
            raw_data_list: 原始数据列表
        
        Returns:
            处理结果统计
        """
        if not raw_data_list:
            return {'total': 0, 'success': 0, 'error': 0, 'aggregated': 0, 'errors': []}
        
        # 1. 转换数据
        transformed_list = []
        transform_errors = []
        for idx, raw_data in enumerate(raw_data_list):
            try:
                transformed = self.transform_data(raw_data)
                transformed_list.append(transformed)
            except Exception as e:
                transform_errors.append({
                    'index': idx,
                    'error': f'转换失败: {str(e)}',
                    'data': raw_data
                })
        
        if not transformed_list:
            return {
                'total': len(raw_data_list),
                'success': 0,
                'error': len(transform_errors),
                'aggregated': 0,
                'errors': transform_errors[:10]
            }
        
        # 2. 聚合数据
        aggregated = self._aggregate_data(transformed_list)
        
        # 3. 写入数据库
        conn = self._get_connection()
        # 使用 DictCursor 以便通过字段名访问
        cursor = self._get_cursor(conn)
        
        check_sql = self._build_check_sql()
        update_sql = self._build_update_sql()
        insert_sql = self._build_insert_sql()
        
        success_count = 0
        error_count = 0
        errors = []
        
        for idx, (key, data) in enumerate(aggregated.items()):
            try:
                # 查询是否存在相同记录
                check_params = self._get_check_params(data)
                cursor.execute(check_sql, check_params)
                existing = cursor.fetchone()
                
                if existing:
                    # 存在相同记录，更新
                    existing_row = self._row_to_dict(existing)
                    existing_id = existing_row['id']
                    existing_total = existing_row['total_count'] or 0
                    
                    update_params = self._get_update_params(data, existing_total, existing_id)
                    cursor.execute(update_sql, update_params)
                else:
                    # 不存在相同记录，插入
                    insert_params = self._get_insert_params(data)
                    cursor.execute(insert_sql, insert_params)
                
                success_count += 1
            except Exception as e:
                error_count += 1
                errors.append({
                    'index': idx,
                    'error': str(e),
                    'data': data
                })
        
        conn.commit()
        conn.close()
        
        return {
            'total': len(raw_data_list),
            'transformed': len(transformed_list),
            'aggregated': len(aggregated),
            'success': success_count,
            'error': error_count + len(transform_errors),
            'errors': (transform_errors + errors)[:10]
        }


class ControlHitchErrorStorageService(BaseDataStorageService):
    """
    control_hitch_error_mothod 表入库服务
    唯一性判断：method_name, error_code, error_message
    """
    
    def get_table_name(self) -> str:
        return 'control_hitch_error_mothod'
    
    def get_unique_fields(self) -> List[str]:
        return ['method_name', 'error_code', 'error_message']
    
    def get_insert_fields(self) -> List[str]:
        return ['method_name', 'error_code', 'error_message', 'content']
    
    def transform_data(self, raw_data: Dict) -> Dict:
        """
        转换原始数据
        使用统一的转换工具进行数据转换
        """
        from services.transform_utils import transform_for_table
        
        try:
            if self.db_type == 'mysql':
                db_config = self.db_config
            else:
                db_config = self.db_path
            
            result = transform_for_table(self.get_table_name(), raw_data, db_config)
            
            # 兜底逻辑
            content = raw_data.get('content', '')
            if not result.get('method_name'):
                result['method_name'] = self._extract_method_name(content)
            if not result.get('error_code'):
                result['error_code'] = str(self._extract_error_code(content))
            if not result.get('error_message'):
                result['error_message'] = self._extract_error_message(content)
            if not result.get('content'):
                result['content'] = content[:10240] if content else None
            if not result.get('count'):
                result['count'] = 1
            
            return result
        except ValueError:
            return self._transform_default(raw_data)
    
    def _transform_default(self, raw_data: Dict) -> Dict:
        """默认转换逻辑"""
        import re
        content = raw_data.get('content', '')
        
        return {
            'method_name': self._extract_method_name(content),
            'error_code': str(self._extract_error_code(content)),
            'error_message': self._extract_error_message(content),
            'content': content[:10240] if content else None,
            'count': 1
        }
    
    def _extract_method_name(self, content: str) -> Optional[str]:
        import re
        if not content:
            return None
        match = re.search(r'method:([^,]+)', content)
        return match.group(1).strip() if match else None
    
    def _extract_error_code(self, content: str) -> int:
        import re
        if not content:
            return 500
        match = re.search(r'code=(\d+)', content)
        if match:
            try:
                return int(match.group(1))
            except ValueError:
                return 500
        return 500
    
    def _extract_error_message(self, content: str) -> str:
        import re
        if not content:
            return 'system_error'
        match = re.search(r'desc=([^)]+)', content)
        if match:
            return match.group(1).strip()
        if 'timeout' in content.lower():
            return 'timeout'
        return 'system_error'


class GwHitchErrorStorageService(BaseDataStorageService):
    """
    gw_hitch_error_mothod 表入库服务
    唯一性判断：method_name, error_code, error_message
    """
    
    def get_table_name(self) -> str:
        return 'gw_hitch_error_mothod'
    
    def get_unique_fields(self) -> List[str]:
        return ['method_name', 'error_code', 'error_message']
    
    def get_insert_fields(self) -> List[str]:
        return ['method_name', 'error_code', 'error_message', 'content']
    
    def transform_data(self, raw_data: Dict) -> Dict:
        """
        转换原始数据
        使用统一的转换工具进行数据转换
        """
        from services.transform_utils import transform_for_table
        import json
        
        try:
            if self.db_type == 'mysql':
                db_config = self.db_config
            else:
                db_config = self.db_path
            
            result = transform_for_table(self.get_table_name(), raw_data, db_config)
            
            # 兜底逻辑
            if not result.get('method_name'):
                result['method_name'] = raw_data.get('path', '')
            if result.get('error_code') is None:
                result['error_code'] = self._extract_error_code(raw_data)
            if not result.get('error_message'):
                result['error_message'] = self._extract_error_message(raw_data)
            if not result.get('content'):
                response_body = raw_data.get('response_body', '')
                result['content'] = response_body[:10240] if response_body else None
            if not result.get('count'):
                result['count'] = 1
            
            return result
        except ValueError:
            return self._transform_default(raw_data)
    
    def _transform_default(self, raw_data: Dict) -> Dict:
        """默认转换逻辑"""
        response_body = raw_data.get('response_body', '')
        
        return {
            'method_name': raw_data.get('path', ''),
            'error_code': self._extract_error_code(raw_data),
            'error_message': self._extract_error_message(raw_data),
            'content': response_body[:10240] if response_body else None,
            'count': 1
        }
    
    def _extract_error_code(self, raw_data: Dict) -> Optional[int]:
        import json
        response_body = raw_data.get('response_body', '')
        if not response_body:
            return None
        try:
            json_data = json.loads(response_body) if isinstance(response_body, str) else response_body
            code = json_data.get('resData', {}).get('code')
            if code is None:
                code = json_data.get('errCode')
            return code
        except (json.JSONDecodeError, TypeError, AttributeError):
            return None
    
    def _extract_error_message(self, raw_data: Dict) -> Optional[str]:
        import json
        response_body = raw_data.get('response_body', '')
        if not response_body:
            return None
        try:
            json_data = json.loads(response_body) if isinstance(response_body, str) else response_body
            message = json_data.get('resData', {}).get('message')
            if not message:
                message = json_data.get('errMsg')
            return message
        except (json.JSONDecodeError, TypeError, AttributeError):
            return None


class HitchSupplierErrorSpStorageService(BaseDataStorageService):
    """
    hitch_supplier_error_sp 表入库服务
    唯一性判断：sp_id, method_name, error_code, error_message (当天范围)
    """
    
    def get_table_name(self) -> str:
        return 'hitch_supplier_error_sp'
    
    def get_unique_fields(self) -> List[str]:
        return ['sp_id', 'method_name', 'error_code', 'error_message']
    
    def get_insert_fields(self) -> List[str]:
        return ['sp_id', 'sp_name', 'method_name', 'content', 'error_code', 'error_message']
    
    def use_daily_scope(self) -> bool:
        """使用当天范围查询"""
        return True
    
    def _build_update_sql(self) -> str:
        """
        构建更新SQL（包含sp_name字段）
        """
        table_name = self.get_table_name()
        ph = self._get_placeholder()
        
        if self.db_type == 'mysql':
            return f"UPDATE `{table_name}` SET `count` = {ph}, `total_count` = {ph}, `content` = {ph}, `sp_name` = {ph} WHERE id = {ph}"
        else:
            return f"UPDATE {table_name} SET count = {ph}, total_count = {ph}, content = {ph}, sp_name = {ph}, update_time = CURRENT_TIMESTAMP WHERE id = {ph}"
    
    def _get_update_params(self, data: Dict, existing_total: int, existing_id: int) -> Tuple:
        """获取更新参数（包含sp_name）"""
        current_count = data.get('count', 1) or 1
        new_total = (existing_total or 0) + current_count
        
        return (current_count, new_total, data.get('content'), data.get('sp_name'), existing_id)
    
    def transform_data(self, raw_data: Dict) -> Dict:
        """转换原始数据"""
        from services.transform_utils import transform_for_table
        
        try:
            if self.db_type == 'mysql':
                db_config = self.db_config
            else:
                db_config = self.db_path
            
            result = transform_for_table(self.get_table_name(), raw_data, db_config)
            
            # 兜底逻辑
            if result.get('sp_id') is None:
                result['sp_id'] = raw_data.get('sp_id')
            if not result.get('sp_name'):
                result['sp_name'] = raw_data.get('sp_name')
            if not result.get('method_name'):
                result['method_name'] = raw_data.get('method_name')
            if not result.get('content'):
                content = raw_data.get('content', '')
                result['content'] = content[:10240] if content else None
            if result.get('error_code') is None:
                result['error_code'] = raw_data.get('error_code')
            if not result.get('error_message'):
                error_message = raw_data.get('error_message', '')
                result['error_message'] = error_message[:255] if error_message else None
            if not result.get('count'):
                result['count'] = 1
            
            return result
        except ValueError:
            return self._transform_default(raw_data)
    
    def _transform_default(self, raw_data: Dict) -> Dict:
        """默认转换逻辑"""
        error_message = raw_data.get('error_message', '') or ''
        content = raw_data.get('content', '') or ''
        
        return {
            'sp_id': raw_data.get('sp_id'),
            'sp_name': raw_data.get('sp_name'),
            'method_name': raw_data.get('method_name'),
            'content': content[:10240],
            'error_code': raw_data.get('error_code'),
            'error_message': error_message[:255],
            'count': 1
        }


class HitchSupplierErrorTotalStorageService(BaseDataStorageService):
    """
    hitch_supplier_error_total 表入库服务
    唯一性判断：sp_id, method_name, error_code, error_message (当天范围)
    """
    
    def get_table_name(self) -> str:
        return 'hitch_supplier_error_total'
    
    def get_unique_fields(self) -> List[str]:
        return ['sp_id', 'method_name', 'error_code', 'error_message']
    
    def get_insert_fields(self) -> List[str]:
        return ['sp_id', 'method_name', 'error_code', 'error_message', 'content']
    
    def use_daily_scope(self) -> bool:
        """使用当天范围查询"""
        return True
    
    def transform_data(self, raw_data: Dict) -> Dict:
        """转换原始数据"""
        from services.transform_utils import transform_for_table
        
        try:
            if self.db_type == 'mysql':
                db_config = self.db_config
            else:
                db_config = self.db_path
            
            result = transform_for_table(self.get_table_name(), raw_data, db_config)
            
            # 兜底逻辑
            if result.get('sp_id') is None:
                result['sp_id'] = raw_data.get('sp_id')
            if not result.get('method_name'):
                result['method_name'] = raw_data.get('method_name')
            if result.get('error_code') is None:
                result['error_code'] = raw_data.get('error_code')
            if not result.get('error_message'):
                error_message = raw_data.get('error_message', '')
                result['error_message'] = error_message[:255] if error_message else None
            if not result.get('content'):
                content = raw_data.get('content', '')
                result['content'] = content[:10240] if content else None
            if not result.get('count'):
                result['count'] = 1
            
            return result
        except ValueError:
            return self._transform_default(raw_data)
    
    def _transform_default(self, raw_data: Dict) -> Dict:
        """默认转换逻辑"""
        error_message = raw_data.get('error_message', '') or ''
        content = raw_data.get('content', '') or ''
        
        return {
            'sp_id': raw_data.get('sp_id'),
            'method_name': raw_data.get('method_name'),
            'error_code': raw_data.get('error_code'),
            'error_message': error_message[:255],
            'content': content[:10240],
            'count': 1
        }


def get_storage_service(table_name: str, db_config) -> BaseDataStorageService:
    """
    根据表名获取对应的入库服务
    
    Args:
        table_name: 表名
        db_config: 数据库配置
    
    Returns:
        对应的入库服务实例
    
    Raises:
        ValueError: 不支持的表名
    """
    service_map = {
        'control_hitch_error_mothod': ControlHitchErrorStorageService,
        'gw_hitch_error_mothod': GwHitchErrorStorageService,
        'hitch_supplier_error_sp': HitchSupplierErrorSpStorageService,
        'hitch_supplier_error_total': HitchSupplierErrorTotalStorageService,
    }
    
    service_class = service_map.get(table_name)
    if not service_class:
        raise ValueError(f"不支持的表名: {table_name}")
    
    return service_class(db_config)
