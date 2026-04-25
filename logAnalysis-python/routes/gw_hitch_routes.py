# -*- coding: utf-8 -*-
"""
GW Hitch日志处理API路由
提供GW日志查询和gw_hitch_error_mothod表数据管理接口
"""
from flask import Blueprint, request, jsonify, g

# 创建蓝图
gw_hitch_bp = Blueprint('gw_hitch', __name__, url_prefix='/api/gw-hitch')


def get_db_config():
    """获取数据库配置"""
    from config.database import MYSQL_CONFIG
    return MYSQL_CONFIG


def get_gw_processor():
    """获取GW Hitch处理器实例"""
    if 'gw_processor' not in g:
        from services.gw_hitch_processor import GwHitchProcessor
        g.gw_processor = GwHitchProcessor(get_db_config())
    return g.gw_processor


# ==================== 数据查询 ====================

@gw_hitch_bp.route('/data', methods=['GET'])
def get_table_data():
    """获取gw_hitch_error_mothod表数据"""
    try:
        limit = request.args.get('limit', 100, type=int)
        offset = request.args.get('offset', 0, type=int)
        order_by = request.args.get('order_by', 'id')
        order_dir = request.args.get('order_dir', 'DESC')
        
        processor = get_gw_processor()
        result = processor.get_table_data(
            limit=limit,
            offset=offset,
            order_by=order_by,
            order_dir=order_dir
        )
        
        return jsonify(result)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@gw_hitch_bp.route('/statistics', methods=['GET'])
def get_error_statistics():
    """获取错误统计信息"""
    try:
        processor = get_gw_processor()
        result = processor.get_error_statistics()
        return jsonify(result)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 数据处理 ====================

@gw_hitch_bp.route('/process', methods=['POST'])
def process_logs():
    """处理日志数据并写入gw_hitch_error_mothod表"""
    try:
        data = request.json
        
        if not data.get('log_data'):
            return jsonify({'error': '缺少日志数据'}), 400
        
        processor = get_gw_processor()
        # 清除缓存以确保使用最新的映射规则
        processor.clear_config_cache()
        result = processor.process_logs(data['log_data'])
        
        return jsonify({
            'message': '处理完成',
            'result': result
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@gw_hitch_bp.route('/collect', methods=['POST'])
def collect_and_process():
    """从CLS查询日志并处理写入gw_hitch_error_mothod表"""
    try:
        data = request.json or {}
        
        # 获取必要参数
        credential_id = data.get('credential_id')
        topic_id = data.get('topic_id')
        query = data.get('query', '*')
        
        if not credential_id or not topic_id:
            return jsonify({'error': '缺少credential_id或topic_id参数'}), 400
        
        # 导入CLS API调用函数
        import os
        import sys
        import time
        sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
        from app import call_cls_api
        
        # 获取Topic信息
        from config.database import get_db_connection, dict_cursor, get_placeholder
        conn = get_db_connection()
        cursor = dict_cursor(conn)
        ph = get_placeholder()
        
        cursor.execute(f'SELECT region FROM log_topics WHERE topic_id = {ph}', (topic_id,))
        topic_info = cursor.fetchone()
        conn.close()
        
        region = None
        if topic_info:
            topic_info = dict(topic_info) if not isinstance(topic_info, dict) else topic_info
            region = topic_info.get('region')
        
        # 计算时间范围
        now = int(time.time() * 1000)
        from_time = data.get('from_time', now - 3600000)  # 默认1小时
        to_time = data.get('to_time', now)
        limit = data.get('limit', 100)
        
        # 调用CLS API
        cls_response = call_cls_api(
            credential_id=credential_id,
            topic_id=topic_id,
            query=query,
            from_time=from_time,
            to_time=to_time,
            limit=limit,
            region=region
        )
        
        # 处理响应数据
        processor = get_gw_processor()
        # 清除缓存以确保使用最新的映射规则
        processor.clear_config_cache()
        result = processor.process_cls_response(cls_response)
        
        return jsonify({
            'message': '采集并处理完成',
            'result': result,
            'cls_response_count': len(cls_response.get('Response', {}).get('Results', []))
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 辅助接口 ====================

@gw_hitch_bp.route('/schema', methods=['GET'])
def get_table_schema():
    """获取gw_hitch_error_mothod表结构"""
    return jsonify({
        'table_name': 'gw_hitch_error_mothod',
        'description': '网关顺风车业务错误方法聚合监控表',
        'columns': [
            {'name': 'id', 'type': 'BIGINT', 'description': '主键ID，自增'},
            {'name': 'method_name', 'type': 'VARCHAR(255)', 'description': '发生异常的接口或方法名称'},
            {'name': 'error_code', 'type': 'INT', 'description': '错误码'},
            {'name': 'error_message', 'type': 'VARCHAR(1024)', 'description': '错误信息'},
            {'name': 'content', 'type': 'VARCHAR(10240)', 'description': '响应内容'},
            {'name': 'count', 'type': 'INT', 'description': '单次聚合周期内的错误次数'},
            {'name': 'total_count', 'type': 'BIGINT', 'description': '历史累计错误总次数'},
            {'name': 'create_time', 'type': 'TIMESTAMP', 'description': '记录创建时间'},
            {'name': 'update_time', 'type': 'TIMESTAMP', 'description': '记录最后更新时间'}
        ],
        'field_mapping': {
            'method_name': 'path (去掉前缀，从第一个/开始)',
            'error_code': 'response_body.resData.code 或 errCode',
            'error_message': 'response_body.resData.message 或 errMsg',
            'content': 'response_body原始值（截取前10240字符）',
            'count': '单次聚合周期内的错误次数',
            'total_count': '历史累计错误总次数',
            'create_time': '记录创建时间',
            'update_time': '记录最后更新时间'
        }
    })


@gw_hitch_bp.route('/transform-rules', methods=['GET'])
def get_transform_rules():
    """获取gw_hitch_error_mothod表的详细转换规则"""
    # 默认配置 - 适配新表结构
    default_config = {
        'table_name': 'gw_hitch_error_mothod',
        'table_display_name': 'GW Hitch错误日志表',
        'description': '将GW日志数据按照特定规则转换并写入数据库，用于错误分析和统计',
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
                'source': 'path',
                'transform': 'regex:/.*:0',
                'required': False,
                'default': None,
                'description': '发生异常的接口或方法名称，从path中提取，去掉HTTP方法前缀'
            },
            {
                'name': 'error_code',
                'type': 'INT',
                'source': 'response_body',
                'transform': 'json_path:resData.code|fallback:errCode',
                'required': False,
                'default': None,
                'description': '错误码，优先从resData.code取值，若无则取外层errCode'
            },
            {
                'name': 'error_message',
                'type': 'VARCHAR(1024)',
                'source': 'response_body',
                'transform': 'json_path:resData.message|fallback:errMsg',
                'required': False,
                'default': None,
                'description': '错误信息，优先从resData.message取值，若无则取外层errMsg'
            },
            {
                'name': 'content',
                'type': 'VARCHAR(10240)',
                'source': 'response_body',
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
                'description': '历史累计错误总次数'
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
                    'path': 'POST /hitchride/order/addition',
                    'response_body': '{"errCode":0,"errMsg":"success","resData":{"code":37,"message":"出发时间太近，追单失败"}}'
                },
                'output': {
                    'method_name': '/hitchride/order/addition',
                    'error_code': 37,
                    'error_message': '出发时间太近，追单失败',
                    'count': 1,
                    'total_count': 1
                }
            },
            {
                'input': {
                    'path': 'GET /api/user/info',
                    'response_body': '{"errCode":500,"errMsg":"系统错误"}'
                },
                'output': {
                    'method_name': '/api/user/info',
                    'error_code': 500,
                    'error_message': '系统错误',
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
        ''', ('gw_hitch_error_mothod',))
        
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


@gw_hitch_bp.route('/processor-types', methods=['GET'])
def get_processor_types():
    """获取支持的处理器类型"""
    return jsonify({
        'types': [
            {
                'value': 'gw_hitch_error',
                'label': 'GW Hitch错误日志处理器',
                'description': '将GW日志按照特定规则转换并写入gw_hitch_error_mothod表',
                'target_table': 'gw_hitch_error_mothod'
            }
        ]
    })


@gw_hitch_bp.route('/clear-cache', methods=['POST'])
def clear_config_cache():
    """清除映射规则缓存，用于在映射规则更新后重新加载"""
    try:
        processor = get_gw_processor()
        processor.clear_config_cache()
        return jsonify({'message': '缓存已清除'})
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@gw_hitch_bp.route('/transform-test', methods=['POST'])
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
        table_name = 'gw_hitch_error_mothod'
        
        from config.database import get_db_type, MYSQL_CONFIG
        import os
        
        # 使用统一的转换工具函数
        try:
            from services.transform_utils import transform_for_table
            db_config = MYSQL_CONFIG if get_db_type() == 'mysql' else os.path.join(os.path.dirname(os.path.dirname(__file__)), 'data.db')
            result = transform_for_table(table_name, log_data, db_config)
        except ValueError:
            # 没有映射配置，使用GwHitchProcessor的默认转换逻辑
            processor = get_gw_processor()
            processor.clear_config_cache()
            result = processor.transform_log(log_data)
        
        return jsonify({
            'success': True,
            'result': result
        })
    except Exception as e:
        import traceback
        return jsonify({'error': str(e), 'traceback': traceback.format_exc()}), 500
