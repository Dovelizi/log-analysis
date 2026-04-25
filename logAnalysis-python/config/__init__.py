# -*- coding: utf-8 -*-
"""配置模块"""
from .database import (
    DB_TYPE, MYSQL_CONFIG,
    get_db_connection, get_db_type, dict_cursor,
    row_to_dict, get_placeholder, get_auto_increment,
    get_timestamp_default
)

__all__ = [
    'DB_TYPE', 'MYSQL_CONFIG',
    'get_db_connection', 'get_db_type', 'dict_cursor',
    'row_to_dict', 'get_placeholder', 'get_auto_increment',
    'get_timestamp_default'
]
