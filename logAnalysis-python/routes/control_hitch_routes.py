# -*- coding: utf-8 -*-
"""
Control Hitch日志处理API路由
提供Control日志查询和control_hitch_error_mothod表数据管理接口
"""
from flask import Blueprint, request, jsonify, g

# 创建蓝图
control_hitch_bp = Blueprint('control_hitch', __name__, url_prefix='/api/control-hitch')


def get_db_config():
    """获取数据库配置"""
    from config.database import MYSQL_CONFIG
    return MYSQL_CONFIG


@control_hitch_bp.route('/transform-rules', methods=['GET'])
def get_transform_rules():
    """获取control_hitch_error_mothod表的详细转换规则"""
    # 默认配置 - 适配新表结构
    default_config = {
        'table_name': 'control_hitch_error_mothod',
        'table_display_name': 'Control Hitch错误日志表',
        'description': '将Control服务日志数据按照特定规则转换并写入数据库，用于错误分析和统计',
        'field_config': [
            {
                'name': 'id',
                'type': 'BIGINT',
                'source': None,
                'transform': None,
                'required': False,
                'default': None,
                'description': '自增主键，自动生成'
            },
            {
                'name': 'method_name',
                'type': 'VARCHAR(255)',
                'source': 'content',
                'transform': 'regex:method:([^,]+):1',
                'required': False,
                'default': None,
                'description': '出错的方法名，从content中提取method:后面的值'
            },
            {
                'name': 'error_code',
                'type': 'VARCHAR(255)',
                'source': 'content',
                'transform': 'regex:code=(\\d+):1|default_code:500',
                'required': False,
                'default': None,
                'description': '错误码，从content中提取code=后面的数字'
            },
            {
                'name': 'error_message',
                'type': 'VARCHAR(1024)',
                'source': 'content',
                'transform': 'regex:desc=([^)]+):1|default_if_timeout:system_error',
                'required': False,
                'default': None,
                'description': '错误信息，从content中提取desc=后面的内容'
            },
            {
                'name': 'content',
                'type': 'VARCHAR(10240)',
                'source': 'content',
                'transform': 'substr:10240',
                'required': False,
                'default': None,
                'description': '响应内容，截取前10240个字符'
            },
            {
                'name': 'count',
                'type': 'INT',
                'source': None,
                'transform': None,
                'required': False,
                'default': 0,
                'description': '单次聚合周期内的错误次数'
            },
            {
                'name': 'total_count',
                'type': 'BIGINT',
                'source': None,
                'transform': None,
                'required': False,
                'default': 0,
                'description': '累计错误总数'
            },
            {
                'name': 'create_time',
                'type': 'TIMESTAMP',
                'source': None,
                'transform': None,
                'required': False,
                'default': 'CURRENT_TIMESTAMP',
                'description': '记录创建时间，自动填充'
            },
            {
                'name': 'update_time',
                'type': 'TIMESTAMP',
                'source': None,
                'transform': None,
                'required': False,
                'default': 'CURRENT_TIMESTAMP',
                'description': '记录最后更新时间，自动更新'
            }
        ],
        'transform_examples': [
            {
                'input': {
                    'content': '[biz_worker_pool-thread-342] method:orderStatusUpdate,failed,time cost:1006,reason:请求过于频繁 BizException(code=700000, desc=请求过于频繁)\n\tat com.qq.mobility...'
                },
                'output': {
                    'method_name': 'orderStatusUpdate',
                    'error_code': '700000',
                    'error_message': '请求过于频繁',
                    'count': 1,
                    'total_count': 1
                }
            },
            {
                'input': {
                    'content': '[biz_worker_pool-thread-100] method:createOrder,failed,time cost:500,reason:系统异常 BizException(code=500001, desc=数据库连接失败)\n\tat com.qq.mobility...'
                },
                'output': {
                    'method_name': 'createOrder',
                    'error_code': '500001',
                    'error_message': '数据库连接失败',
                    'count': 1,
                    'total_count': 1
                }
            }
        ]
    }
    
    # 尝试从数据库读取已保存的配置
    try:
        from config.database import get_db_connection, dict_cursor, get_placeholder
        import json as json_module
        
        conn = get_db_connection()
        cursor = dict_cursor(conn)
        ph = get_placeholder()
        
        cursor.execute(f'''
            SELECT id, table_display_name, description, field_config 
            FROM topic_table_mappings 
            WHERE table_name = {ph}
        ''', ('control_hitch_error_mothod',))
        
        row = cursor.fetchone()
        conn.close()
        
        if row:
            row = dict(row) if not isinstance(row, dict) else row
            # 如果数据库中有保存的配置，使用数据库的配置
            if row.get('field_config'):
                field_config = row['field_config']
                if isinstance(field_config, str):
                    field_config = json_module.loads(field_config)
                default_config['field_config'] = field_config
            if row.get('table_display_name'):
                default_config['table_display_name'] = row['table_display_name']
            if row.get('description'):
                default_config['description'] = row['description']
    except Exception as e:
        # 如果读取失败，使用默认配置
        print(f"读取数据库配置失败: {e}")
    
    return jsonify(default_config)


@control_hitch_bp.route('/schema', methods=['GET'])
def get_table_schema():
    """获取control_hitch_error_mothod表结构"""
    return jsonify({
        'table_name': 'control_hitch_error_mothod',
        'description': '顺风车错误方法监控表',
        'columns': [
            {'name': 'id', 'type': 'BIGINT', 'description': '主键ID，自增'},
            {'name': 'method_name', 'type': 'VARCHAR(255)', 'description': '出错的方法名'},
            {'name': 'error_code', 'type': 'VARCHAR(255)', 'description': '错误码'},
            {'name': 'error_message', 'type': 'VARCHAR(1024)', 'description': '错误信息'},
            {'name': 'content', 'type': 'VARCHAR(10240)', 'description': '响应内容'},
            {'name': 'count', 'type': 'INT', 'description': '单次聚合周期内的错误次数'},
            {'name': 'total_count', 'type': 'BIGINT', 'description': '累计错误总数'},
            {'name': 'create_time', 'type': 'TIMESTAMP', 'description': '记录创建时间'},
            {'name': 'update_time', 'type': 'TIMESTAMP', 'description': '记录最后更新时间'}
        ],
        'field_mapping': {
            'method_name': 'content中method:后面的值',
            'error_code': 'content中code=后面的数字',
            'error_message': 'content中desc=后面的内容',
            'content': 'content原始值（截取前10240字符）',
            'count': '单次聚合周期内的错误次数',
            'total_count': '累计错误总数',
            'create_time': '记录创建时间',
            'update_time': '记录最后更新时间'
        }
    })


# ==================== 数据查询 ====================

@control_hitch_bp.route('/data', methods=['GET'])
def get_table_data():
    """获取control_hitch_error_mothod表数据"""
    try:
        limit = request.args.get('limit', 100, type=int)
        offset = request.args.get('offset', 0, type=int)
        order_by = request.args.get('order_by', 'id')
        order_dir = request.args.get('order_dir', 'DESC')
        
        from services.control_hitch_processor import ControlHitchProcessor
        processor = ControlHitchProcessor(get_db_config())
        result = processor.get_table_data(limit, offset, order_by, order_dir)
        
        return jsonify(result)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 数据处理 ====================

@control_hitch_bp.route('/process', methods=['POST'])
def process_logs():
    """处理日志数据并写入control_hitch_error_mothod表"""
    try:
        data = request.json
        
        if not data.get('log_data'):
            return jsonify({'error': '缺少日志数据'}), 400
        
        from services.control_hitch_processor import ControlHitchProcessor
        processor = ControlHitchProcessor(get_db_config())
        # 清除缓存以确保使用最新的映射规则
        processor.clear_config_cache()
        result = processor.process_logs(data['log_data'])
        
        return jsonify(result)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@control_hitch_bp.route('/collect', methods=['POST'])
def collect_and_process():
    """从CLS查询日志并处理写入control_hitch_error_mothod表"""
    try:
        data = request.json or {}
        
        # 获取必要参数
        credential_id = data.get('credential_id')
        topic_id = data.get('topic_id')
        query = data.get('query', '*')
        time_range = data.get('time_range', 3600)  # 默认1小时
        limit = data.get('limit', 100)
        
        if not credential_id or not topic_id:
            return jsonify({'error': '缺少credential_id或topic_id参数'}), 400
        
        import time as time_module
        import json as json_module
        
        # 计算时间范围
        now = int(time_module.time() * 1000)
        from_time = now - time_range * 1000
        
        # 调用CLS API
        from app import call_cls_api
        cls_result = call_cls_api(
            credential_id=credential_id,
            topic_id=topic_id,
            query=query,
            from_time=from_time,
            to_time=now,
            limit=limit
        )
        
        # 检查CLS响应
        if 'Response' not in cls_result:
            return jsonify({'error': 'CLS API调用失败'}), 500
        
        if 'Error' in cls_result['Response']:
            return jsonify({
                'error': cls_result['Response']['Error'].get('Message', '未知错误'),
                'code': cls_result['Response']['Error'].get('Code')
            }), 500
        
        # 处理日志数据
        from services.control_hitch_processor import ControlHitchProcessor
        processor = ControlHitchProcessor(get_db_config())
        # 清除缓存以确保使用最新的映射规则
        processor.clear_config_cache()
        process_result = processor.process_cls_response(cls_result)
        
        return jsonify({
            'cls_result': {
                'total': len(cls_result['Response'].get('Results', [])),
                'analysis': cls_result['Response'].get('Analysis', False)
            },
            'process_result': process_result
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@control_hitch_bp.route('/statistics', methods=['GET'])
def get_statistics():
    """获取错误统计信息"""
    try:
        from services.control_hitch_processor import ControlHitchProcessor
        processor = ControlHitchProcessor(get_db_config())
        result = processor.get_error_statistics()
        
        return jsonify(result)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@control_hitch_bp.route('/processor-types', methods=['GET'])
def get_processor_types():
    """获取支持的处理器类型"""
    return jsonify({
        'types': [
            {
                'value': 'control_hitch_error',
                'label': 'Control Hitch错误日志处理器',
                'description': '将Control日志按照特定规则转换并写入control_hitch_error_mothod表',
                'target_table': 'control_hitch_error_mothod'
            }
        ]
    })


@control_hitch_bp.route('/clear-cache', methods=['POST'])
def clear_config_cache():
    """清除映射规则缓存，用于在映射规则更新后重新加载"""
    try:
        from services.control_hitch_processor import ControlHitchProcessor
        processor = ControlHitchProcessor(get_db_config())
        processor.clear_config_cache()
        return jsonify({'message': '缓存已清除'})
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@control_hitch_bp.route('/transform-test', methods=['POST'])
def transform_test():
    """
    执行转换测试，使用与入库相同的转换逻辑
    确保转换测试和实际入库使用同一套转换规则
    
    使用统一的 TransformUtils 工具类进行转换
    """
    try:
        data = request.json
        
        if not data.get('log_data'):
            return jsonify({'error': '缺少日志数据'}), 400
        
        log_data = data['log_data']
        table_name = 'control_hitch_error_mothod'
        
        from config.database import get_db_type, MYSQL_CONFIG
        import os
        
        # 使用统一的转换工具函数
        try:
            from services.transform_utils import transform_for_table
            db_config = MYSQL_CONFIG if get_db_type() == 'mysql' else os.path.join(os.path.dirname(os.path.dirname(__file__)), 'data.db')
            result = transform_for_table(table_name, log_data, db_config)
        except ValueError:
            # 没有映射配置，使用ControlHitchProcessor的默认转换逻辑
            from services.control_hitch_processor import ControlHitchProcessor
            processor = ControlHitchProcessor(get_db_config())
            processor.clear_config_cache()
            result = processor.transform_log(log_data)
        
        return jsonify({
            'success': True,
            'result': result
        })
    except Exception as e:
        import traceback
        return jsonify({'error': str(e), 'traceback': traceback.format_exc()}), 500
