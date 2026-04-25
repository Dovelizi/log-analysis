# -*- coding: utf-8 -*-
"""
Topic映射关系管理API路由
提供Topic与数据表映射的CRUD操作接口
"""
import os
import json
from flask import Blueprint, request, jsonify, g

# 创建蓝图
table_mapping_bp = Blueprint('table_mapping', __name__, url_prefix='/api/table-mappings')


def get_db_config():
    """获取数据库配置"""
    from config.database import MYSQL_CONFIG
    return MYSQL_CONFIG


def get_mapping_model():
    """获取映射模型实例"""
    if 'mapping_model' not in g:
        from models.table_mapping import TableMappingModel
        g.mapping_model = TableMappingModel(get_db_config())
    return g.mapping_model


def get_processor_service():
    """获取数据处理服务实例"""
    if 'processor_service' not in g:
        from services.data_processor import DataProcessorService
        g.processor_service = DataProcessorService(get_db_config())
    return g.processor_service


# ==================== 映射配置管理 ====================

@table_mapping_bp.route('', methods=['GET'])
def get_all_mappings():
    """获取所有映射配置"""
    try:
        model = get_mapping_model()
        mappings = model.get_all_mappings()
        return jsonify(mappings)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@table_mapping_bp.route('/<int:mapping_id>', methods=['GET'])
def get_mapping(mapping_id):
    """获取单个映射配置详情"""
    try:
        model = get_mapping_model()
        mapping = model.get_mapping(mapping_id)
        if not mapping:
            return jsonify({'error': '映射配置不存在'}), 404
        return jsonify(mapping)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@table_mapping_bp.route('/by-topic/<int:topic_id>', methods=['GET'])
def get_mappings_by_topic(topic_id):
    """根据Topic ID获取映射配置"""
    try:
        model = get_mapping_model()
        mappings = model.get_mapping_by_topic(topic_id)
        return jsonify(mappings)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@table_mapping_bp.route('', methods=['POST'])
def create_mapping():
    """创建映射配置"""
    try:
        data = request.json
        
        required_fields = ['topic_id', 'table_name', 'field_config']
        for field in required_fields:
            if not data.get(field):
                return jsonify({'error': f'缺少必要参数: {field}'}), 400
        
        model = get_mapping_model()
        mapping_id = model.create_mapping(
            topic_id=data['topic_id'],
            table_name=data['table_name'],
            field_config=data['field_config'],
            table_display_name=data.get('table_display_name'),
            description=data.get('description'),
            auto_collect=data.get('auto_collect', True),
            filter_config=data.get('filter_config')
        )
        
        return jsonify({
            'message': '创建成功',
            'mapping_id': mapping_id
        }), 201
        
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@table_mapping_bp.route('/<int:mapping_id>', methods=['PUT'])
def update_mapping(mapping_id):
    """更新映射配置"""
    try:
        data = request.json
        model = get_mapping_model()
        
        existing = model.get_mapping(mapping_id)
        if not existing:
            return jsonify({'error': '映射配置不存在'}), 404
        
        # 构建更新参数
        update_params = {
            'table_display_name': data.get('table_display_name'),
            'description': data.get('description'),
            'auto_collect': data.get('auto_collect'),
            'status': data.get('status'),
            'field_config': data.get('field_config')
        }
        
        # 处理 filter_config（入库条件配置）
        if 'filter_config' in data:
            update_params['filter_config'] = data.get('filter_config')
        
        model.update_mapping(mapping_id, **update_params)
        
        # 如果有字段配置更新，同时更新field_mappings表
        if data.get('field_config'):
            model.update_field_mappings(mapping_id, data.get('field_config'))
        
        return jsonify({'message': '更新成功'})
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@table_mapping_bp.route('/<int:mapping_id>', methods=['DELETE'])
def delete_mapping(mapping_id):
    """删除映射配置"""
    try:
        drop_table = request.args.get('drop_table', 'false').lower() == 'true'
        
        model = get_mapping_model()
        
        existing = model.get_mapping(mapping_id)
        if not existing:
            return jsonify({'error': '映射配置不存在'}), 404
        
        model.delete_mapping(mapping_id, drop_table=drop_table)
        
        return jsonify({
            'message': '删除成功',
            'table_dropped': drop_table
        })
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 字段映射管理 ====================

@table_mapping_bp.route('/<int:mapping_id>/fields', methods=['GET'])
def get_field_mappings(mapping_id):
    """获取字段映射配置"""
    try:
        model = get_mapping_model()
        fields = model.get_field_mappings(mapping_id)
        return jsonify(fields)
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 数据表操作 ====================

@table_mapping_bp.route('/<int:mapping_id>/data', methods=['GET'])
def get_table_data(mapping_id):
    """获取数据表中的数据"""
    try:
        model = get_mapping_model()
        
        mapping = model.get_mapping(mapping_id)
        if not mapping:
            return jsonify({'error': '映射配置不存在'}), 404
        
        limit = request.args.get('limit', 100, type=int)
        offset = request.args.get('offset', 0, type=int)
        order_by = request.args.get('order_by', 'id')
        order_dir = request.args.get('order_dir', 'DESC')
        
        # 从映射配置的 field_config 中提取要显示的列
        display_columns = None
        if mapping.get('field_config'):
            field_config = mapping['field_config']
            if isinstance(field_config, str):
                import json
                field_config = json.loads(field_config)
            # 提取所有配置的字段名作为显示列
            display_columns = [f['name'] for f in field_config if f.get('name')]
        
        result = model.get_table_data(
            mapping['table_name'],
            limit=limit,
            offset=offset,
            order_by=order_by,
            order_dir=order_dir,
            display_columns=display_columns
        )
        
        return jsonify(result)
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@table_mapping_bp.route('/<int:mapping_id>/schema', methods=['GET'])
def get_table_schema(mapping_id):
    """获取数据表结构"""
    try:
        model = get_mapping_model()
        
        mapping = model.get_mapping(mapping_id)
        if not mapping:
            return jsonify({'error': '映射配置不存在'}), 404
        
        schema = model.get_table_schema(mapping['table_name'])
        return jsonify(schema)
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 数据采集操作 ====================

@table_mapping_bp.route('/<int:mapping_id>/collect', methods=['POST'])
def collect_data(mapping_id):
    """手动触发数据采集"""
    try:
        data = request.json or {}
        model = get_mapping_model()
        processor = get_processor_service()
        
        mapping = model.get_mapping(mapping_id)
        if not mapping:
            return jsonify({'error': '映射配置不存在'}), 404
        
        # 导入CLS API调用函数
        import sys
        sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
        from app import call_cls_api
        
        # 获取Topic信息
        from config.database import get_db_connection, dict_cursor, get_placeholder
        conn = get_db_connection()
        cursor = dict_cursor(conn)
        ph = get_placeholder()
        
        cursor.execute(f'''
            SELECT t.*, c.id as credential_id
            FROM log_topics t
            JOIN api_credentials c ON t.credential_id = c.id
            WHERE t.id = {ph}
        ''', (mapping['topic_id'],))
        
        topic = cursor.fetchone()
        conn.close()
        
        if not topic:
            return jsonify({'error': 'Topic配置不存在'}), 404
        
        topic = dict(topic) if not isinstance(topic, dict) else topic
        
        import time
        now = int(time.time() * 1000)
        from_time = data.get('from_time', now - 3600000)
        to_time = data.get('to_time', now)
        
        cls_response = call_cls_api(
            credential_id=topic['credential_id'],
            topic_id=topic['topic_id'],
            query=data.get('query', '*'),
            from_time=from_time,
            to_time=to_time,
            limit=data.get('limit', 100),
            region=topic.get('region')
        )
        
        result = processor.process_cls_response(mapping_id, cls_response)
        
        return jsonify({
            'message': '采集完成',
            'result': result
        })
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@table_mapping_bp.route('/<int:mapping_id>/process', methods=['POST'])
def process_data(mapping_id):
    """处理并入库日志数据"""
    try:
        data = request.json
        
        if not data.get('log_data'):
            return jsonify({'error': '缺少日志数据'}), 400
        
        model = get_mapping_model()
        processor = get_processor_service()
        
        mapping = model.get_mapping(mapping_id)
        if not mapping:
            return jsonify({'error': '映射配置不存在'}), 404
        
        result = processor.process_log_data(mapping_id, data['log_data'])
        
        return jsonify({
            'message': '处理完成',
            'result': result
        })
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 采集日志 ====================

@table_mapping_bp.route('/collection-logs', methods=['GET'])
def get_collection_logs():
    """获取采集日志"""
    try:
        mapping_id = request.args.get('mapping_id', type=int)
        limit = request.args.get('limit', 20, type=int)
        offset = request.args.get('offset', 0, type=int)
        
        model = get_mapping_model()
        result = model.get_collection_logs(mapping_id=mapping_id, limit=limit, offset=offset)
        
        return jsonify(result)
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@table_mapping_bp.route('/statistics', methods=['GET'])
def get_statistics():
    """获取统计信息"""
    try:
        mapping_id = request.args.get('mapping_id', type=int)
        
        processor = get_processor_service()
        stats = processor.get_statistics(mapping_id=mapping_id)
        
        return jsonify(stats)
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ==================== 辅助接口 ====================

@table_mapping_bp.route('/field-types', methods=['GET'])
def get_field_types():
    """获取支持的字段类型"""
    return jsonify({
        'types': [
            {'value': 'TEXT', 'label': '文本', 'description': '字符串类型'},
            {'value': 'INTEGER', 'label': '整数', 'description': '整数类型'},
            {'value': 'REAL', 'label': '浮点数', 'description': '浮点数类型'},
            {'value': 'TIMESTAMP', 'label': '时间戳', 'description': '时间类型'},
            {'value': 'JSON', 'label': 'JSON', 'description': 'JSON对象类型'},
            {'value': 'BLOB', 'label': '二进制', 'description': '二进制数据'}
        ]
    })


@table_mapping_bp.route('/transform-rules', methods=['GET'])
def get_transform_rules():
    """获取支持的转换规则"""
    return jsonify({
        'rules': [
            {'value': 'trim', 'label': '去除空白', 'example': 'trim'},
            {'value': 'lower', 'label': '转小写', 'example': 'lower'},
            {'value': 'upper', 'label': '转大写', 'example': 'upper'},
            {'value': 'regex', 'label': '正则提取', 'example': 'regex:pattern:group'},
            {'value': 'split', 'label': '分割取值', 'example': 'split:,:0'},
            {'value': 'replace', 'label': '替换', 'example': 'replace:old:new'},
            {'value': 'json_path', 'label': 'JSON路径', 'example': 'json_path:data.value'}
        ]
    })


@table_mapping_bp.route('/filter-operators', methods=['GET'])
def get_filter_operators():
    """获取支持的入库过滤操作符"""
    return jsonify({
        'operators': [
            {'value': 'equals', 'label': '等于', 'description': '字段值等于指定值'},
            {'value': 'not_equals', 'label': '不等于', 'description': '字段值不等于指定值'},
            {'value': 'contains', 'label': '包含', 'description': '字段值包含指定字符串'},
            {'value': 'not_contains', 'label': '不包含', 'description': '字段值不包含指定字符串'},
            {'value': 'starts_with', 'label': '以...开头', 'description': '字段值以指定字符串开头'},
            {'value': 'ends_with', 'label': '以...结尾', 'description': '字段值以指定字符串结尾'},
            {'value': 'gt', 'label': '大于', 'description': '字段值大于指定数值'},
            {'value': 'gte', 'label': '大于等于', 'description': '字段值大于等于指定数值'},
            {'value': 'lt', 'label': '小于', 'description': '字段值小于指定数值'},
            {'value': 'lte', 'label': '小于等于', 'description': '字段值小于等于指定数值'},
            {'value': 'in', 'label': '在列表中', 'description': '字段值在指定列表中（逗号分隔）'},
            {'value': 'not_in', 'label': '不在列表中', 'description': '字段值不在指定列表中（逗号分隔）'},
            {'value': 'regex', 'label': '正则匹配', 'description': '字段值匹配指定正则表达式'},
            {'value': 'is_empty', 'label': '为空', 'description': '字段值为空'},
            {'value': 'is_not_empty', 'label': '不为空', 'description': '字段值不为空'}
        ]
    })


@table_mapping_bp.route('/transform-test', methods=['POST'])
def transform_test():
    """
    测试转换规则
    支持使用查询配置中的独立转换规则
    
    请求体:
    {
        "table_name": "目标表名",
        "log_data": {...},  // 原始日志数据
        "transform_config": {  // 可选，查询配置的转换规则
            "target_column": {
                "source_field": "来源字段",
                "transform_rule": "转换规则",
                "default_value": "默认值",
                "empty_handler": "空值处理"
            }
        }
    }
    """
    try:
        data = request.json
        table_name = data.get('table_name')
        log_data = data.get('log_data')
        transform_config = data.get('transform_config')
        
        if not table_name:
            return jsonify({'error': '缺少目标表名'}), 400
        if not log_data:
            return jsonify({'error': '缺少日志数据'}), 400
        
        # 如果log_data是字符串，尝试解析为JSON
        if isinstance(log_data, str):
            try:
                log_data = json.loads(log_data)
            except:
                return jsonify({'error': '日志数据格式错误，无法解析为JSON'}), 400
        
        db_config = get_db_config()
        
        from services.transform_utils import transform_for_table
        result = transform_for_table(table_name, log_data, db_config, query_transform_config=transform_config)
        
        return jsonify({
            'success': True,
            'result': result,
            'input': log_data
        })
        
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    except Exception as e:
        return jsonify({'error': str(e)}), 500
