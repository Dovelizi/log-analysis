package com.loganalysis.health.interfaces.rest;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * P2c 起：Redis 检查改用 Redisson 客户端；数据库检查继续用 JdbcTemplate 的 SELECT 1
 * （该场景引入 MP 无实际收益）。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedissonClient redissonClient;

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
            // Redisson 的 pingAll：所有节点都返回 PONG 时为 true；单节点模式下即为连接状态
            boolean ok = redissonClient.getNodesGroup().pingAll();
            checks.put("redis", ok ? "ok" : "error: ping failed");
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

