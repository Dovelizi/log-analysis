package com.loganalysis.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口
 * 对齐原 app.py /api/health：检查数据库、Redis 连通性，返回 200/503。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "healthy");
        body.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        body.put("version", "1.0.0");

        Map<String, String> checks = new HashMap<>();
        body.put("checks", checks);
        boolean healthy = true;

        // 数据库
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            checks.put("database", one != null && one == 1 ? "ok" : "error: unexpected result");
        } catch (Exception e) {
            checks.put("database", "error: " + e.getMessage());
            healthy = false;
        }

        // Redis（失败不拉垮整体，与原行为对齐）
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            checks.put("redis", "PONG".equalsIgnoreCase(pong) ? "ok" : "error: " + pong);
        } catch (Exception e) {
            checks.put("redis", "error: " + e.getMessage());
        }

        if (!healthy) {
            body.put("status", "unhealthy");
            return ResponseEntity.status(503).body(body);
        }
        return ResponseEntity.ok(body);
    }
}
