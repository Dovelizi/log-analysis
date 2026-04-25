# -*- coding: utf-8 -*-
"""
统一的数据转换工具类
确保测试转换和入库转换使用同一套逻辑

所有日志处理器和转换测试接口都应该使用这个类进行数据转换，
避免不同地方的转换逻辑不一致导致的问题。
"""
import json
import re
from datetime import datetime
from typing import Any, Dict, List, Optional


class TransformUtils:
    """统一的数据转换工具类"""
    
    @staticmethod
    def safe_json_loads(json_str: str) -> Any:
        """
        安全地解析 JSON 字符串，支持转义格式
        
        Args:
            json_str: JSON 字符串，可能是普通格式或转义格式（如 {\\"key\\":\\"value\\"}）
        
        Returns:
            解析后的对象，解析失败返回 None
        """
        if not json_str or not isinstance(json_str, str):
            return None
        
        # 1. 尝试直接解析
        try:
            return json.loads(json_str)
        except json.JSONDecodeError:
            pass
        
        # 2. 尝试手动替换转义字符后解析（保持中文编码不变）
        try:
            # 只替换转义的引号和反斜杠，不影响中文
            unescaped = json_str.replace('\\"', '"').replace('\\\\', '\\')
            return json.loads(unescaped)
        except:
            pass
        
        # 3. 如果字符串被包裹在引号中，先去掉外层引号再解析
        try:
            if json_str.startswith('"') and json_str.endswith('"'):
                inner = json_str[1:-1].replace('\\"', '"').replace('\\\\', '\\')
                return json.loads(inner)
        except:
            pass
        
        return None
    
    @staticmethod
    def extract_value(data: Dict, field_path: str) -> Any:
        """
        从数据中提取字段值，支持嵌套路径
        
        Args:
            data: 源数据字典
            field_path: 字段路径，如 'response_body.resData.code'
        
        Returns:
            提取到的值
        """
        if not field_path:
            return None
        
        parts = field_path.split('.')
        value = data
        
        for part in parts:
            if isinstance(value, dict):
                value = value.get(part)
            elif isinstance(value, str):
                # 使用 safe_json_loads 支持转义 JSON
                parsed = TransformUtils.safe_json_loads(value)
                if isinstance(parsed, dict):
                    value = parsed.get(part)
                else:
                    value = None
            else:
                value = None
            
            if value is None:
                break
        
        return value
    
    @staticmethod
    def apply_transform(value: Any, transform_rule: str, full_data: Dict = None) -> Any:
        """
        应用转换规则
        
        支持的转换规则：
        - regex:pattern:group - 正则表达式提取
        - json_path:path - JSON路径提取
        - split:delimiter:index - 字符串分割
        - replace:old:new - 字符串替换
        - trim - 去除首尾空格
        - lower - 转小写
        - upper - 转大写
        - substr:length 或 substr:start:end - 字符串截取
        - fallback:field - 备选字段
        - default_if_empty:value - 空值默认值
        - default_if_timeout:value - 超时检测默认值
        - default_code:value - 默认错误码
        - datetime_parse: - 日期时间解析（去除毫秒）
        
        Args:
            value: 原始值
            transform_rule: 转换规则字符串，多个规则用 | 分隔
            full_data: 完整的日志数据（用于fallback等操作）
        
        Returns:
            转换后的值
        """
        if not transform_rule:
            return value
        
        # 处理多个转换规则（用|分隔，但要避免分割正则表达式内部的|）
        # 策略：只在 | 后面紧跟规则关键字时才分割
        rule_keywords = ('regex', 'regex_fallback', 'split', 'replace', 'trim', 'lower', 'upper', 
                        'substr', 'substring', 'json_path', 'fallback', 'default_if_empty',
                        'default_if_timeout', 'default_code', 'datetime_parse')
        
        transforms = []
        current_rule = []
        for part in transform_rule.split('|'):
            part_stripped = part.strip()
            # 检查这部分是否以规则关键字开头
            is_new_rule = any(part_stripped.startswith(kw + ':') or part_stripped == kw for kw in rule_keywords)
            
            if is_new_rule and current_rule:
                # 遇到新规则，保存之前的规则
                transforms.append('|'.join(current_rule))
                current_rule = [part]
            else:
                # 继续当前规则（可能是正则中的|）
                current_rule.append(part)
        
        if current_rule:
            transforms.append('|'.join(current_rule))
        
        result = value
        
        for rule in transforms:
            rule = rule.strip()
            if not rule:
                continue
            
            parts = rule.split(':')
            rule_type = parts[0]
            
            try:
                if rule_type == 'regex' and len(parts) >= 2:
                    if result is None:
                        continue
                    value_str = str(result)
                    # 对转义字符串进行预处理，将 \" 替换为 "，使正则能正常匹配
                    value_str_unescaped = value_str.replace('\\"', '"').replace('\\\\', '\\')
                    # 正则表达式可能包含冒号，最后一个部分如果是数字则为捕获组索引
                    # 格式: regex:pattern:group 或 regex:pattern
                    last_part = parts[-1]
                    if last_part.isdigit():
                        # 最后是数字，作为捕获组索引
                        group = int(last_part)
                        pattern = ':'.join(parts[1:-1])
                    else:
                        # 最后不是数字，整个作为正则表达式
                        group = 0
                        pattern = ':'.join(parts[1:])
                    # 先尝试匹配转义处理后的字符串，失败则尝试原始字符串
                    match = re.search(pattern, value_str_unescaped)
                    if not match:
                        match = re.search(pattern, value_str)
                    if match:
                        result = match.group(group) if group <= len(match.groups()) else match.group(0)
                    else:
                        result = None
                
                elif rule_type == 'regex_fallback':
                    # 支持多个正则模式的回退匹配
                    # 新格式: regex_fallback:pattern1||pattern2||...:group （用 || 分隔多个pattern）
                    # 旧格式: regex_fallback:pattern1:pattern2:...:group （兼容简单pattern）
                    # 依次尝试每个pattern，直到匹配成功
                    if result is None:
                        continue
                    value_str = str(result)
                    # 对转义字符串进行预处理，将 \" 替换为 "，使正则能正常匹配
                    value_str_unescaped = value_str.replace('\\"', '"').replace('\\\\', '\\')
                    
                    # 获取 regex_fallback: 后面的全部内容
                    full_pattern_str = ':'.join(parts[1:])
                    
                    # 检查最后是否有 :数字 作为捕获组索引
                    group = 1  # 默认取第1个捕获组
                    if re.search(r':(\d+)$', full_pattern_str):
                        match_group = re.search(r':(\d+)$', full_pattern_str)
                        group = int(match_group.group(1))
                        full_pattern_str = full_pattern_str[:match_group.start()]
                    
                    # 用 || 分隔多个 pattern
                    if '||' in full_pattern_str:
                        patterns = full_pattern_str.split('||')
                    else:
                        # 兼容旧格式：如果没有 ||，尝试用 : 分隔（仅适用于简单pattern）
                        patterns = [full_pattern_str]
                    
                    matched = False
                    for pattern in patterns:
                        pattern = pattern.strip()
                        if not pattern:
                            continue
                        # 先尝试匹配转义处理后的字符串，失败则尝试原始字符串
                        match = re.search(pattern, value_str_unescaped)
                        if not match:
                            match = re.search(pattern, value_str)
                        if match:
                            result = match.group(group) if group <= len(match.groups()) else match.group(0)
                            matched = True
                            break
                    if not matched:
                        result = None
                
                elif rule_type == 'split' and len(parts) >= 3:
                    if result is None:
                        continue
                    value_str = str(result)
                    delimiter = parts[1]
                    index = int(parts[2])
                    split_parts = value_str.split(delimiter)
                    if 0 <= index < len(split_parts):
                        result = split_parts[index]
                    else:
                        result = None
                
                elif rule_type == 'replace' and len(parts) >= 3:
                    if result is None:
                        continue
                    value_str = str(result)
                    old = parts[1]
                    new = parts[2]
                    result = value_str.replace(old, new)
                
                elif rule_type == 'trim':
                    if result is not None:
                        result = str(result).strip()
                
                elif rule_type == 'lower':
                    if result is not None:
                        result = str(result).lower()
                
                elif rule_type == 'upper':
                    if result is not None:
                        result = str(result).upper()
                
                elif rule_type in ('substr', 'substring') and len(parts) >= 2:
                    if result is None:
                        continue
                    value_str = str(result)
                    # 截取字符串: substr:start:end 或 substr:length
                    if len(parts) == 2:
                        # substr:length - 从开头截取指定长度
                        length = int(parts[1])
                        result = value_str[:length]
                    else:
                        # substr:start:end - 截取指定范围
                        start = int(parts[1])
                        end = int(parts[2]) if parts[2] else None
                        result = value_str[start:end]
                
                elif rule_type == 'json_path' and len(parts) >= 2:
                    if result is None:
                        continue
                    path = parts[1]
                    try:
                        if isinstance(result, str):
                            # 使用 safe_json_loads 支持转义 JSON
                            data = TransformUtils.safe_json_loads(result)
                        else:
                            data = result
                        if data:
                            result = TransformUtils.extract_value(data, path)
                        else:
                            result = None
                    except:
                        result = None
                
                elif rule_type == 'fallback' and len(parts) >= 2:
                    # 如果当前值为空，从备选字段取值
                    if result is None or result == '':
                        fallback_field = parts[1]
                        if full_data:
                            # 优先尝试从 response_body 的 JSON 中获取
                            response_body = full_data.get('response_body')
                            if response_body and isinstance(response_body, str):
                                json_data = TransformUtils.safe_json_loads(response_body)
                                if json_data:
                                    result = TransformUtils.extract_value(json_data, fallback_field)
                            # 如果还是空，从 full_data 顶层获取
                            if result is None or result == '':
                                result = TransformUtils.extract_value(full_data, fallback_field)
                
                elif rule_type == 'default_if_empty' and len(parts) >= 2:
                    # 如果当前值为空，使用默认值
                    if result is None or result == '':
                        result = ':'.join(parts[1:])  # 支持默认值中包含冒号
                
                elif rule_type == 'default_if_timeout' and len(parts) >= 1:
                    # 如果当前值为空，检查full_data中是否包含timeout
                    if result is None or result == '':
                        default_val = ':'.join(parts[1:]) if len(parts) > 1 else 'system_error'
                        if full_data:
                            content_str = json.dumps(full_data, ensure_ascii=False).lower()
                            if 'timeout' in content_str:
                                result = 'timeout'
                            else:
                                result = default_val
                        else:
                            result = default_val
                
                elif rule_type == 'default_code' and len(parts) >= 2:
                    # 如果当前值为空，返回默认错误码
                    if result is None or result == '':
                        try:
                            result = int(parts[1])
                        except:
                            result = 500
                
                elif rule_type == 'datetime_parse':
                    # 日期时间处理：去除毫秒部分
                    if result is not None and isinstance(result, str):
                        # 处理 "2025-12-30 15:01:53.378" 格式（点分隔毫秒）
                        if '.' in result:
                            result = result.split('.')[0]
                        # 处理 "2025-12-30 15:01:53 378" 格式（空格分隔毫秒）
                        match = re.match(r'^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+\d+$', result)
                        if match:
                            result = match.group(1)
            
            except Exception:
                pass
        
        return result
    
    @staticmethod
    def convert_type(value: Any, field_type: str) -> Any:
        """
        类型转换
        
        Args:
            value: 原始值
            field_type: 目标类型 (INTEGER, REAL, TIMESTAMP, JSON, TEXT等)
        
        Returns:
            转换后的值
        """
        if value is None:
            return None
        
        try:
            field_type_upper = field_type.upper()
            if field_type_upper in ('INTEGER', 'INT', 'BIGINT', 'SMALLINT', 'TINYINT'):
                return int(float(value)) if value != '' else None
            elif field_type_upper in ('REAL', 'FLOAT', 'DOUBLE', 'DECIMAL'):
                return float(value) if value != '' else None
            elif field_type == 'TIMESTAMP':
                if isinstance(value, (int, float)):
                    if value > 1e12:
                        value = value / 1000
                    return datetime.fromtimestamp(value).strftime('%Y-%m-%d %H:%M:%S')
                return str(value)
            elif field_type == 'JSON':
                if isinstance(value, (dict, list)):
                    return json.dumps(value, ensure_ascii=False)
                return str(value)
            else:
                return str(value) if value is not None else None
        except Exception:
            return str(value) if value is not None else None
    
    @staticmethod
    def transform_log_to_row(log: Dict, field_map: Dict, field_config: List) -> Dict:
        """
        将日志数据转换为表行数据
        
        这是核心转换方法，测试转换和入库转换都应该使用此方法
        
        转换规则优先级：
        1. 严格使用 field_mappings 表中的 transform_rule（字段转换规则配置）
        2. 如果 field_mappings 中没有配置，才使用 field_config 中的 transform
        
        Args:
            log: 原始日志数据
            field_map: 字段映射配置字典 {target_column: mapping_config}
                       来自 field_mappings 表，应该用目标字段名作为key
            field_config: 字段配置列表，来自 topic_table_mappings.field_config
        
        Returns:
            转换后的行数据字典
        """
        row_data = {}
        
        for field in field_config:
            field_name = field.get('name')
            source_field = field.get('source', field_name)
            
            # 获取字段映射配置（用目标字段名作为key）
            fm = field_map.get(field_name, {})
            
            # 获取源数据值
            # 优先使用 field_mappings 表中的 source_field
            actual_source = fm.get('source_field') or source_field
            value = TransformUtils.extract_value(log, actual_source) if actual_source else None
            
            # 严格按照 field_mappings 表中的 transform_rule 进行转换
            # 这是"字段转换规则"配置处的转换逻辑
            if fm.get('transform_rule'):
                value = TransformUtils.apply_transform(value, fm['transform_rule'], log)
            # 如果 field_mappings 中没有配置 transform_rule，才使用 field_config 中的 transform
            elif field.get('transform'):
                value = TransformUtils.apply_transform(value, field['transform'], log)
            
            # 类型转换
            field_type = field.get('type', 'TEXT')
            value = TransformUtils.convert_type(value, field_type)
            
            # 处理空值处理器 empty_handler（优先使用查询配置中的，其次使用字段配置中的）
            empty_handler = fm.get('empty_handler') or field.get('empty_handler')
            if (value is None or value == '') and empty_handler:
                if empty_handler == 'default_if_timeout':
                    # 超时检测：若日志含timeout则返回timeout，否则返回system_error
                    content_str = json.dumps(log, ensure_ascii=False).lower()
                    value = 'timeout' if 'timeout' in content_str else 'system_error'
                elif empty_handler == 'default_code':
                    # 默认错误码
                    value = 500
                elif empty_handler == 'use_default':
                    # 使用默认值（优先使用查询配置中的，其次使用字段配置中的）
                    value = fm.get('default_value') or field.get('default') or None
            
            # 处理默认值（如果没有empty_handler）
            if (value is None or value == '') and not empty_handler:
                if fm.get('default_value'):
                    value = fm['default_value']
                    # 默认值也需要类型转换
                    value = TransformUtils.convert_type(value, field_type)
                elif field.get('default') is not None and field.get('default') != '':
                    default_val = field['default']
                    # 跳过数据库特殊默认值，让数据库自动处理
                    if default_val not in ('CURRENT_TIMESTAMP', 'NOW()', 'NULL'):
                        value = default_val
                        # 默认值也需要类型转换
                        value = TransformUtils.convert_type(value, field_type)
            
            # 跳过自增主键字段（id字段，source为空或None，且查询配置中也没有配置source_field）
            # 这些字段由数据库自动生成，不需要从日志中提取
            if field_name == 'id' and (not field.get('source') or field.get('source') == '') and (not fm.get('source_field') or fm.get('source_field') == ''):
                continue
            
            # 跳过source为空且使用数据库默认值的字段（如自动时间戳）
            # 但如果查询配置中有配置source_field或transform_rule，则不跳过
            has_query_config = fm.get('source_field') or fm.get('transform_rule') or fm.get('default_value')
            if not has_query_config and (not field.get('source') or field.get('source') == '') and field.get('default') in ('CURRENT_TIMESTAMP', 'NOW()', None, ''):
                continue
            
            # 跳过默认值为数据库函数的字段（如 CURRENT_TIMESTAMP），让数据库自动处理
            if fm.get('default_value') in ('CURRENT_TIMESTAMP', 'NOW()') and value is None:
                continue
            
            # 如果值是数据库函数字符串，跳过该字段让数据库处理
            if value in ('CURRENT_TIMESTAMP', 'NOW()'):
                continue
            
            # 检查必填
            if fm.get('is_required') and value is None:
                raise ValueError(f"必填字段 '{field_name}' 值为空")
            
            row_data[field_name] = value
        
        return row_data


def transform_for_table(table_name: str, log_data: Dict, db_config, query_transform_config: Dict = None) -> Dict:
    """
    根据表名执行数据转换的便捷函数
    
    这是一个统一的入口函数，用于测试转换和入库转换
    确保两者使用完全相同的转换逻辑
    
    Args:
        table_name: 目标表名
        log_data: 原始日志数据
        db_config: 数据库配置
        query_transform_config: 查询配置的独立转换规则（可选），优先于表映射的规则
            格式: {target_column: {source_field, transform_rule, ...}, ...}
    
    Returns:
        转换后的数据字典
    """
    import os
    
    # 获取数据库连接
    if isinstance(db_config, dict):
        import pymysql
        import pymysql.cursors
        conn = pymysql.connect(**db_config)
        cursor = conn.cursor(pymysql.cursors.DictCursor)
        ph = '%s'
        db_type = 'mysql'
    else:
        import sqlite3
        conn = sqlite3.connect(db_config)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        ph = '?'
        db_type = 'sqlite'
    
    # 获取目标表的实际字段列表及其信息
    actual_columns = {}  # {column_name: {type, default, ...}}
    try:
        if db_type == 'mysql':
            cursor.execute(f"SHOW COLUMNS FROM `{table_name}`")
            for row in cursor.fetchall():
                row_dict = dict(row) if not isinstance(row, dict) else row
                col_name = row_dict.get('Field')
                actual_columns[col_name] = {
                    'type': row_dict.get('Type', 'TEXT'),
                    'default': row_dict.get('Default'),
                    'nullable': row_dict.get('Null') == 'YES',
                    'key': row_dict.get('Key'),
                    'extra': row_dict.get('Extra', '')
                }
        else:
            cursor.execute(f"PRAGMA table_info({table_name})")
            for row in cursor.fetchall():
                row_dict = dict(row) if not isinstance(row, dict) else row
                col_name = row_dict.get('name')
                actual_columns[col_name] = {
                    'type': row_dict.get('type', 'TEXT'),
                    'default': row_dict.get('dflt_value'),
                    'nullable': row_dict.get('notnull') == 0,
                    'pk': row_dict.get('pk') == 1
                }
    except Exception as e:
        # 如果获取表结构失败，不过滤字段
        print(f"获取表 {table_name} 结构失败: {e}")
        actual_columns = None
    
    # 查找映射配置
    cursor.execute(f'SELECT id, field_config FROM topic_table_mappings WHERE table_name = {ph}', (table_name,))
    mapping_row = cursor.fetchone()
    
    if not mapping_row:
        conn.close()
        raise ValueError(f"未找到表 {table_name} 的映射配置")
    
    mapping_row = dict(mapping_row) if not isinstance(mapping_row, dict) else mapping_row
    mapping_id = mapping_row['id']
    field_config = mapping_row['field_config']
    if isinstance(field_config, str):
        field_config = json.loads(field_config)
    
    # 获取 field_config 中已有的字段名
    config_field_names = {f.get('name') for f in field_config}
    
    # 如果获取到了实际表结构，同步字段配置
    if actual_columns:
        # 1. 过滤掉数据库中不存在的字段
        field_config = [f for f in field_config if f.get('name') in actual_columns]
        
        # 2. 补充数据库中存在但 field_config 中缺失的字段
        for col_name, col_info in actual_columns.items():
            if col_name not in config_field_names:
                # 根据数据库字段信息生成默认配置
                field_type = 'TEXT'
                db_type_upper = col_info.get('type', '').upper()
                if 'INT' in db_type_upper:
                    field_type = 'INTEGER'
                elif 'FLOAT' in db_type_upper or 'DOUBLE' in db_type_upper or 'REAL' in db_type_upper or 'DECIMAL' in db_type_upper:
                    field_type = 'REAL'
                elif 'TIMESTAMP' in db_type_upper or 'DATETIME' in db_type_upper:
                    field_type = 'TIMESTAMP'
                elif 'JSON' in db_type_upper:
                    field_type = 'JSON'
                
                # 判断是否是自增主键
                is_auto_increment = (
                    col_info.get('extra', '').lower() == 'auto_increment' or
                    col_info.get('pk', False)
                )
                
                new_field = {
                    'name': col_name,
                    'type': field_type,
                    'source': '',  # 新字段默认没有来源，需要用户配置
                    'default': col_info.get('default') or ''
                }
                
                # 自增主键不需要来源
                if is_auto_increment:
                    new_field['source'] = ''
                    new_field['default'] = ''
                
                field_config.append(new_field)
    
    # 获取字段映射配置（用target_column作为key，避免source_field重复导致覆盖）
    cursor.execute(f'SELECT * FROM field_mappings WHERE mapping_id = {ph}', (mapping_id,))
    field_mappings = [dict(row) if not isinstance(row, dict) else row for row in cursor.fetchall()]
    field_map = {fm['target_column']: fm for fm in field_mappings}
    
    conn.close()
    
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
    
    # 使用统一的转换方法
    result = TransformUtils.transform_log_to_row(log_data, field_map, field_config)
    
    # 最终过滤：确保只返回实际存在于数据库表中的字段
    if actual_columns:
        result = {k: v for k, v in result.items() if k in actual_columns}
    
    return result


class FilterEvaluator:
    """
    数据入库过滤条件评估器
    
    支持的条件类型：
    - equals: 等于
    - not_equals: 不等于
    - contains: 包含
    - not_contains: 不包含
    - starts_with: 以...开头
    - ends_with: 以...结尾
    - gt: 大于
    - gte: 大于等于
    - lt: 小于
    - lte: 小于等于
    - in: 在列表中
    - not_in: 不在列表中
    - regex: 正则匹配
    - is_empty: 为空
    - is_not_empty: 不为空
    
    filter_config 格式:
    {
        "enabled": true,
        "logic": "and",  // "and" 或 "or"
        "conditions": [
            {
                "field": "字段路径",
                "operator": "操作符",
                "value": "比较值"
            }
        ]
    }
    """
    
    @staticmethod
    def evaluate(log_data: Dict, filter_config: Dict) -> bool:
        """
        评估日志数据是否满足过滤条件
        
        Args:
            log_data: 日志数据
            filter_config: 过滤条件配置
        
        Returns:
            True 表示满足条件，应该入库
            False 表示不满足条件，应该跳过
        """
        if not filter_config:
            return True
        
        if not filter_config.get('enabled', False):
            return True
        
        conditions = filter_config.get('conditions', [])
        if not conditions:
            return True
        
        logic = filter_config.get('logic', 'and').lower()
        
        results = []
        for condition in conditions:
            result = FilterEvaluator._evaluate_condition(log_data, condition)
            results.append(result)
        
        if logic == 'and':
            return all(results)
        else:  # or
            return any(results)
    
    @staticmethod
    def _evaluate_condition(log_data: Dict, condition: Dict) -> bool:
        """评估单个条件"""
        field = condition.get('field', '')
        operator = condition.get('operator', 'equals')
        compare_value = condition.get('value', '')
        
        # 提取字段值
        actual_value = TransformUtils.extract_value(log_data, field)
        
        # 转换为字符串进行比较（除了数值比较操作符）
        if actual_value is None:
            actual_str = ''
        elif isinstance(actual_value, (dict, list)):
            actual_str = json.dumps(actual_value, ensure_ascii=False)
        else:
            actual_str = str(actual_value)
        
        try:
            if operator == 'equals':
                return actual_str == str(compare_value)
            
            elif operator == 'not_equals':
                return actual_str != str(compare_value)
            
            elif operator == 'contains':
                return str(compare_value) in actual_str
            
            elif operator == 'not_contains':
                return str(compare_value) not in actual_str
            
            elif operator == 'starts_with':
                return actual_str.startswith(str(compare_value))
            
            elif operator == 'ends_with':
                return actual_str.endswith(str(compare_value))
            
            elif operator == 'gt':
                return FilterEvaluator._compare_numeric(actual_value, compare_value, lambda a, b: a > b)
            
            elif operator == 'gte':
                return FilterEvaluator._compare_numeric(actual_value, compare_value, lambda a, b: a >= b)
            
            elif operator == 'lt':
                return FilterEvaluator._compare_numeric(actual_value, compare_value, lambda a, b: a < b)
            
            elif operator == 'lte':
                return FilterEvaluator._compare_numeric(actual_value, compare_value, lambda a, b: a <= b)
            
            elif operator == 'in':
                # compare_value 应该是逗号分隔的值列表
                values = [v.strip() for v in str(compare_value).split(',')]
                return actual_str in values
            
            elif operator == 'not_in':
                values = [v.strip() for v in str(compare_value).split(',')]
                return actual_str not in values
            
            elif operator == 'regex':
                try:
                    return bool(re.search(str(compare_value), actual_str))
                except:
                    return False
            
            elif operator == 'is_empty':
                return actual_value is None or actual_str == '' or actual_str.strip() == ''
            
            elif operator == 'is_not_empty':
                return actual_value is not None and actual_str != '' and actual_str.strip() != ''
            
            else:
                # 未知操作符，默认返回 True
                return True
                
        except Exception:
            # 评估出错时默认返回 True（不过滤）
            return True
    
    @staticmethod
    def _compare_numeric(actual, compare, comparator) -> bool:
        """数值比较"""
        try:
            actual_num = float(actual) if actual is not None else 0
            compare_num = float(compare)
            return comparator(actual_num, compare_num)
        except (ValueError, TypeError):
            # 无法转换为数字时，尝试字符串比较
            try:
                return comparator(str(actual), str(compare))
            except:
                return False
