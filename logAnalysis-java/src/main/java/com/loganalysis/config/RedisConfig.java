package com.loganalysis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 配置
 * 对齐原 redis_config.py: decode_responses=True（即使用字符串序列化）。
 * Spring Data Redis 的 StringRedisTemplate 默认使用 StringRedisSerializer，对等。
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
