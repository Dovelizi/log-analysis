# -*- coding: utf-8 -*-
"""
数据概览API路由
为首页Dashboard提供各数据表的统计和趋势数据
"""
from flask import Blueprint, request, jsonify
from datetime import datetime, timedelta

# 导入日志配置
from config.logging_config import get_api_logger, log_api_call

# 创建蓝图
dashboard_bp = Blueprint('dashboard', __name__, url_prefix='/api/dashboard')

# 获取日志记录器
logger = get_api_logger()

# 最大日期区间（天）
MAX_DATE_RANGE_DAYS = 7


def get_db_config():
    """获取数据库配置"""
    from config.database import MYSQL_CONFIG
    return MYSQL_CONFIG


def get_db_connection():
    """获取数据库连接"""
    from config.database import get_db_connection as get_conn
    return get_conn()


def dict_cursor(conn):
    """获取字典游标"""
    from config.database import dict_cursor as dc
    return dc(conn)


def get_placeholder():
    """获取SQL占位符"""
    from config.database import get_placeholder as gp
    return gp()


def get_db_type():
    """获取数据库类型"""
    from config.database import get_db_type as gdt
    return gdt()


def parse_date_range():
    """
    解析请求中的日期范围参数
    
    Returns:
        (start_date, end_date, start_time, end_time, error_message)
        - start_date, end_date: 'YYYY-MM-DD' 格式
        - start_time, end_time: 'YYYY-MM-DD HH:MM:SS' 格式
        - error_message: 错误信息，无错误时为None
    """
    start_date = request.args.get('start_date')
    end_date = request.args.get('end_date')
    
    # 如果未提供日期参数，默认使用当天
    if not start_date or not end_date:
        today = datetime.now().strftime('%Y-%m-%d')
        start_date = today
        end_date = today
        start_time = f"{start_date} 00:00:00"
        end_time = f"{end_date} 23:59:59"
    else:
        try:
            # 验证日期格式
            start_dt = datetime.strptime(start_date, '%Y-%m-%d')
            end_dt = datetime.strptime(end_date, '%Y-%m-%d')
            
            # 验证日期范围不超过最大限制
            delta = (end_dt - start_dt).days
            if delta < 0:
                return None, None, None, None, '开始日期不能晚于结束日期'
            if delta >= MAX_DATE_RANGE_DAYS:
                return None, None, None, None, f'日期范围不能超过{MAX_DATE_RANGE_DAYS}天'
            
            start_time = f"{start_date} 00:00:00"
            end_time = f"{end_date} 23:59:59"
        except ValueError:
            return None, None, None, None, '日期格式错误，请使用YYYY-MM-DD格式'
    
    return start_date, end_date, start_time, end_time, None


def table_exists(cursor, table_name):
    """检查表是否存在"""
    db_type = get_db_type()
    cursor.execute(f"SHOW TABLES LIKE '{table_name}'")
    return cursor.fetchone() is not None


# ==================== 通用统计接口 ====================

@dashboard_bp.route('/available-dates', methods=['GET'])
@log_api_call(logger)
def get_available_dates():
    """获取最近7天有数据的日期列表"""
    today = datetime.now()
    today_str = today.strftime('%Y-%m-%d')
    
    # 从数据库查询
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    
    # 计算最近7天的日期范围
    start_date = (today - timedelta(days=6)).strftime('%Y-%m-%d')
    end_date = today_str
    start_time = f"{start_date} 00:00:00"
    end_time = f"{end_date} 23:59:59"
    
    tables = [
        'control_hitch_error_mothod',
        'gw_hitch_error_mothod', 
        'hitch_control_cost_time',
        'hitch_supplier_error_sp',
        'hitch_supplier_error_total'
    ]
    
    available_dates = set()
    
    for table in tables:
        try:
            raw_cursor = conn.cursor()
            if not table_exists(raw_cursor, table):
                continue
            
            # 统一使用 create_time 查询有数据的日期
            if db_type == 'mysql':
                cursor.execute(f'''
                    SELECT DISTINCT DATE(create_time) as date_val
                    FROM {table}
                    WHERE create_time BETWEEN %s AND %s
                ''', (start_time, end_time))
            else:
                cursor.execute(f'''
                    SELECT DISTINCT DATE(create_time) as date_val
                    FROM {table}
                    WHERE create_time BETWEEN ? AND ?
                ''', (start_time, end_time))
            
            for row in cursor.fetchall():
                date_val = row['date_val'] if isinstance(row, dict) else row[0]
                if date_val:
                    # 转换为字符串格式
                    if hasattr(date_val, 'strftime'):
                        date_str = date_val.strftime('%Y-%m-%d')
                    else:
                        date_str = str(date_val)
                    available_dates.add(date_str)
        except Exception as e:
            print(f"Error checking dates for {table}: {e}")
            continue
    
    conn.close()
    
    # 排序日期（降序，最新的在前）
    sorted_dates = sorted(list(available_dates), reverse=True)
    
    return jsonify({
        'dates': sorted_dates,
        'today': today_str
    })


@dashboard_bp.route('/overview', methods=['GET'])
@log_api_call(logger)
def get_overview():
    """获取所有数据表的概览统计（支持日期查询）"""
    # 解析日期范围
    start_date, end_date, start_time, end_time, error = parse_date_range()
    if error:
        return jsonify({'error': error}), 400
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    ph = get_placeholder()
    db_type = get_db_type()
    
    tables = [
        'control_hitch_error_mothod',
        'gw_hitch_error_mothod',
        'hitch_control_cost_time',
        'hitch_supplier_error_sp',
        'hitch_supplier_error_total'
    ]
    
    result = {
        'start_date': start_date,
        'end_date': end_date
    }
    
    for table in tables:
        try:
            # 检查表是否存在
            raw_cursor = conn.cursor()
            if not table_exists(raw_cursor, table):
                result[table] = {'exists': False, 'total': 0}
                continue
            
            # 统一使用 create_time 过滤
            if db_type == 'mysql':
                cursor.execute('''
                    SELECT COUNT(*) as cnt FROM {} WHERE create_time BETWEEN %s AND %s
                '''.format(table), (start_time, end_time))
            else:
                cursor.execute('''
                    SELECT COUNT(*) as cnt FROM {} WHERE create_time BETWEEN ? AND ?
                '''.format(table), (start_time, end_time))
            row = cursor.fetchone()
            total = row['cnt'] if isinstance(row, dict) else row[0]
            result[table] = {'exists': True, 'total': total}
        except Exception as e:
            result[table] = {'exists': False, 'total': 0, 'error': str(e)}
    
    conn.close()
    
    return jsonify(result)


# ==================== Control Hitch Error 统计 ====================

@dashboard_bp.route('/control-hitch/statistics', methods=['GET'])
def get_control_hitch_statistics():
    """获取control_hitch_error_mothod表的统计数据（支持日期查询）"""
    # 解析日期范围
    start_date, end_date, start_time, end_time, error = parse_date_range()
    if error:
        return jsonify({'error': error}), 400
    
    page = request.args.get('page', 1, type=int)
    page_size = request.args.get('page_size', 10, type=int)
    
    # 排序参数 - 默认按 更新时间、累计数量、本次数量 依次倒序
    sort_field = request.args.get('sort_field', None)
    sort_order = request.args.get('sort_order', 'desc')
    # 验证排序字段，防止SQL注入
    allowed_sort_fields = ['count', 'total_count', 'update_time', 'create_time', 'method_name', 'error_code']
    if sort_field and sort_field not in allowed_sort_fields:
        sort_field = None
    if sort_order.lower() not in ['asc', 'desc']:
        sort_order = 'desc'
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    ph = get_placeholder()
    db_type = get_db_type()
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'control_hitch_error_mothod'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        result = {
            'exists': True,
            'start_date': start_date,
            'end_date': end_date,
            'total_count': 0,
            'total_error_count': 0,
            'unique_error_code_count': 0,
            'unique_method_count': 0,
            'error_code_distribution': [],
            'method_distribution': [],
            'trend_hourly': [],
            'recent_errors': []
        }
        
        # 构建日期范围条件 - 统一使用 create_time 过滤
        if db_type == 'mysql':
            date_condition = "create_time BETWEEN %s AND %s"
            date_params = (start_time, end_time)
        else:
            date_condition = "create_time BETWEEN ? AND ?"
            date_params = (start_time, end_time)
        
        # 记录总数（日期范围内）
        cursor.execute(f'''
            SELECT COUNT(*) as cnt FROM control_hitch_error_mothod
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['total_count'] = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 累计错误总数（日期范围内）
        cursor.execute(f'''
            SELECT SUM(total_count) as total FROM control_hitch_error_mothod
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        total = row['total'] if isinstance(row, dict) else row[0]
        result['total_error_count'] = total or 0
        
        # 顺风车接口错误码总数（日期范围内） - 统计 error_code 的总数量（不去重）
        cursor.execute(f'''
            SELECT COUNT(error_code) as cnt FROM control_hitch_error_mothod
            WHERE error_code IS NOT NULL AND {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['unique_error_code_count'] = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 方法种类总数（日期范围内）
        cursor.execute(f'''
            SELECT COUNT(DISTINCT method_name) as cnt FROM control_hitch_error_mothod
            WHERE method_name IS NOT NULL AND method_name != '' AND {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['unique_method_count'] = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 错误码分布（日期范围内）
        cursor.execute(f'''
            SELECT error_code, SUM(total_count) as count 
            FROM control_hitch_error_mothod 
            WHERE error_code IS NOT NULL AND {date_condition}
            GROUP BY error_code 
            ORDER BY count DESC 
            LIMIT 10
        ''', date_params)
        result['error_code_distribution'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 方法名分布（日期范围内）
        cursor.execute(f'''
            SELECT method_name, SUM(total_count) as count 
            FROM control_hitch_error_mothod 
            WHERE method_name IS NOT NULL AND method_name != '' AND {date_condition}
            GROUP BY method_name 
            ORDER BY count DESC 
            LIMIT 10
        ''', date_params)
        result['method_distribution'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 趋势统计（按小时，从 hitch_error_log_insert_record 表查询 control_hitch 数据）
        trend_sql = '''
            SELECT 
                DATE_FORMAT(create_time, '%%Y-%%m-%%d %%H:00:00') as time_bucket,
                SUM(count) as count
            FROM hitch_error_log_insert_record
            WHERE log_from = 1 AND create_time BETWEEN %s AND %s
            GROUP BY time_bucket
            ORDER BY time_bucket
        '''
        cursor.execute(trend_sql, (start_time, end_time))
        result['trend_hourly'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 最近错误列表 - 直接从数据库查询
        offset = (page - 1) * page_size
        # 默认排序：更新时间、累计数量、本次数量 依次倒序
        if sort_field:
            order_clause = f"ORDER BY {sort_field} {sort_order.upper()}"
        else:
            order_clause = "ORDER BY update_time DESC, total_count DESC, count DESC"
        
        if db_type == 'mysql':
            cursor.execute(f'''
                SELECT id, method_name, error_code, error_message, count, total_count, update_time
                FROM control_hitch_error_mothod
                WHERE {date_condition}
                {order_clause}
                LIMIT %s OFFSET %s
            ''', date_params + (page_size, offset))
        else:
            cursor.execute(f'''
                SELECT id, method_name, error_code, error_message, count, total_count, update_time
                FROM control_hitch_error_mothod
                WHERE {date_condition}
                {order_clause}
                LIMIT ? OFFSET ?
            ''', date_params + (page_size, offset))
        recent_errors = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            if item.get('update_time') and hasattr(item['update_time'], 'isoformat'):
                item['update_time'] = item['update_time'].isoformat()
            recent_errors.append(item)
        result['recent_errors'] = recent_errors
        
        result['page'] = page
        result['page_size'] = page_size
        result['total_pages'] = (result['total_count'] + page_size - 1) // page_size if result['total_count'] > 0 else 1
        
        conn.close()
        
        return jsonify(result)
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== Control Hitch Error 聚合统计 ====================

@dashboard_bp.route('/control-hitch/aggregation', methods=['GET'])
def get_control_hitch_aggregation():
    """获取control_hitch_error_mothod表的聚合统计 - 按方法名+错误码+错误信息聚合"""
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'control_hitch_error_mothod'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        # 获取总数用于分页
        cursor.execute('SELECT COUNT(*) as cnt FROM control_hitch_error_mothod')
        row = cursor.fetchone()
        total_count = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 支持分页
        page = request.args.get('page', 1, type=int)
        page_size = request.args.get('page_size', 10, type=int)
        offset = (page - 1) * page_size
        
        # 直接返回聚合后的数据（默认排序：更新时间、累计数量、本次数量 依次倒序）
        cursor.execute(f'''
            SELECT 
                id,
                method_name,
                error_code,
                error_message,
                count,
                total_count,
                update_time
            FROM control_hitch_error_mothod
            ORDER BY update_time DESC, total_count DESC, count DESC
            LIMIT {page_size} OFFSET {offset}
        ''')
        
        aggregation = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            if item.get('update_time') and hasattr(item['update_time'], 'isoformat'):
                item['update_time'] = item['update_time'].isoformat()
            aggregation.append(item)
        
        conn.close()
        return jsonify({
            'exists': True,
            'aggregation': aggregation,
            'page': page,
            'page_size': page_size,
            'total_count': total_count,
            'total_pages': (total_count + page_size - 1) // page_size if total_count > 0 else 1
        })
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== GW Hitch Error 统计 ====================

@dashboard_bp.route('/gw-hitch/statistics', methods=['GET'])
def get_gw_hitch_statistics():
    """获取gw_hitch_error_mothod表的统计数据（支持日期查询）"""
    # 解析日期范围
    start_date, end_date, start_time, end_time, error = parse_date_range()
    if error:
        return jsonify({'error': error}), 400
    
    page = request.args.get('page', 1, type=int)
    page_size = request.args.get('page_size', 10, type=int)
    
    # 排序参数 - 默认按 更新时间、累计数量、本次数量 依次倒序
    sort_field = request.args.get('sort_field', None)
    sort_order = request.args.get('sort_order', 'desc')
    # 验证排序字段，防止SQL注入
    allowed_sort_fields = ['count', 'total_count', 'update_time', 'create_time', 'method_name', 'error_code']
    if sort_field and sort_field not in allowed_sort_fields:
        sort_field = None
    if sort_order.lower() not in ['asc', 'desc']:
        sort_order = 'desc'
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    ph = get_placeholder()
    db_type = get_db_type()
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'gw_hitch_error_mothod'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        result = {
            'exists': True,
            'start_date': start_date,
            'end_date': end_date,
            'total_count': 0,
            'total_error_count': 0,
            'unique_method_count': 0,
            'error_code_distribution': [],
            'method_distribution': [],
            'trend_hourly': [],
            'recent_errors': []
        }
        
        # 构建日期范围条件 - 统一使用 create_time 过滤
        if db_type == 'mysql':
            date_condition = "create_time BETWEEN %s AND %s"
            date_params = (start_time, end_time)
        else:
            date_condition = "create_time BETWEEN ? AND ?"
            date_params = (start_time, end_time)
        
        # 记录总数（日期范围内）
        cursor.execute(f'''
            SELECT COUNT(*) as cnt FROM gw_hitch_error_mothod
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['total_count'] = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 累计错误总数（日期范围内）
        cursor.execute(f'''
            SELECT SUM(total_count) as total FROM gw_hitch_error_mothod
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        total = row['total'] if isinstance(row, dict) else row[0]
        result['total_error_count'] = total or 0
        
        # 网关错误数量（日期范围内） - 统计 total_count 的总数
        cursor.execute(f'''
            SELECT SUM(total_count) as cnt FROM gw_hitch_error_mothod
            WHERE total_count IS NOT NULL AND {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['unique_method_count'] = row['cnt'] if isinstance(row, dict) else (row[0] if row[0] is not None else 0)
        
        # 错误码分布（日期范围内）
        cursor.execute(f'''
            SELECT error_code, SUM(total_count) as count 
            FROM gw_hitch_error_mothod 
            WHERE error_code IS NOT NULL AND {date_condition}
            GROUP BY error_code 
            ORDER BY count DESC 
            LIMIT 10
        ''', date_params)
        result['error_code_distribution'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 方法名分布（日期范围内）
        cursor.execute(f'''
            SELECT method_name, SUM(total_count) as count 
            FROM gw_hitch_error_mothod 
            WHERE method_name IS NOT NULL AND method_name != '' AND {date_condition}
            GROUP BY method_name 
            ORDER BY count DESC 
            LIMIT 10
        ''', date_params)
        result['method_distribution'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 趋势统计（按小时，从 hitch_error_log_insert_record 表查询 gw_hitch 数据）
        trend_sql = '''
            SELECT 
                DATE_FORMAT(create_time, '%%Y-%%m-%%d %%H:00:00') as time_bucket,
                SUM(count) as count
            FROM hitch_error_log_insert_record
            WHERE log_from = 2 AND create_time BETWEEN %s AND %s
            GROUP BY time_bucket
            ORDER BY time_bucket
        '''
        cursor.execute(trend_sql, (start_time, end_time))
        result['trend_hourly'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 最近错误列表 - 直接从数据库查询
        offset = (page - 1) * page_size
        # 默认排序：更新时间、累计数量、本次数量 依次倒序
        if sort_field:
            order_clause = f"ORDER BY {sort_field} {sort_order.upper()}"
        else:
            order_clause = "ORDER BY update_time DESC, total_count DESC, count DESC"
        
        if db_type == 'mysql':
            cursor.execute(f'''
                SELECT id, method_name, error_code, error_message, count, total_count, update_time
                FROM gw_hitch_error_mothod
                WHERE {date_condition}
                {order_clause}
                LIMIT %s OFFSET %s
            ''', date_params + (page_size, offset))
        else:
            cursor.execute(f'''
                SELECT id, method_name, error_code, error_message, count, total_count, update_time
                FROM gw_hitch_error_mothod
                WHERE {date_condition}
                {order_clause}
                LIMIT ? OFFSET ?
            ''', date_params + (page_size, offset))
        recent_errors = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            if item.get('update_time') and hasattr(item['update_time'], 'isoformat'):
                item['update_time'] = item['update_time'].isoformat()
            recent_errors.append(item)
        result['recent_errors'] = recent_errors
        
        result['page'] = page
        result['page_size'] = page_size
        result['total_pages'] = (result['total_count'] + page_size - 1) // page_size if result['total_count'] > 0 else 1
        
        conn.close()
        
        return jsonify(result)
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== GW Hitch Error 聚合统计 ====================

@dashboard_bp.route('/gw-hitch/aggregation', methods=['GET'])
def get_gw_hitch_aggregation():
    """获取gw_hitch_error_mothod表的聚合统计 - 按接口+错误码+错误信息聚合"""
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'gw_hitch_error_mothod'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        # 获取总数用于分页
        cursor.execute('SELECT COUNT(*) as cnt FROM gw_hitch_error_mothod')
        row = cursor.fetchone()
        total_count = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 支持分页
        page = request.args.get('page', 1, type=int)
        page_size = request.args.get('page_size', 10, type=int)
        offset = (page - 1) * page_size
        
        # 直接返回聚合后的数据（默认排序：更新时间、累计数量、本次数量 依次倒序）
        cursor.execute(f'''
            SELECT 
                id,
                method_name,
                error_code,
                error_message,
                count,
                total_count,
                update_time
            FROM gw_hitch_error_mothod
            ORDER BY update_time DESC, total_count DESC, count DESC
            LIMIT {page_size} OFFSET {offset}
        ''')
        
        aggregation = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            if item.get('update_time') and hasattr(item['update_time'], 'isoformat'):
                item['update_time'] = item['update_time'].isoformat()
            aggregation.append(item)
        
        conn.close()
        return jsonify({
            'exists': True,
            'aggregation': aggregation,
            'page': page,
            'page_size': page_size,
            'total_count': total_count,
            'total_pages': (total_count + page_size - 1) // page_size if total_count > 0 else 1
        })
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== Hitch Control Cost Time 统计 ====================

@dashboard_bp.route('/hitch-control-cost-time/statistics', methods=['GET'])
def get_hitch_control_cost_time_statistics():
    """获取hitch_control_cost_time表的统计数据 - 顺风车高耗时接口分析（支持日期查询）"""
    # 解析日期范围
    start_date, end_date, start_time, end_time, error = parse_date_range()
    if error:
        return jsonify({'error': error}), 400
    
    page = request.args.get('page', 1, type=int)
    page_size = request.args.get('page_size', 10, type=int)
    high_cost_page = request.args.get('high_cost_page', 1, type=int)
    high_cost_page_size = request.args.get('high_cost_page_size', 10, type=int)
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    ph = get_placeholder()
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'hitch_control_cost_time'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        result = {
            'exists': True,
            'start_date': start_date,
            'end_date': end_date,
            'columns': ['id', 'trace_id', 'method_name', 'content', 'time_cost', 'log_time', 'create_time'],
            'column_labels': {
                'id': '主键ID',
                'trace_id': '链路追踪ID',
                'method_name': '方法或接口名称',
                'content': '响应内容',
                'time_cost': '方法执行耗时（毫秒）',
                'log_time': '日志记录时间',
                'create_time': '记录入库时间'
            },
            'total_count': 0,
            'unique_method_count': 0,
            'avg_cost_time': 0,
            'max_cost_time': 0,
            'min_cost_time': 0,
            'high_cost_list': [],
            'method_avg_cost': [],
            'cost_time_distribution': [],
            'recent_records': []
        }
        
        # 构建日期范围条件
        if db_type == 'mysql':
            date_condition = "create_time BETWEEN %s AND %s"
            date_params = (start_time, end_time)
        else:
            date_condition = "create_time BETWEEN ? AND ?"
            date_params = (start_time, end_time)
        
        # 总数（日期范围内）
        cursor.execute(f'''
            SELECT COUNT(*) as cnt FROM hitch_control_cost_time
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['total_count'] = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 顺风车高耗时接口种类总数（日期范围内）
        cursor.execute(f'''
            SELECT COUNT(DISTINCT method_name) as cnt FROM hitch_control_cost_time
            WHERE method_name IS NOT NULL AND method_name != '' AND {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['unique_method_count'] = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 平均、最大、最小耗时（日期范围内）
        cursor.execute(f'''
            SELECT 
                AVG(time_cost) as avg_cost,
                MAX(time_cost) as max_cost,
                MIN(time_cost) as min_cost
            FROM hitch_control_cost_time 
            WHERE time_cost IS NOT NULL AND time_cost > 0 AND {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        if row:
            row = dict(row) if not isinstance(row, dict) else row
            result['avg_cost_time'] = round(row.get('avg_cost') or 0, 2)
            result['max_cost_time'] = row.get('max_cost') or 0
            result['min_cost_time'] = row.get('min_cost') or 0
        
        # 高耗时记录列表 - 支持分页（日期范围内）
        high_cost_offset = (high_cost_page - 1) * high_cost_page_size
        
        if db_type == 'mysql':
            cursor.execute(f'''
                SELECT * FROM hitch_control_cost_time 
                WHERE time_cost IS NOT NULL AND {date_condition}
                ORDER BY time_cost DESC
                LIMIT %s OFFSET %s
            ''', date_params + (high_cost_page_size, high_cost_offset))
        else:
            cursor.execute(f'''
                SELECT * FROM hitch_control_cost_time 
                WHERE time_cost IS NOT NULL AND {date_condition}
                ORDER BY time_cost DESC
                LIMIT ? OFFSET ?
            ''', date_params + (high_cost_page_size, high_cost_offset))
        high_cost_list = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            for k, v in item.items():
                if hasattr(v, 'isoformat'):
                    item[k] = v.isoformat()
            high_cost_list.append(item)
        result['high_cost_list'] = high_cost_list
        result['high_cost_page'] = high_cost_page
        result['high_cost_page_size'] = high_cost_page_size
        result['high_cost_total_pages'] = (result['total_count'] + high_cost_page_size - 1) // high_cost_page_size if result['total_count'] > 0 else 1
        
        # 按方法名统计平均耗时（日期范围内）
        cursor.execute(f'''
            SELECT 
                method_name,
                COUNT(*) as count,
                AVG(time_cost) as avg_cost,
                MAX(time_cost) as max_cost
            FROM hitch_control_cost_time 
            WHERE method_name IS NOT NULL AND method_name != '' AND {date_condition}
            GROUP BY method_name
            ORDER BY avg_cost DESC
            LIMIT 15
        ''', date_params)
        method_stats = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            if item.get('avg_cost'):
                item['avg_cost'] = round(item['avg_cost'], 2)
            method_stats.append(item)
        result['method_avg_cost'] = method_stats
        
        # 耗时分布（按1秒间隔统计，日期范围内）
        # 先获取最大耗时来确定区间数量
        cursor.execute(f'''
            SELECT MAX(time_cost) as max_cost FROM hitch_control_cost_time
            WHERE time_cost IS NOT NULL AND {date_condition}
        ''', date_params)
        max_cost_row = cursor.fetchone()
        max_cost = (dict(max_cost_row) if not isinstance(max_cost_row, dict) else max_cost_row).get('max_cost') or 0
        max_seconds = int(max_cost / 1000) + 1  # 转换为秒并向上取整
        
        # 动态生成区间统计SQL
        if max_seconds > 0:
            case_parts = []
            order_parts = []
            for i in range(max_seconds):
                lower = i * 1000
                upper = (i + 1) * 1000
                label = f'{i}-{i+1}s'
                case_parts.append(f"WHEN time_cost >= {lower} AND time_cost < {upper} THEN '{label}'")
                order_parts.append(f"WHEN time_range = '{label}' THEN {i}")
            
            case_sql = ' '.join(case_parts)
            order_sql = ' '.join(order_parts)
            
            # 使用子查询避免 GROUP BY 别名问题，兼容 MySQL 和 SQLite
            cursor.execute(f'''
                SELECT time_range, COUNT(*) as count FROM (
                    SELECT 
                        CASE {case_sql} END as time_range
                    FROM hitch_control_cost_time
                    WHERE time_cost IS NOT NULL AND {date_condition}
                ) t
                WHERE time_range IS NOT NULL
                GROUP BY time_range
                ORDER BY CASE {order_sql} ELSE 999 END
            ''', date_params)
            result['cost_time_distribution'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        else:
            result['cost_time_distribution'] = []
        
        # 最近记录 - 直接从数据库查询
        offset = (page - 1) * page_size
        
        if db_type == 'mysql':
            cursor.execute(f'''
                SELECT * FROM hitch_control_cost_time 
                WHERE {date_condition}
                ORDER BY create_time DESC
                LIMIT %s OFFSET %s
            ''', date_params + (page_size, offset))
        else:
            cursor.execute(f'''
                SELECT * FROM hitch_control_cost_time 
                WHERE {date_condition}
                ORDER BY create_time DESC
                LIMIT ? OFFSET ?
            ''', date_params + (page_size, offset))
        recent_records = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            for k, v in item.items():
                if hasattr(v, 'isoformat'):
                    item[k] = v.isoformat()
            recent_records.append(item)
        result['recent_records'] = recent_records
        
        result['page'] = page
        result['page_size'] = page_size
        result['total_pages'] = (result['total_count'] + page_size - 1) // page_size if result['total_count'] > 0 else 1
        
        conn.close()
        
        return jsonify(result)
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== Hitch Supplier Error SP 统计 ====================

@dashboard_bp.route('/hitch-supplier-error-sp/statistics', methods=['GET'])
def get_hitch_supplier_error_sp_statistics():
    """获取hitch_supplier_error_sp表的统计数据（支持日期查询）"""
    # 解析日期范围
    start_date, end_date, start_time, end_time, error = parse_date_range()
    if error:
        return jsonify({'error': error}), 400
    
    page = request.args.get('page', 1, type=int)
    page_size = request.args.get('page_size', 10, type=int)
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'hitch_supplier_error_sp'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        result = {
            'exists': True,
            'start_date': start_date,
            'end_date': end_date,
            'total_count': 0,
            'total_error_count': 0,
            'supplier_distribution': [],
            'error_code_distribution': [],
            'recent_records': [],
            'trend_hourly': []
        }
        
        # 构建日期范围条件 - 统一使用 create_time 过滤
        if db_type == 'mysql':
            date_condition = "create_time BETWEEN %s AND %s"
            date_params = (start_time, end_time)
        else:
            date_condition = "create_time BETWEEN ? AND ?"
            date_params = (start_time, end_time)
        
        # 记录总数（日期范围内）
        cursor.execute(f'''
            SELECT COUNT(*) as cnt FROM hitch_supplier_error_sp
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['total_count'] = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 累计错误总数（日期范围内）
        cursor.execute(f'''
            SELECT SUM(total_count) as total FROM hitch_supplier_error_sp 
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        total = row['total'] if isinstance(row, dict) else row[0]
        result['total_error_count'] = total or 0
        
        # 供应商分布（日期范围内）
        cursor.execute(f'''
            SELECT sp_id, sp_name, SUM(total_count) as count 
            FROM hitch_supplier_error_sp 
            WHERE sp_id IS NOT NULL AND {date_condition}
            GROUP BY sp_id, sp_name
            ORDER BY count DESC
            LIMIT 10
        ''', date_params)
        result['supplier_distribution'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 错误码分布（日期范围内）
        cursor.execute(f'''
            SELECT error_code, SUM(total_count) as count 
            FROM hitch_supplier_error_sp 
            WHERE error_code IS NOT NULL AND {date_condition}
            GROUP BY error_code
            ORDER BY count DESC
            LIMIT 10
        ''', date_params)
        result['error_code_distribution'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 小时趋势（按供应商分组，从 hitch_error_log_insert_record 表查询 supplier_sp 数据）
        trend_sql = '''
            SELECT 
                sp_id,
                DATE_FORMAT(create_time, '%%Y-%%m-%%d %%H:00:00') as time_bucket,
                SUM(count) as count
            FROM hitch_error_log_insert_record
            WHERE log_from = 3 AND create_time BETWEEN %s AND %s
            GROUP BY sp_id, time_bucket
            ORDER BY time_bucket
        '''
        cursor.execute(trend_sql, date_params)
        result['trend_hourly'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 最近记录 - 直接从数据库查询
        offset = (page - 1) * page_size
        
        if db_type == 'mysql':
            cursor.execute(f'''
                SELECT * FROM hitch_supplier_error_sp 
                WHERE {date_condition}
                ORDER BY update_time DESC, total_count DESC, count DESC
                LIMIT %s OFFSET %s
            ''', date_params + (page_size, offset))
        else:
            cursor.execute(f'''
                SELECT * FROM hitch_supplier_error_sp 
                WHERE {date_condition}
                ORDER BY update_time DESC, total_count DESC, count DESC
                LIMIT ? OFFSET ?
            ''', date_params + (page_size, offset))
        recent_records = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            for k, v in item.items():
                if hasattr(v, 'isoformat'):
                    item[k] = v.isoformat()
            recent_records.append(item)
        result['recent_records'] = recent_records
        
        result['page'] = page
        result['page_size'] = page_size
        result['total_pages'] = (result['total_count'] + page_size - 1) // page_size if result['total_count'] > 0 else 1
        
        conn.close()
        
        return jsonify(result)
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== Hitch Supplier Error SP 聚合统计 ====================

@dashboard_bp.route('/hitch-supplier-error-sp/aggregation', methods=['GET'])
def get_hitch_supplier_error_sp_aggregation():
    """获取hitch_supplier_error_sp表的聚合统计 - 按sp_id分组（支持日期过滤）"""
    # 解析日期范围
    start_date, end_date, start_time, end_time, error = parse_date_range()
    if error:
        return jsonify({'error': error}), 400
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'hitch_supplier_error_sp'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        # 构建日期条件 - 统一使用 create_time 过滤
        if db_type == 'mysql':
            date_condition = "create_time BETWEEN %s AND %s"
        else:
            date_condition = "create_time BETWEEN ? AND ?"
        date_params = (start_time, end_time)
        
        # 获取日期范围内的 sp_id 列表
        cursor.execute(f'''
            SELECT DISTINCT sp_id, sp_name FROM hitch_supplier_error_sp 
            WHERE sp_id IS NOT NULL AND {date_condition}
            ORDER BY sp_id
        ''', date_params)
        sp_rows = cursor.fetchall()
        sp_list = [{'sp_id': row['sp_id'] if isinstance(row, dict) else row[0], 
                    'sp_name': row['sp_name'] if isinstance(row, dict) else row[1]} for row in sp_rows]
        
        # 获取请求的 sp_id，如果没有则使用第一个
        sp_id = request.args.get('sp_id', sp_list[0]['sp_id'] if sp_list else None, type=int)
        page = request.args.get('page', 1, type=int)
        page_size = request.args.get('page_size', 10, type=int)
        offset = (page - 1) * page_size
        ph = get_placeholder()
        
        # 排序参数 - 默认按 更新时间、累计数量、本次数量 依次倒序
        sort_field = request.args.get('sort_field', None)
        sort_order = request.args.get('sort_order', 'desc')
        # 验证排序字段，防止SQL注入
        allowed_sort_fields = ['count', 'total_count', 'update_time', 'method_name', 'error_code']
        if sort_field and sort_field not in allowed_sort_fields:
            sort_field = None
        if sort_order.lower() not in ['asc', 'desc']:
            sort_order = 'desc'
        
        # 构建排序子句
        if sort_field:
            order_clause = f"ORDER BY {sort_field} {sort_order.upper()}"
        else:
            order_clause = "ORDER BY update_time DESC, total_count DESC, count DESC"
        
        # 获取当前 sp_id 在日期范围内的记录总数
        cursor.execute(f'''
            SELECT COUNT(*) as cnt FROM hitch_supplier_error_sp
            WHERE sp_id = {ph} AND {date_condition}
        ''', (sp_id,) + date_params)
        row = cursor.fetchone()
        total_count = row['cnt'] if isinstance(row, dict) else row[0]
        total_pages = (total_count + page_size - 1) // page_size if total_count > 0 else 1
        
        # 返回日期范围内的聚合数据
        if db_type == 'mysql':
            cursor.execute(f'''
                SELECT 
                    id,
                    method_name,
                    error_code,
                    error_message,
                    count,
                    total_count,
                    update_time
                FROM hitch_supplier_error_sp
                WHERE sp_id = %s AND {date_condition}
                {order_clause}
                LIMIT %s OFFSET %s
            ''', (sp_id,) + date_params + (page_size, offset))
        else:
            cursor.execute(f'''
                SELECT 
                    id,
                    method_name,
                    error_code,
                    error_message,
                    count,
                    total_count,
                    update_time
                FROM hitch_supplier_error_sp
                WHERE sp_id = ? AND {date_condition}
                {order_clause}
                LIMIT ? OFFSET ?
            ''', (sp_id,) + date_params + (page_size, offset))
        
        aggregation = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            if item.get('update_time') and hasattr(item['update_time'], 'isoformat'):
                item['update_time'] = item['update_time'].isoformat()
            aggregation.append(item)
        
        conn.close()
        return jsonify({
            'exists': True,
            'start_date': start_date,
            'end_date': end_date,
            'sp_list': sp_list,
            'current_sp': sp_id,
            'aggregation': aggregation,
            'page': page,
            'page_size': page_size,
            'total_count': total_count,
            'total_pages': total_pages
        })
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== Hitch Supplier Error Total 统计 ====================

@dashboard_bp.route('/hitch-supplier-error-total/statistics', methods=['GET'])
def get_hitch_supplier_error_total_statistics():
    """获取hitch_supplier_error_total表的统计数据（支持日期查询）"""
    # 解析日期范围
    start_date, end_date, start_time, end_time, error = parse_date_range()
    if error:
        return jsonify({'error': error}), 400
    
    page = request.args.get('page', 1, type=int)
    page_size = request.args.get('page_size', 10, type=int)
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'hitch_supplier_error_total'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        result = {
            'exists': True,
            'start_date': start_date,
            'end_date': end_date,
            'total_count': 0,
            'total_error_count': 0,
            'supplier_error_summary': [],
            'error_code_distribution': [],
            'recent_records': [],
            'daily_trend': []
        }
        
        # 构建日期范围条件 - 统一使用 create_time 过滤
        if db_type == 'mysql':
            date_condition = "create_time BETWEEN %s AND %s"
            date_params = (start_time, end_time)
        else:
            date_condition = "create_time BETWEEN ? AND ?"
            date_params = (start_time, end_time)
        
        # 记录总数（日期范围内）
        cursor.execute(f'''
            SELECT COUNT(*) as cnt FROM hitch_supplier_error_total
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        result['total_count'] = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 累计错误总数（日期范围内）
        cursor.execute(f'''
            SELECT SUM(total_count) as total FROM hitch_supplier_error_total 
            WHERE {date_condition}
        ''', date_params)
        row = cursor.fetchone()
        total = row['total'] if isinstance(row, dict) else row[0]
        result['total_error_count'] = total or 0
        
        # 服务商错误汇总（日期范围内）
        cursor.execute(f'''
            SELECT sp_id, SUM(total_count) as total_errors
            FROM hitch_supplier_error_total 
            WHERE sp_id IS NOT NULL AND {date_condition}
            GROUP BY sp_id
            ORDER BY total_errors DESC
            LIMIT 10
        ''', date_params)
        result['supplier_error_summary'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 错误码分布（日期范围内）
        cursor.execute(f'''
            SELECT error_code, SUM(total_count) as count 
            FROM hitch_supplier_error_total 
            WHERE error_code IS NOT NULL AND {date_condition}
            GROUP BY error_code
            ORDER BY count DESC
            LIMIT 10
        ''', date_params)
        result['error_code_distribution'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 小时趋势（按供应商分组，从 hitch_error_log_insert_record 表查询 supplier_total 数据）
        cursor.execute('''
            SELECT 
                sp_id,
                DATE_FORMAT(create_time, '%%Y-%%m-%%d %%H:00:00') as time_bucket,
                SUM(count) as count
            FROM hitch_error_log_insert_record
            WHERE log_from = 4 AND create_time BETWEEN %s AND %s
            GROUP BY sp_id, time_bucket
            ORDER BY time_bucket
        ''', (start_time, end_time))
        result['hourly_trend'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 保留 daily_trend 用于兼容（使用 create_time）
        if db_type == 'mysql':
            cursor.execute('''
                SELECT 
                    DATE(create_time) as date,
                    SUM(count) as count
                FROM hitch_supplier_error_total
                WHERE create_time BETWEEN %s AND %s
                GROUP BY DATE(create_time)
                ORDER BY date
            ''', (start_time, end_time))
        else:
            cursor.execute('''
                SELECT 
                    DATE(create_time) as date,
                    SUM(count) as count
                FROM hitch_supplier_error_total
                WHERE create_time BETWEEN ? AND ?
                GROUP BY DATE(create_time)
                ORDER BY date
            ''', (start_time, end_time))
        result['daily_trend'] = [dict(r) if not isinstance(r, dict) else r for r in cursor.fetchall()]
        
        # 最近记录 - 直接从数据库查询
        offset = (page - 1) * page_size
        
        if db_type == 'mysql':
            cursor.execute(f'''
                SELECT * FROM hitch_supplier_error_total 
                WHERE {date_condition}
                ORDER BY update_time DESC, total_count DESC, count DESC
                LIMIT %s OFFSET %s
            ''', date_params + (page_size, offset))
        else:
            cursor.execute(f'''
                SELECT * FROM hitch_supplier_error_total 
                WHERE {date_condition}
                ORDER BY update_time DESC, total_count DESC, count DESC
                LIMIT ? OFFSET ?
            ''', date_params + (page_size, offset))
        recent_records = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            for k, v in item.items():
                if hasattr(v, 'isoformat'):
                    item[k] = v.isoformat()
            recent_records.append(item)
        result['recent_records'] = recent_records
        
        result['page'] = page
        result['page_size'] = page_size
        result['total_pages'] = (result['total_count'] + page_size - 1) // page_size if result['total_count'] > 0 else 1
        
        conn.close()
        
        return jsonify(result)
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== Hitch Supplier Error Total 聚合统计 ====================

@dashboard_bp.route('/hitch-supplier-error-total/aggregation', methods=['GET'])
def get_hitch_supplier_error_total_aggregation():
    """获取hitch_supplier_error_total表的聚合统计 - 按sp_id分组（支持日期过滤）"""
    # 解析日期范围
    start_date, end_date, start_time, end_time, error = parse_date_range()
    if error:
        return jsonify({'error': error}), 400
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, 'hitch_supplier_error_total'):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        # 构建日期条件 - 统一使用 create_time 过滤
        if db_type == 'mysql':
            date_condition = "create_time BETWEEN %s AND %s"
        else:
            date_condition = "create_time BETWEEN ? AND ?"
        date_params = (start_time, end_time)
        
        # 获取日期范围内的 sp_id 列表
        cursor.execute(f'''
            SELECT DISTINCT sp_id FROM hitch_supplier_error_total 
            WHERE sp_id IS NOT NULL AND {date_condition}
            ORDER BY sp_id
        ''', date_params)
        sp_list = [row['sp_id'] if isinstance(row, dict) else row[0] for row in cursor.fetchall()]
        
        # 获取请求的 sp_id，如果没有则使用第一个
        sp_id = request.args.get('sp_id', sp_list[0] if sp_list else None, type=int)
        page = request.args.get('page', 1, type=int)
        page_size = request.args.get('page_size', 10, type=int)
        offset = (page - 1) * page_size
        ph = get_placeholder()
        
        # 排序参数 - 默认按 更新时间、累计数量、本次数量 依次倒序
        sort_field = request.args.get('sort_field', None)
        sort_order = request.args.get('sort_order', 'desc')
        # 验证排序字段，防止SQL注入
        allowed_sort_fields = ['count', 'total_count', 'update_time', 'method_name', 'error_code']
        if sort_field and sort_field not in allowed_sort_fields:
            sort_field = None
        if sort_order.lower() not in ['asc', 'desc']:
            sort_order = 'desc'
        
        # 构建排序子句
        if sort_field:
            order_clause = f"ORDER BY {sort_field} {sort_order.upper()}"
        else:
            order_clause = "ORDER BY update_time DESC, total_count DESC, count DESC"
        
        # 获取当前 sp_id 在日期范围内的记录总数
        cursor.execute(f'''
            SELECT COUNT(*) as cnt FROM hitch_supplier_error_total
            WHERE sp_id = {ph} AND {date_condition}
        ''', (sp_id,) + date_params)
        row = cursor.fetchone()
        total_count = row['cnt'] if isinstance(row, dict) else row[0]
        total_pages = (total_count + page_size - 1) // page_size if total_count > 0 else 1
        
        # 返回日期范围内的聚合数据
        if db_type == 'mysql':
            cursor.execute(f'''
                SELECT 
                    id,
                    method_name,
                    error_code,
                    error_message,
                    count,
                    total_count,
                    update_time
                FROM hitch_supplier_error_total
                WHERE sp_id = %s AND {date_condition}
                {order_clause}
                LIMIT %s OFFSET %s
            ''', (sp_id,) + date_params + (page_size, offset))
        else:
            cursor.execute(f'''
                SELECT 
                    id,
                    method_name,
                    error_code,
                    error_message,
                    count,
                    total_count,
                    update_time
                FROM hitch_supplier_error_total
                WHERE sp_id = ? AND {date_condition}
                {order_clause}
                LIMIT ? OFFSET ?
            ''', (sp_id,) + date_params + (page_size, offset))
        
        aggregation = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            if item.get('update_time') and hasattr(item['update_time'], 'isoformat'):
                item['update_time'] = item['update_time'].isoformat()
            aggregation.append(item)
        
        conn.close()
        return jsonify({
            'exists': True,
            'start_date': start_date,
            'end_date': end_date,
            'sp_list': sp_list,
            'current_sp': sp_id,
            'aggregation': aggregation,
            'page': page,
            'page_size': page_size,
            'total_count': total_count,
            'total_pages': total_pages
        })
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== 通用数据表查询 ====================

@dashboard_bp.route('/table/<table_name>/data', methods=['GET'])
def get_table_data(table_name):
    """获取指定表的数据"""
    # 白名单验证表名
    allowed_tables = [
        'control_hitch_error_mothod',
        'gw_hitch_error_mothod',
        'hitch_control_cost_time',
        'hitch_supplier_error_sp',
        'hitch_supplier_error_total'
    ]
    
    if table_name not in allowed_tables:
        return jsonify({'error': '不允许访问该表'}), 403
    
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    
    try:
        raw_cursor = conn.cursor()
        if not table_exists(raw_cursor, table_name):
            return jsonify({'error': '表不存在', 'exists': False}), 404
        
        limit = request.args.get('limit', 100, type=int)
        offset = request.args.get('offset', 0, type=int)
        
        # 限制最大返回数量
        if limit > 500:
            limit = 500
        
        # 获取总数
        cursor.execute(f'SELECT COUNT(*) as cnt FROM {table_name}')
        row = cursor.fetchone()
        total = row['cnt'] if isinstance(row, dict) else row[0]
        
        # 获取数据
        cursor.execute(f'SELECT * FROM {table_name} ORDER BY id DESC LIMIT {limit} OFFSET {offset}')
        records = []
        for r in cursor.fetchall():
            item = dict(r) if not isinstance(r, dict) else r
            for k, v in item.items():
                if hasattr(v, 'isoformat'):
                    item[k] = v.isoformat()
            records.append(item)
        
        conn.close()
        return jsonify({
            'total': total,
            'limit': limit,
            'offset': offset,
            'records': records
        })
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500
