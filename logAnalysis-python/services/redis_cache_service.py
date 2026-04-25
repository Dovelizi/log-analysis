# -*- coding: utf-8 -*-
"""
Redis缓存服务
用于缓存错误日志聚合数据，提升入库性能
"""
import json
import redis
from typing import Dict, Any, Optional, Tuple, List
from datetime import datetime, timedelta

from config.redis_config import (
    REDIS_CONFIG,
    get_expire_seconds_until_end_of_day
)


class RedisCacheService:
    """Redis缓存服务类"""
    
    _instance = None
    _redis_client = None
    
    def __new__(cls):
        """单例模式"""
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def __init__(self):
        """初始化Redis连接"""
        # 初始化DB0连接（错误日志聚合）
        if self._redis_client is None:
            try:
                self._redis_client = redis.Redis(**REDIS_CONFIG)
                # 测试连接
                self._redis_client.ping()
            except Exception as e:
                print(f"Redis DB0连接失败: {e}")
                self._redis_client = None
    
    def is_available(self) -> bool:
        """检查Redis是否可用"""
        if self._redis_client is None:
            return False
        try:
            self._redis_client.ping()
            return True
        except Exception:
            return False
    
    def _build_cache_key(self, table_name: str, **unique_fields) -> str:
        """
        构建缓存key
        格式: 表名_字段1值_字段2值_...
        
        Args:
            table_name: 表名
            **unique_fields: 唯一性字段及其值
        
        Returns:
            缓存key字符串
        """
        parts = [table_name]
        # 按字段名排序确保key一致性
        for field_name in sorted(unique_fields.keys()):
            value = unique_fields[field_name]
            # 将None转为空字符串，其他值转为字符串
            parts.append(str(value) if value is not None else '')
        return '_'.join(parts)
    
    def get_cached_data(self, table_name: str, **unique_fields) -> Optional[Dict]:
        """
        从缓存获取数据
        
        Args:
            table_name: 表名
            **unique_fields: 唯一性字段及其值
        
        Returns:
            缓存的数据字典，不存在则返回None
        """
        if not self.is_available():
            return None
        
        try:
            cache_key = self._build_cache_key(table_name, **unique_fields)
            cached = self._redis_client.get(cache_key)
            if cached:
                return json.loads(cached)
            return None
        except Exception as e:
            print(f"Redis读取失败: {e}")
            return None
    
    def set_cached_data(self, table_name: str, data: Dict, **unique_fields) -> bool:
        """
        设置缓存数据
        
        Args:
            table_name: 表名
            data: 要缓存的数据字典（包含id, count, total_count等）
            **unique_fields: 唯一性字段及其值
        
        Returns:
            是否设置成功
        """
        if not self.is_available():
            return False
        
        try:
            cache_key = self._build_cache_key(table_name, **unique_fields)
            # 序列化数据
            cache_data = json.dumps(data, ensure_ascii=False, default=str)
            # 设置缓存，过期时间到当天23:59:59
            expire_seconds = get_expire_seconds_until_end_of_day()
            self._redis_client.setex(cache_key, expire_seconds, cache_data)
            return True
        except Exception as e:
            print(f"Redis写入失败: {e}")
            return False
    
    def update_cached_count(self, table_name: str, count: int, add_count: int, **unique_fields) -> Tuple[bool, Optional[Dict]]:
        """
        更新缓存中的count和total_count
        
        Args:
            table_name: 表名
            count: 新的count值（直接覆盖）
            add_count: 要累加到total_count的值
            **unique_fields: 唯一性字段及其值
        
        Returns:
            (是否成功, 更新后的数据字典)
        """
        if not self.is_available():
            return False, None
        
        try:
            cache_key = self._build_cache_key(table_name, **unique_fields)
            cached = self._redis_client.get(cache_key)
            
            if cached:
                data = json.loads(cached)
                # 更新count（直接覆盖）
                data['count'] = count
                # 累加total_count
                data['total_count'] = (data.get('total_count') or 0) + add_count
                # 更新时间
                data['update_time'] = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                
                # 写回缓存，过期时间到当天23:59:59
                cache_data = json.dumps(data, ensure_ascii=False, default=str)
                expire_seconds = get_expire_seconds_until_end_of_day()
                self._redis_client.setex(cache_key, expire_seconds, cache_data)
                return True, data
            
            return False, None
        except Exception as e:
            print(f"Redis更新失败: {e}")
            return False, None
    
    def delete_cached_data(self, table_name: str, **unique_fields) -> bool:
        """
        删除缓存数据
        
        Args:
            table_name: 表名
            **unique_fields: 唯一性字段及其值
        
        Returns:
            是否删除成功
        """
        if not self.is_available():
            return False
        
        try:
            cache_key = self._build_cache_key(table_name, **unique_fields)
            self._redis_client.delete(cache_key)
            return True
        except Exception as e:
            print(f"Redis删除失败: {e}")
            return False


# 全局单例
redis_cache = RedisCacheService()
