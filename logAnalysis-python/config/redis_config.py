# -*- coding: utf-8 -*-
"""
Redis配置模块
"""
import os
from datetime import datetime, timedelta

# Redis配置 - DB0 用于错误日志聚合缓存
REDIS_CONFIG = {
    'host': os.environ.get('REDIS_HOST', 'localhost'),
    'port': int(os.environ.get('REDIS_PORT', 6379)),
    'password': os.environ.get('REDIS_PASSWORD', None),
    'db': int(os.environ.get('REDIS_DB', 0)),
    'decode_responses': True,
    'socket_timeout': 5,
    'socket_connect_timeout': 5
}

# 缓存过期时间（24小时）- 保留用于兼容
CACHE_EXPIRE_SECONDS = 24 * 60 * 60


def get_expire_seconds_until_end_of_day() -> int:
    """
    计算从当前时间到当天23:59:59的秒数
    
    Returns:
        到当天结束的秒数
    """
    now = datetime.now()
    end_of_day = datetime(now.year, now.month, now.day, 23, 59, 59)
    delta = end_of_day - now
    return max(int(delta.total_seconds()), 1)  # 至少1秒
