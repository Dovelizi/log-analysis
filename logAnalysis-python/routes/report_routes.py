# -*- coding: utf-8 -*-
"""
报表汇总API路由
提供报表数据聚合、新增错误对比、导出和推送功能
"""
import os
import io
import json
import base64
import hashlib
import requests
from datetime import datetime, timedelta
from flask import Blueprint, request, jsonify

from config.database import (
    get_db_connection, get_db_type, dict_cursor, get_placeholder
)

# 导入日志配置
from config.logging_config import get_api_logger, log_api_call

report_bp = Blueprint('report', __name__, url_prefix='/api/report')

# 获取日志记录器
logger = get_api_logger()

PH = get_placeholder()


def table_exists(cursor, table_name):
    """检查表是否存在"""
    cursor.execute(f"SHOW TABLES LIKE '{table_name}'")
    return cursor.fetchone() is not None


def get_date_range(start_date, end_date):
    """获取日期范围的开始和结束时间"""
    return f"{start_date} 00:00:00", f"{end_date} 23:59:59"


# ==================== 报表汇总数据接口 ====================

@report_bp.route('/summary', methods=['GET'])
def get_report_summary():
    """
    获取报表汇总数据，复用dashboard的API数据
    包含：
    - 累计错误数
    - 错误趋势（近24小时/按天）
    - 错误码分布 TOP10
    - 网关错误方法报错分布 TOP10
    - 顺风车高耗时接口 TOP15
    - 服务商错误当日趋势（按供应商）
    """
    from flask import current_app
    
    # 获取日期参数，默认今天
    date_str = request.args.get('date', datetime.now().strftime('%Y-%m-%d'))
    
    result = {
        'date': date_str,
        'generated_at': datetime.now().isoformat()
    }
    
    try:
        # 复用 dashboard 的 API 接口获取数据
        from routes.dashboard_routes import (
            get_control_hitch_statistics,
            get_gw_hitch_statistics,
            get_hitch_control_cost_time_statistics,
            get_hitch_supplier_error_sp_statistics
        )
        
        # 使用 test_request_context 模拟请求
        with current_app.test_request_context(f'/api/dashboard/control-hitch/statistics?start_date={date_str}&end_date={date_str}'):
            control_res = get_control_hitch_statistics()
            control_data = control_res.get_json()
        
        with current_app.test_request_context(f'/api/dashboard/gw-hitch/statistics?start_date={date_str}&end_date={date_str}'):
            gw_res = get_gw_hitch_statistics()
            gw_data = gw_res.get_json()
        
        with current_app.test_request_context(f'/api/dashboard/hitch-control-cost-time/statistics?start_date={date_str}&end_date={date_str}'):
            cost_time_res = get_hitch_control_cost_time_statistics()
            cost_time_data = cost_time_res.get_json()
        
        with current_app.test_request_context(f'/api/dashboard/hitch-supplier-error-sp/statistics?start_date={date_str}&end_date={date_str}'):
            supplier_res = get_hitch_supplier_error_sp_statistics()
            supplier_data = supplier_res.get_json()
        
        # 组装报表数据
        result['control_total_errors'] = control_data.get('total_error_count', 0)
        result['control_error_trend'] = control_data.get('trend_hourly', [])
        result['control_error_code_top10'] = control_data.get('error_code_distribution', [])
        
        result['gw_method_top10'] = gw_data.get('method_distribution', [])
        result['gw_total_errors'] = gw_data.get('total_error_count', 0)
        
        result['high_cost_top15'] = cost_time_data.get('method_avg_cost', [])
        result['avg_cost_time'] = cost_time_data.get('avg_cost_time', 0)
        result['max_cost_time'] = cost_time_data.get('max_cost_time', 0)
        
        result['supplier_trend'] = supplier_data.get('trend_hourly', [])
        result['supplier_total_errors'] = supplier_data.get('total_error_count', 0)
        
        return jsonify(result)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 新增错误对比 ====================

@report_bp.route('/weekly-new-errors', methods=['GET'])
def get_weekly_new_errors():
    """
    获取最近一周每天的新增错误
    对比当天和前一天的错误，找出新增的错误码+方法名组合
    支持传入 end_date 参数，以该日期为基准向前推7天
    """
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    
    result = {
        'daily_new_errors': []
    }
    
    try:
        raw_cursor = conn.cursor()
        
        # 检查表是否存在
        if not table_exists(raw_cursor, 'control_hitch_error_mothod'):
            conn.close()
            return jsonify(result)
        
        # 获取基准日期（默认今天）
        end_date_str = request.args.get('end_date')
        if end_date_str:
            try:
                base_date = datetime.strptime(end_date_str, '%Y-%m-%d').date()
            except ValueError:
                base_date = datetime.now().date()
        else:
            base_date = datetime.now().date()
        
        for i in range(7):
            current_date = base_date - timedelta(days=i)
            prev_date = current_date - timedelta(days=1)
            
            current_start = f"{current_date} 00:00:00"
            current_end = f"{current_date} 23:59:59"
            prev_start = f"{prev_date} 00:00:00"
            prev_end = f"{prev_date} 23:59:59"
            
            # 获取当天的错误组合
            if db_type == 'mysql':
                cursor.execute('''
                    SELECT DISTINCT method_name, error_code, error_message, SUM(total_count) as total_count
                    FROM control_hitch_error_mothod
                    WHERE create_time BETWEEN %s AND %s
                    GROUP BY method_name, error_code, error_message
                ''', (current_start, current_end))
            else:
                cursor.execute('''
                    SELECT DISTINCT method_name, error_code, error_message, SUM(total_count) as total_count
                    FROM control_hitch_error_mothod
                    WHERE create_time BETWEEN ? AND ?
                    GROUP BY method_name, error_code, error_message
                ''', (current_start, current_end))
            
            current_errors = {(r['method_name'], r['error_code']): dict(r) for r in cursor.fetchall()}
            
            # 获取前一天的错误组合
            if db_type == 'mysql':
                cursor.execute('''
                    SELECT DISTINCT method_name, error_code
                    FROM control_hitch_error_mothod
                    WHERE create_time BETWEEN %s AND %s
                ''', (prev_start, prev_end))
            else:
                cursor.execute('''
                    SELECT DISTINCT method_name, error_code
                    FROM control_hitch_error_mothod
                    WHERE create_time BETWEEN ? AND ?
                ''', (prev_start, prev_end))
            
            prev_error_keys = {(r['method_name'], r['error_code']) for r in cursor.fetchall()}
            
            # 找出新增的错误
            new_errors = []
            for key, error in current_errors.items():
                if key not in prev_error_keys:
                    new_errors.append(error)
            
            result['daily_new_errors'].append({
                'date': str(current_date),
                'compared_to': str(prev_date),
                'new_count': len(new_errors),
                'errors': sorted(new_errors, key=lambda x: x.get('total_count', 0), reverse=True)[:20]
            })
        
        conn.close()
        return jsonify(result)
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== 推送配置管理 ====================

def init_report_config_table():
    """初始化报表推送配置表并处理兼容性迁移"""
    conn = get_db_connection()
    cursor = conn.cursor()
    db_type = get_db_type()
    
    try:
        if db_type == 'mysql':
            # 检查并更新表结构
            cursor.execute("SHOW TABLES LIKE 'report_push_config'")
            if cursor.fetchone():
                # 表已存在，检查字段
                cursor.execute("DESCRIBE report_push_config")
                columns = [row[0] for row in cursor.fetchall()]
                
                # 迁移 email_recipients 到 email_config
                if 'email_recipients' in columns and 'email_config' not in columns:
                    cursor.execute("ALTER TABLE report_push_config CHANGE email_recipients email_config TEXT COMMENT '邮箱配置JSON'")
                
                # 添加 schedule_cron
                if 'schedule_cron' not in columns:
                    cursor.execute("ALTER TABLE report_push_config ADD COLUMN schedule_cron VARCHAR(125) AFTER schedule_enabled")
                
                # 添加推送模式相关字段
                if 'push_mode' not in columns:
                    cursor.execute("ALTER TABLE report_push_config ADD COLUMN push_mode VARCHAR(32) DEFAULT 'daily' COMMENT '推送模式: daily=每日定时, date=指定日期, relative=相对日期'")
                
                if 'push_date' not in columns:
                    cursor.execute("ALTER TABLE report_push_config ADD COLUMN push_date DATE NULL COMMENT '指定推送日期(push_mode=date时使用)'")
                
                if 'relative_days' not in columns:
                    cursor.execute("ALTER TABLE report_push_config ADD COLUMN relative_days INT DEFAULT 0 COMMENT '相对天数(push_mode=relative时使用，T-N的N值)'")
                
                # 添加最后定时推送时间字段
                if 'last_scheduled_push_time' not in columns:
                    cursor.execute("ALTER TABLE report_push_config ADD COLUMN last_scheduled_push_time TIMESTAMP NULL COMMENT '最后一次定时推送时间'")
                
                # 迁移 created_at 到 create_time
                if 'created_at' in columns and 'create_time' not in columns:
                    cursor.execute("ALTER TABLE report_push_config CHANGE created_at create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
                
                # 迁移 updated_at 到 update_time
                if 'updated_at' in columns and 'update_time' not in columns:
                    cursor.execute("ALTER TABLE report_push_config CHANGE updated_at update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
                
                # 确保 push_type 长度足够
                cursor.execute("ALTER TABLE report_push_config MODIFY push_type VARCHAR(64) NOT NULL COMMENT 'wecom=企微, email=邮箱'")
            else:
                cursor.execute('''
                    CREATE TABLE report_push_config (
                        id INT PRIMARY KEY AUTO_INCREMENT,
                        name VARCHAR(255) NOT NULL,
                        push_type VARCHAR(64) NOT NULL COMMENT 'wecom=企微, email=邮箱',
                        webhook_url TEXT COMMENT '企微机器人webhook地址',
                        email_config TEXT COMMENT '邮箱配置JSON',
                        schedule_enabled TINYINT DEFAULT 0 COMMENT '是否启用定时推送',
                        schedule_cron VARCHAR(125) COMMENT 'Cron表达式',
                        schedule_time VARCHAR(64) COMMENT '每日推送时间 HH:MM',
                        push_mode VARCHAR(32) DEFAULT 'daily' COMMENT '推送模式: daily=每日定时, date=指定日期, relative=相对日期',
                        push_date DATE NULL COMMENT '指定推送日期(push_mode=date时使用)',
                        relative_days INT DEFAULT 0 COMMENT '相对天数(push_mode=relative时使用，T-N的N值)',
                        last_push_time TIMESTAMP NULL COMMENT '上次推送时间',
                        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                ''')
        else:
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS report_push_config (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    push_type TEXT NOT NULL,
                    webhook_url TEXT,
                    email_config TEXT,
                    schedule_enabled INTEGER DEFAULT 0,
                    schedule_cron TEXT,
                    schedule_time TEXT,
                    push_mode TEXT DEFAULT 'daily',
                    push_date DATE,
                    relative_days INTEGER DEFAULT 0,
                    last_push_time TIMESTAMP,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            ''')
        conn.commit()
    except Exception as e:
        print(f"初始化报表推送配置表失败: {e}")
    finally:
        conn.close()


def init_push_log_table():
    """初始化推送记录表并处理兼容性迁移"""
    conn = get_db_connection()
    cursor = conn.cursor()
    db_type = get_db_type()
    
    try:
        if db_type == 'mysql':
            cursor.execute("SHOW TABLES LIKE 'report_push_log'")
            if cursor.fetchone():
                cursor.execute("DESCRIBE report_push_log")
                columns = [row[0] for row in cursor.fetchall()]
                
                if 'created_at' in columns and 'create_time' not in columns:
                    cursor.execute("ALTER TABLE report_push_log CHANGE created_at create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
                
                if 'updated_at' in columns and 'update_time' not in columns:
                    cursor.execute("ALTER TABLE report_push_log CHANGE updated_at update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
                
                if 'push_type' in columns:
                    cursor.execute("ALTER TABLE report_push_log MODIFY push_type VARCHAR(64) NOT NULL")
                
                if 'push_mode' in columns:
                    cursor.execute("ALTER TABLE report_push_log MODIFY push_mode VARCHAR(64)")
            else:
                cursor.execute('''
                    CREATE TABLE report_push_log (
                        id INT PRIMARY KEY AUTO_INCREMENT,
                        config_id INT NOT NULL COMMENT '推送配置ID',
                        config_name VARCHAR(255) COMMENT '推送配置名称',
                        push_type VARCHAR(64) NOT NULL COMMENT '推送类型: wecom/email',
                        push_mode VARCHAR(64) COMMENT '推送模式: image/markdown',
                        report_date VARCHAR(20) NOT NULL COMMENT '报表数据日期',
                        status VARCHAR(20) DEFAULT 'pending' COMMENT '推送状态: pending/success/failed',
                        webhook_url TEXT COMMENT 'Webhook地址',
                        image_data LONGTEXT COMMENT '推送的图片base64',
                        response_text TEXT COMMENT '推送响应内容',
                        error_message TEXT COMMENT '错误信息',
                        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '推送时间',
                        update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                ''')
        else:
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS report_push_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    config_id INTEGER NOT NULL,
                    config_name TEXT,
                    push_type TEXT NOT NULL,
                    push_mode TEXT,
                    report_date TEXT NOT NULL,
                    status TEXT DEFAULT 'pending',
                    webhook_url TEXT,
                    image_data TEXT,
                    response_text TEXT,
                    error_message TEXT,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            ''')
        conn.commit()
    except Exception as e:
        print(f"初始化推送记录表失败: {e}")
    finally:
        conn.close()


# 在模块加载时初始化表
init_report_config_table()
init_push_log_table()


@report_bp.route('/push-configs', methods=['GET'])
def get_push_configs():
    """获取推送配置列表"""
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    
    try:
        cursor.execute('SELECT * FROM report_push_config ORDER BY id DESC')
        configs = []
        for row in cursor.fetchall():
            config = dict(row) if not isinstance(row, dict) else row
            # 处理时间格式
            for field in ['last_push_time', 'create_time', 'update_time']:
                if config.get(field) and hasattr(config[field], 'isoformat'):
                    config[field] = config[field].isoformat()
            # 解析邮箱配置JSON
            if config.get('email_config'):
                try:
                    config['email_config'] = json.loads(config['email_config'])
                except:
                    pass
            configs.append(config)
        conn.close()
        return jsonify(configs)
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


@report_bp.route('/push-configs', methods=['POST'])
def create_push_config():
    """创建推送配置"""
    data = request.get_json() or {}
    
    name = data.get('name')
    push_type = data.get('push_type')
    
    if not name or not push_type:
        return jsonify({'error': '名称和推送类型不能为空'}), 400
    
    # 处理邮箱配置JSON
    email_config = data.get('email_config')
    if email_config and isinstance(email_config, dict):
        email_config = json.dumps(email_config)
    
    # 处理推送模式
    push_mode = data.get('push_mode', 'daily')  # daily, date, relative
    push_date = data.get('push_date')  # 指定日期 YYYY-MM-DD
    relative_days = data.get('relative_days', 0)  # T-N的N值
    
    # 处理空字符串的 push_date，MySQL DATE 类型不接受空字符串
    if push_date == '':
        push_date = None
    
    # 验证推送模式
    if push_mode not in ['daily', 'date', 'relative']:
        return jsonify({'error': '推送模式无效，必须是 daily、date 或 relative'}), 400
    
    if push_mode == 'date' and not push_date:
        return jsonify({'error': '指定日期模式必须提供推送日期'}), 400
    
    if push_mode == 'relative' and (not isinstance(relative_days, int) or relative_days < 0):
        return jsonify({'error': '相对日期模式必须提供有效的天数(>=0)'}), 400
    
    conn = get_db_connection()
    cursor = conn.cursor()
    db_type = get_db_type()
    
    try:
        if db_type == 'mysql':
            cursor.execute('''
                INSERT INTO report_push_config (name, push_type, webhook_url, email_config, schedule_enabled, schedule_cron, schedule_time, push_mode, push_date, relative_days)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ''', (
                name,
                push_type,
                data.get('webhook_url', ''),
                email_config,
                data.get('schedule_enabled', 0),
                data.get('schedule_cron'),
                data.get('schedule_time'),
                push_mode,
                push_date,
                relative_days
            ))
        else:
            cursor.execute('''
                INSERT INTO report_push_config (name, push_type, webhook_url, email_config, schedule_enabled, schedule_cron, schedule_time, push_mode, push_date, relative_days)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', (
                name,
                push_type,
                data.get('webhook_url', ''),
                email_config,
                data.get('schedule_enabled', 0),
                data.get('schedule_cron'),
                data.get('schedule_time'),
                push_mode,
                push_date,
                relative_days
            ))
        conn.commit()
        conn.close()
        return jsonify({'message': '创建成功'}), 201
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


@report_bp.route('/push-configs/<int:config_id>', methods=['PUT'])
def update_push_config(config_id):
    """更新推送配置"""
    data = request.get_json() or {}
    
    # 处理邮箱配置JSON
    email_config = data.get('email_config')
    if email_config and isinstance(email_config, dict):
        email_config = json.dumps(email_config)
    
    # 处理推送模式
    push_mode = data.get('push_mode', 'daily')
    push_date = data.get('push_date')
    relative_days = data.get('relative_days', 0)
    
    # 处理空字符串的 push_date，MySQL DATE 类型不接受空字符串
    if push_date == '':
        push_date = None
    
    # 验证推送模式
    if push_mode not in ['daily', 'date', 'relative']:
        return jsonify({'error': '推送模式无效，必须是 daily、date 或 relative'}), 400
    
    if push_mode == 'date' and not push_date:
        return jsonify({'error': '指定日期模式必须提供推送日期'}), 400
    
    if push_mode == 'relative' and (not isinstance(relative_days, int) or relative_days < 0):
        return jsonify({'error': '相对日期模式必须提供有效的天数(>=0)'}), 400
    
    conn = get_db_connection()
    cursor = conn.cursor()
    db_type = get_db_type()
    
    try:
        if db_type == 'mysql':
            cursor.execute('''
                UPDATE report_push_config 
                SET name = %s, push_type = %s, webhook_url = %s, email_config = %s, 
                    schedule_enabled = %s, schedule_cron = %s, schedule_time = %s,
                    push_mode = %s, push_date = %s, relative_days = %s
                WHERE id = %s
            ''', (
                data.get('name'),
                data.get('push_type'),
                data.get('webhook_url', ''),
                email_config,
                data.get('schedule_enabled', 0),
                data.get('schedule_cron'),
                data.get('schedule_time'),
                push_mode,
                push_date,
                relative_days,
                config_id
            ))
        else:
            cursor.execute('''
                UPDATE report_push_config 
                SET name = ?, push_type = ?, webhook_url = ?, email_config = ?, 
                    schedule_enabled = ?, schedule_cron = ?, schedule_time = ?, 
                    push_mode = ?, push_date = ?, relative_days = ?, update_time = CURRENT_TIMESTAMP
                WHERE id = ?
            ''', (
                data.get('name'),
                data.get('push_type'),
                data.get('webhook_url', ''),
                email_config,
                data.get('schedule_enabled', 0),
                data.get('schedule_cron'),
                data.get('schedule_time'),
                push_mode,
                push_date,
                relative_days,
                config_id
            ))
        conn.commit()
        conn.close()
        return jsonify({'message': '更新成功'})
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


@report_bp.route('/push-configs/<int:config_id>', methods=['DELETE'])
def delete_push_config(config_id):
    """删除推送配置"""
    conn = get_db_connection()
    cursor = conn.cursor()
    db_type = get_db_type()
    
    try:
        if db_type == 'mysql':
            cursor.execute('DELETE FROM report_push_config WHERE id = %s', (config_id,))
        else:
            cursor.execute('DELETE FROM report_push_config WHERE id = ?', (config_id,))
        conn.commit()
        conn.close()
        return jsonify({'message': '删除成功'})
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== 推送功能 ====================

def generate_wecom_markdown(report_data):
    """生成企微机器人的Markdown消息"""
    date_str = report_data.get('date', datetime.now().strftime('%Y-%m-%d'))
    
    content = f"""## 📊 日报汇总 - {date_str}

### Control 错误统计
> 累计错误数: **{report_data.get('control_total_errors', 0)}**

### 错误码 TOP5
"""
    
    error_codes = report_data.get('control_error_code_top10', [])[:5]
    for i, item in enumerate(error_codes, 1):
        content += f"{i}. `{item.get('error_code', '-')}` - {item.get('count', 0)}次\n"
    
    content += f"""
### GW 方法报错 TOP5
"""
    
    gw_methods = report_data.get('gw_method_top10', [])[:5]
    for i, item in enumerate(gw_methods, 1):
        method_name = item.get('method_name', '-')
        if len(method_name) > 30:
            method_name = method_name[:30] + '...'
        content += f"{i}. `{method_name}` - {item.get('count', 0)}次\n"
    
    content += f"""
### 顺风车高耗时接口 TOP5
"""
    
    high_cost = report_data.get('high_cost_top15', [])[:5]
    for i, item in enumerate(high_cost, 1):
        method_name = item.get('method_name', '-')
        if len(method_name) > 30:
            method_name = method_name[:30] + '...'
        max_cost = item.get('max_cost', item.get('max_cost_time', 0))
        content += f"{i}. `{method_name}` - {max_cost}ms\n"
    
    content += f"""
---
*生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}*
"""
    
    return content


def push_to_wecom(webhook_url, report_data, image_base64=None):
    """推送到企微机器人（图片模式）"""
    if image_base64:
        # 计算图片的 MD5 和 base64
        image_bytes = base64.b64decode(image_base64)
        image_md5 = hashlib.md5(image_bytes).hexdigest()
        
        payload = {
            "msgtype": "image",
            "image": {
                "base64": image_base64,
                "md5": image_md5
            }
        }
    else:
        # 降级为 Markdown 模式
        content = generate_wecom_markdown(report_data)
        payload = {
            "msgtype": "markdown",
            "markdown": {
                "content": content
            }
        }
    
    response = requests.post(webhook_url, json=payload, timeout=30)
    return response.status_code == 200, response.text


@report_bp.route('/push', methods=['POST'])
def trigger_push():
    """手动触发推送"""
    data = request.get_json() or {}
    config_id = data.get('config_id')
    date_str = data.get('date', datetime.now().strftime('%Y-%m-%d'))
    image_base64 = data.get('image_base64')  # 前端传来的图片 base64
    
    if not config_id:
        return jsonify({'error': '请指定推送配置'}), 400
    
    # 获取推送配置
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    
    try:
        if db_type == 'mysql':
            cursor.execute('SELECT * FROM report_push_config WHERE id = %s', (config_id,))
        else:
            cursor.execute('SELECT * FROM report_push_config WHERE id = ?', (config_id,))
        
        config = cursor.fetchone()
        if not config:
            conn.close()
            return jsonify({'error': '推送配置不存在'}), 404
        
        config = dict(config) if not isinstance(config, dict) else config
        
        # 获取报表数据（用于降级到 Markdown 模式）
        start_time, end_time = get_date_range(date_str, date_str)
        
        # 复用summary接口获取数据
        from flask import current_app
        with current_app.test_request_context(f'/api/report/summary?date={date_str}'):
            summary_response = get_report_summary()
            report_data = summary_response.get_json()
        
        # 根据推送类型执行推送
        if config['push_type'] == 'wecom':
            webhook_url = config.get('webhook_url')
            if not webhook_url:
                conn.close()
                return jsonify({'error': '企微Webhook地址未配置'}), 400
            
            push_mode = 'image' if image_base64 else 'markdown'
            
            # 创建推送记录（状态为pending）
            raw_cursor = conn.cursor()
            if db_type == 'mysql':
                raw_cursor.execute('''
                    INSERT INTO report_push_log 
                    (config_id, config_name, push_type, push_mode, report_date, status, webhook_url, image_data)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ''', (config_id, config['name'], 'wecom', push_mode, date_str, 'pending', webhook_url, image_base64))
                log_id = raw_cursor.lastrowid
            else:
                raw_cursor.execute('''
                    INSERT INTO report_push_log 
                    (config_id, config_name, push_type, push_mode, report_date, status, webhook_url, image_data)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ''', (config_id, config['name'], 'wecom', push_mode, date_str, 'pending', webhook_url, image_base64))
                log_id = raw_cursor.lastrowid
            conn.commit()
            
            # 使用前端传来的图片，如果没有则降级为 Markdown
            if image_base64:
                print(f"[Push] 使用前端传来的图片，大小: {len(image_base64)} bytes")
            else:
                print(f"[Push] 未收到图片，将使用Markdown模式")
            
            success, result = push_to_wecom(webhook_url, report_data, image_base64)
            
            if success:
                # 更新推送记录状态为成功
                if db_type == 'mysql':
                    raw_cursor.execute('''
                        UPDATE report_push_log SET status = %s, response_text = %s, update_time = NOW()
                        WHERE id = %s
                    ''', ('success', result, log_id))
                    raw_cursor.execute('UPDATE report_push_config SET last_push_time = NOW() WHERE id = %s', (config_id,))
                else:
                    raw_cursor.execute('''
                        UPDATE report_push_log SET status = ?, response_text = ?, update_time = CURRENT_TIMESTAMP
                        WHERE id = ?
                    ''', ('success', result, log_id))
                    raw_cursor.execute('UPDATE report_push_config SET last_push_time = CURRENT_TIMESTAMP WHERE id = ?', (config_id,))
                conn.commit()
                conn.close()
                push_mode_cn = '图片' if image_base64 else 'Markdown'
                return jsonify({'message': f'推送成功（{push_mode_cn}模式）', 'result': result, 'log_id': log_id})
            else:
                # 更新推送记录状态为失败
                if db_type == 'mysql':
                    raw_cursor.execute('''
                        UPDATE report_push_log SET status = %s, error_message = %s, update_time = NOW()
                        WHERE id = %s
                    ''', ('failed', result, log_id))
                else:
                    raw_cursor.execute('''
                        UPDATE report_push_log SET status = ?, error_message = ?, update_time = CURRENT_TIMESTAMP
                        WHERE id = ?
                    ''', ('failed', result, log_id))
                conn.commit()
                conn.close()
                return jsonify({'error': f'推送失败: {result}', 'log_id': log_id}), 500
        
        elif config['push_type'] == 'email':
            # 邮件推送暂未实现
            conn.close()
            return jsonify({'error': '邮件推送功能暂未实现'}), 501
        
        else:
            conn.close()
            return jsonify({'error': f'不支持的推送类型: {config["push_type"]}'}), 400
    
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


@report_bp.route('/push-logs', methods=['GET'])
@log_api_call(logger)
def get_push_logs():
    """获取推送记录列表（支持分页）"""
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    ph = get_placeholder()
    
    try:
        # 获取分页参数
        page = int(request.args.get('page', 1))
        page_size = int(request.args.get('page_size', 20))
        
        # 计算偏移量
        offset = (page - 1) * page_size
        
        # 获取总记录数
        cursor.execute('SELECT COUNT(*) as total FROM report_push_log')
        total_result = cursor.fetchone()
        total = total_result['total'] if isinstance(total_result, dict) else total_result[0]
        
        # 获取分页数据（不包含图片数据）
        if db_type == 'mysql':
            cursor.execute('''
                SELECT id, config_id, config_name, push_type, push_mode, report_date, 
                       status, webhook_url, response_text, error_message, create_time
                FROM report_push_log
                ORDER BY create_time DESC
                LIMIT %s OFFSET %s
            ''', (page_size, offset))
        else:
            cursor.execute('''
                SELECT id, config_id, config_name, push_type, push_mode, report_date, 
                       status, webhook_url, response_text, error_message, create_time
                FROM report_push_log
                ORDER BY create_time DESC
                LIMIT ? OFFSET ?
            ''', (page_size, offset))
        
        logs = []
        for row in cursor.fetchall():
            log = dict(row) if not isinstance(row, dict) else row
            # 处理时间格式
            if log.get('create_time') and hasattr(log['create_time'], 'isoformat'):
                log['create_time'] = log['create_time'].isoformat()
            if log.get('report_date') and hasattr(log['report_date'], 'isoformat'):
                log['report_date'] = log['report_date'].isoformat()
            logs.append(log)
        
        conn.close()
        
        # 计算分页信息
        total_pages = (total + page_size - 1) // page_size
        
        return jsonify({
            'data': logs,
            'pagination': {
                'current_page': page,
                'page_size': page_size,
                'total': total,
                'total_pages': total_pages,
                'has_prev': page > 1,
                'has_next': page < total_pages
            }
        })
        
    except Exception as e:
        conn.close()
        logger.error(f"获取推送记录失败: {e}")
        return jsonify({'error': str(e)}), 500


@report_bp.route('/push-logs/<int:log_id>', methods=['GET'])
def get_push_log_detail(log_id):
    """获取推送记录详情（包含图片）"""
    conn = get_db_connection()
    cursor = dict_cursor(conn)
    db_type = get_db_type()
    
    try:
        if db_type == 'mysql':
            cursor.execute('SELECT * FROM report_push_log WHERE id = %s', (log_id,))
        else:
            cursor.execute('SELECT * FROM report_push_log WHERE id = ?', (log_id,))
        
        log = cursor.fetchone()
        conn.close()
        
        if not log:
            return jsonify({'error': '推送记录不存在'}), 404
        
        log = dict(log) if not isinstance(log, dict) else log
        # 处理时间格式
        for field in ['create_time', 'update_time']:
            if log.get(field) and hasattr(log[field], 'isoformat'):
                log[field] = log[field].isoformat()
        
        return jsonify(log)
        
        return jsonify(dict(log) if not isinstance(log, dict) else log)
    except Exception as e:
        conn.close()
        return jsonify({'error': str(e)}), 500


# ==================== 导出功能 ====================

def generate_report_html(report_data):
    """生成报表HTML内容"""
    date_str = report_data.get('date', datetime.now().strftime('%Y-%m-%d'))
    
    html = f"""
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>日报汇总 - {date_str}</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif; background: #0f1419; color: #e6edf3; padding: 20px; }}
        .container {{ max-width: 1200px; margin: 0 auto; }}
        h1 {{ color: #58a6ff; border-bottom: 1px solid #30363d; padding-bottom: 10px; }}
        h2 {{ color: #8b949e; margin-top: 30px; }}
        .stat-card {{ display: inline-block; background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 20px; margin: 10px; min-width: 200px; }}
        .stat-value {{ font-size: 32px; font-weight: bold; color: #58a6ff; }}
        .stat-label {{ color: #8b949e; margin-top: 5px; }}
        table {{ width: 100%; border-collapse: collapse; margin-top: 15px; }}
        th, td {{ padding: 12px; text-align: left; border-bottom: 1px solid #30363d; }}
        th {{ background: #0d1117; color: #8b949e; font-weight: 500; }}
        td {{ color: #c9d1d9; }}
        .badge {{ display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 12px; }}
        .badge-danger {{ background: rgba(248, 81, 73, 0.2); color: #f85149; }}
        code {{ background: #21262d; padding: 2px 6px; border-radius: 4px; font-size: 13px; }}
    </style>
</head>
<body>
    <div class="container">
        <h1>📊 日报汇总 - {date_str}</h1>
        
        <div class="stat-card">
            <div class="stat-value">{report_data.get('control_total_errors', 0)}</div>
            <div class="stat-label">Control 累计错误数</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">{report_data.get('gw_total_errors', 0)}</div>
            <div class="stat-label">GW 累计错误数</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">{report_data.get('max_cost_time', 0)}ms</div>
            <div class="stat-label">最大耗时</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">{round(report_data.get('avg_cost_time', 0), 2)}ms</div>
            <div class="stat-label">平均耗时</div>
        </div>
        
        <h2>错误码分布 TOP10</h2>
        <table>
            <thead><tr><th>排名</th><th>错误码</th><th>次数</th></tr></thead>
            <tbody>
"""
    
    for i, item in enumerate(report_data.get('control_error_code_top10', [])[:10], 1):
        html += f"<tr><td>{i}</td><td><span class='badge badge-danger'>{item.get('error_code', '-')}</span></td><td>{item.get('count', 0)}</td></tr>\n"
    
    html += """
            </tbody>
        </table>
        
        <h2>GW 方法报错 TOP10</h2>
        <table>
            <thead><tr><th>排名</th><th>方法名</th><th>次数</th></tr></thead>
            <tbody>
"""
    
    for i, item in enumerate(report_data.get('gw_method_top10', [])[:10], 1):
        html += f"<tr><td>{i}</td><td><code>{item.get('method_name', '-')}</code></td><td>{item.get('count', 0)}</td></tr>\n"
    
    html += """
            </tbody>
        </table>
        
        <h2>顺风车高耗时接口 TOP15</h2>
        <table>
            <thead><tr><th>排名</th><th>方法名</th><th>最大耗时(ms)</th><th>平均耗时(ms)</th></tr></thead>
            <tbody>
"""
    
    for i, item in enumerate(report_data.get('high_cost_top15', [])[:15], 1):
        max_cost = item.get('max_cost', item.get('max_cost_time', 0))
        avg_cost = round(item.get('avg_cost', item.get('avg_cost_time', 0)), 2)
        html += f"<tr><td>{i}</td><td><code>{item.get('method_name', '-')}</code></td><td>{max_cost}</td><td>{avg_cost}</td></tr>\n"
    
    html += f"""
            </tbody>
        </table>
        
        <p style="margin-top: 30px; color: #8b949e; font-size: 12px;">
            生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
        </p>
    </div>
</body>
</html>
"""
    
    return html


@report_bp.route('/export', methods=['POST'])
def export_report():
    """导出报表为HTML或JSON"""
    data = request.get_json() or {}
    export_format = data.get('format', 'html')  # html, json
    date_str = data.get('date', datetime.now().strftime('%Y-%m-%d'))
    
    # 获取报表数据
    from flask import current_app
    with current_app.test_request_context(f'/api/report/summary?date={date_str}'):
        summary_response = get_report_summary()
        report_data = summary_response.get_json()
    
    if export_format == 'html':
        html_content = generate_report_html(report_data)
        return jsonify({
            'format': 'html',
            'content': html_content,
            'filename': f'report_{date_str}.html'
        })
    
    elif export_format == 'json':
        return jsonify({
            'format': 'json',
            'content': report_data,
            'filename': f'report_{date_str}.json'
        })
    
    else:
        return jsonify({'error': f'不支持的导出格式: {export_format}'}), 400


@report_bp.route('/screenshot', methods=['GET'])
def generate_screenshot():
    """生成报表截图（使用 Playwright）"""
    date_str = request.args.get('date', datetime.now().strftime('%Y-%m-%d'))
    
    try:
        from services.screenshot_service import capture_report_screenshot_sync
        
        # 获取服务基础 URL
        base_url = request.host_url.rstrip('/')
        if not base_url.startswith('http'):
            base_url = f"http://{request.host}"
        
        print(f"[Screenshot] 开始截图，base_url: {base_url}, date: {date_str}")
        
        image_base64 = capture_report_screenshot_sync(base_url, date_str)
        
        if image_base64:
            return jsonify({
                'success': True,
                'image_base64': image_base64,
                'size': len(image_base64)
            })
        else:
            return jsonify({'error': '截图失败'}), 500
            
    except ImportError as e:
        return jsonify({'error': f'截图模块不可用: {e}'}), 500
    except Exception as e:
        return jsonify({'error': str(e)}), 500
