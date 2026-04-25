package com.loganalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.loganalysis.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * Redis 缓存服务
 * 对齐 services/redis_cache_service.py：
 *   缓存 key 格式：tableName_<排序后字段值 1>_<排序后字段值 2>_...
 *   过期时间：到当天 23:59:59，至少 1 秒
 */
@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    public boolean isAvailable() {
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            return false;
        }
    }

    public static long expireSecondsUntilEndOfDay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endOfDay = now.toLocalDate().atTime(LocalTime.of(23, 59, 59));
        long seconds = Duration.between(now, endOfDay).getSeconds();
        return Math.max(seconds, 1L);
    }

    /** 按字段名排序拼接 key，对齐 Python _build_cache_key */
    public String buildCacheKey(String tableName, Map<String, Object> uniqueFields) {
        StringBuilder sb = new StringBuilder(tableName);
        // 排序保证 key 一致性
        TreeMap<String, Object> sorted = new TreeMap<>(uniqueFields);
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            sb.append('_').append(e.getValue() == null ? "" : e.getValue().toString());
        }
        return sb.toString();
    }

    public Map<String, Object> get(String tableName, Map<String, Object> uniqueFields) {
        if (!isAvailable()) return null;
        try {
            String key = buildCacheKey(tableName, uniqueFields);
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null) return null;
            return JsonUtil.mapper().readValue(cached, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Redis 读取失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean set(String tableName, Map<String, Object> data, Map<String, Object> uniqueFields) {
        if (!isAvailable()) return false;
        try {
            String key = buildCacheKey(tableName, uniqueFields);
            String val = JsonUtil.toJson(data);
            redisTemplate.opsForValue().set(key, val, Duration.ofSeconds(expireSecondsUntilEndOfDay()));
            return true;
        } catch (Exception e) {
            log.warn("Redis 写入失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean delete(String tableName, Map<String, Object> uniqueFields) {
        if (!isAvailable()) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.delete(buildCacheKey(tableName, uniqueFields)));
        } catch (Exception e) {
            return false;
        }
    }
}
