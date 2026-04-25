package com.loganalysis.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置。
 *
 * 覆盖 RedissonAutoConfiguration 默认行为：
 *   - 当 spring.redis.password 为 null / 空字符串 / 仅空白时，不发 AUTH 命令
 *     （Redisson 默认无脑发 AUTH，对无密码 Redis 实例会报
 *      "ERR AUTH called without any password configured"）
 *
 * P2c 起替换 Spring Data Redis Lettuce 客户端，由本 bean 接管 Redis 访问。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.password:}")
    private String password;

    @Value("${spring.redis.database:0}")
    private int database;

    @Value("${spring.redis.timeout:5000}")
    private String timeout;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        int timeoutMs = parseTimeoutMs(timeout);
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setPassword(blankToNull(password))
                .setConnectTimeout(timeoutMs)
                .setTimeout(timeoutMs);
        return Redisson.create(config);
    }

    /** 把空白字符串转 null，避免 Redisson 对无密码实例发 AUTH */
    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }

    /**
     * 解析 spring.redis.timeout，支持纯数字（毫秒）和 "5000ms" / "5s" 等 Spring Duration 格式。
     */
    private static int parseTimeoutMs(String t) {
        if (t == null || t.isEmpty()) return 5000;
        String s = t.trim().toLowerCase();
        try {
            if (s.endsWith("ms")) return Integer.parseInt(s.substring(0, s.length() - 2).trim());
            if (s.endsWith("s")) return Integer.parseInt(s.substring(0, s.length() - 1).trim()) * 1000;
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 5000;
        }
    }
}
