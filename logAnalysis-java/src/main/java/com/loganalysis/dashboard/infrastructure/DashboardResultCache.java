package com.loganalysis.dashboard.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.loganalysis.common.util.JsonUtil;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Dashboard 接口结果级缓存（30 秒 TTL，per-key）。
 *
 * 设计：
 *  - 不污染 RedisCacheService（hitch 域专用）：dashboard 用独立 key 前缀 "dash:"
 *  - JSON 序列化：复用 JsonUtil
 *  - Redis 不可用时透传（不缓存，不报错）—— Redisson 已在项目中配置降级
 *  - TTL 短（30s）：业务可接受 30s 数据延迟，且当天数据持续追加，长 TTL 反而误导
 *
 * 使用模式：
 * <pre>
 *   Map&lt;String,Object&gt; data = cache.computeIfAbsent("costTime|2026-04-26|2026-04-26",
 *       () -&gt; service.costTimeStatistics(...));
 * </pre>
 */
@Component
public class DashboardResultCache {

    private static final Logger log = LoggerFactory.getLogger(DashboardResultCache.class);
    private static final String KEY_PREFIX = "dash:";
    /** 30 秒 TTL：业务可接受的数据延迟上限 */
    public static final long DEFAULT_TTL_SECONDS = 30L;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 命中则返回缓存；未命中则执行 supplier 并把结果写回缓存。
     * supplier 抛异常时直接抛出，不缓存空值/异常。
     */
    public Map<String, Object> computeIfAbsent(String logicalKey, Supplier<Map<String, Object>> supplier) {
        String key = KEY_PREFIX + logicalKey;
        // 1. 尝试读缓存
        Map<String, Object> cached = tryGet(key);
        if (cached != null) {
            return cached;
        }
        // 2. miss → 执行业务
        Map<String, Object> result = supplier.get();
        // 3. 写回缓存（失败不影响业务）
        if (result != null) {
            trySet(key, result);
        }
        return result;
    }

    private Map<String, Object> tryGet(String key) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            String s = bucket.get();
            if (s == null) return null;
            return JsonUtil.mapper().readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Dashboard cache read failed key={} err={}", key, e.getMessage());
            return null;
        }
    }

    private void trySet(String key, Map<String, Object> data) {
        try {
            String json = JsonUtil.toJson(data);
            redissonClient.<String>getBucket(key).set(json, DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Dashboard cache write failed key={} err={}", key, e.getMessage());
        }
    }
}
