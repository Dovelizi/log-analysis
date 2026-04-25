package com.loganalysis.hitch.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.loganalysis.common.util.JsonUtil;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存服务。
 *
 * 对齐 services/redis_cache_service.py：
 *   缓存 key 格式：tableName_&lt;排序后字段值 1&gt;_&lt;排序后字段值 2&gt;_...
 *   过期时间：到当天 23:59:59，至少 1 秒
 *
 * P2c 起：底层从 Spring Data Redis（StringRedisTemplate）切换到 Redisson（RBucket&lt;String&gt;）。
 * 保留 {@link #isAvailable()} 降级逻辑：Redis 不可用时所有读写操作返回 null/false，
 * 5 个 Processor 会退化到"每次查 MySQL"的行为（性能退化但业务不中断）。
 */
@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    @Autowired
    private RedissonClient redissonClient;

    /** Redis 连通性检查，失败时所有 get/set/delete 直接返回空/false 实现降级 */
    public boolean isAvailable() {
        try {
            // 用一个轻量探测 key 的 isExists 作为连通性判断；任何异常视为不可用。
            // 对齐原行为：只要能打通 Redis 连接就返回 true，不关心 key 是否存在。
            redissonClient.getBucket("__loganalysis_health__").isExists();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 缓存过期秒数：到当天 23:59:59，至少 1 秒 */
    public static long expireSecondsUntilEndOfDay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endOfDay = now.toLocalDate().atTime(LocalTime.of(23, 59, 59));
        long seconds = Duration.between(now, endOfDay).getSeconds();
        return Math.max(seconds, 1L);
    }

    /** 按字段名排序拼接 key，对齐 Python _build_cache_key */
    public String buildCacheKey(String tableName, Map<String, Object> uniqueFields) {
        StringBuilder sb = new StringBuilder(tableName);
        // 排序保证 key 一致性（TreeMap 按 key 自然序）
        TreeMap<String, Object> sorted = new TreeMap<>(uniqueFields);
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            sb.append('_').append(e.getValue() == null ? "" : e.getValue().toString());
        }
        return sb.toString();
    }

    /**
     * 读取缓存，Redis 不可用时直接返回 null（触发 5 个 Processor 的 MySQL 降级路径）。
     */
    public Map<String, Object> get(String tableName, Map<String, Object> uniqueFields) {
        if (!isAvailable()) return null;
        try {
            String key = buildCacheKey(tableName, uniqueFields);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String cached = bucket.get();
            if (cached == null) return null;
            return JsonUtil.mapper().readValue(cached, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Redis 读取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入缓存，带 TTL。Redis 不可用时返回 false 但不抛异常。
     */
    public boolean set(String tableName, Map<String, Object> data, Map<String, Object> uniqueFields) {
        if (!isAvailable()) return false;
        try {
            String key = buildCacheKey(tableName, uniqueFields);
            String val = JsonUtil.toJson(data);
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(val, expireSecondsUntilEndOfDay(), TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.warn("Redis 写入失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean delete(String tableName, Map<String, Object> uniqueFields) {
        if (!isAvailable()) return false;
        try {
            String key = buildCacheKey(tableName, uniqueFields);
            return redissonClient.getBucket(key).delete();
        } catch (Exception e) {
            return false;
        }
    }
}
