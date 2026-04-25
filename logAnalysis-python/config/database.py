# -*- coding: utf-8 -*-
"""
数据库配置模块
只支持MySQL
"""
import os
import pymysql
import pymysql.cursors

# 数据库类型: 固定为 'mysql'
DB_TYPE = 'mysql'

# MySQL配置
MYSQL_CONFIG = {
    'host': os.environ.get('MYSQL_HOST', 'localhost'),
    'port': int(os.environ.get('MYSQL_PORT', 3306)),
    'user': os.environ.get('MYSQL_USER', 'root'),
    'password': os.environ.get('MYSQL_PASSWORD', '123456'),
    'database': os.environ.get('MYSQL_DATABASE', 'cls_logs'),
    'charset': 'utf8mb4',
    'autocommit': False
}


def get_db_connection():
    """获取数据库连接"""
    conn = pymysql.connect(**MYSQL_CONFIG)
    return conn


def get_db_type():
    """获取当前数据库类型"""
    return DB_TYPE


def dict_cursor(conn):
    """获取字典游标"""
    return conn.cursor(pymysql.cursors.DictCursor)


def row_to_dict(row, cursor=None):
    """将行数据转换为字典"""
    return row if isinstance(row, dict) else dict(row)


def get_placeholder():
    """获取SQL占位符"""
    return '%s'


def get_auto_increment():
    """获取自增主键语法"""
    return 'AUTO_INCREMENT'


def get_timestamp_default():
    """获取时间戳默认值语法"""
    return 'DEFAULT CURRENT_TIMESTAMP'
