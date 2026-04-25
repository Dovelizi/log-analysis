"""
入库记录服务
在5张表入库时记录到 hitch_error_log_insert_record 表
"""
from datetime import datetime
from config.database import get_db_connection, get_db_type


# log_from 常量定义
LOG_FROM_CONTROL_HITCH = 1       # control_hitch_error_mothod
LOG_FROM_GW_HITCH = 2            # gw_hitch_error_mothod
LOG_FROM_SUPPLIER_SP = 3         # hitch_supplier_error_sp
LOG_FROM_SUPPLIER_TOTAL = 4      # hitch_supplier_error_total
LOG_FROM_COST_TIME = 5           # hitch_control_cost_time


def record_insert_log(log_from: int, method_name: str, content: str, count: int = 1, sp_id: int = 0):
    """
    记录入库日志到 hitch_error_log_insert_record 表
    
    Args:
        log_from: 数据来源 (1-5)
        method_name: 发生异常的接口或方法名称
        content: 原始错误上下文或日志详情
        count: 错误数量
        sp_id: 服务商ID，log_from为3或4时有值
    """
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        db_type = get_db_type()
        ph = '%s' if db_type == 'mysql' else '?'
        
        # 截断 content 避免超长
        if content and len(content) > 10000:
            content = content[:10000]
        
        if db_type == 'mysql':
            sql = f'''
                INSERT INTO `hitch_error_log_insert_record` 
                (`log_from`, `sp_id`, `method_name`, `content`, `count`)
                VALUES ({ph}, {ph}, {ph}, {ph}, {ph})
            '''
        else:
            sql = f'''
                INSERT INTO hitch_error_log_insert_record 
                (log_from, sp_id, method_name, content, count)
                VALUES ({ph}, {ph}, {ph}, {ph}, {ph})
            '''
        
        cursor.execute(sql, (log_from, sp_id, method_name, content, count))
        conn.commit()
        conn.close()
        return True
    except Exception as e:
        print(f"[InsertRecordService] 记录入库日志失败: {e}")
        return False


def batch_record_insert_logs(log_from: int, records: list):
    """
    批量记录入库日志
    
    Args:
        log_from: 数据来源 (1-5)
        records: 记录列表，每个元素为 {'method_name': str, 'content': str, 'count': int, 'sp_id': int(可选)}
    """
    if not records:
        return True
    
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        db_type = get_db_type()
        ph = '%s' if db_type == 'mysql' else '?'
        
        if db_type == 'mysql':
            sql = f'''
                INSERT INTO `hitch_error_log_insert_record` 
                (`log_from`, `sp_id`, `method_name`, `content`, `count`)
                VALUES ({ph}, {ph}, {ph}, {ph}, {ph})
            '''
        else:
            sql = f'''
                INSERT INTO hitch_error_log_insert_record 
                (log_from, sp_id, method_name, content, count)
                VALUES ({ph}, {ph}, {ph}, {ph}, {ph})
            '''
        
        for record in records:
            method_name = record.get('method_name', '')
            content = record.get('content', '')
            count = record.get('count', 1)
            sp_id = record.get('sp_id', 0)
            
            # 截断 content 避免超长
            if content and len(content) > 10000:
                content = content[:10000]
            
            cursor.execute(sql, (log_from, sp_id, method_name, content, count))
        
        conn.commit()
        conn.close()
        return True
    except Exception as e:
        print(f"[InsertRecordService] 批量记录入库日志失败: {e}")
        return False
