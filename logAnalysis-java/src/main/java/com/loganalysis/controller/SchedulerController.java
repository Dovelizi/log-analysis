package com.loganalysis.controller;

import com.loganalysis.service.ScheduledQueryRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 调度器状态查询 Controller，对齐 app.py 中：
 *   GET  /api/scheduler/status
 *   GET  /api/scheduler/push-status
 *   POST /api/scheduler/trigger/{configId}   （手动触发定时查询）
 */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    @Autowired
    private ScheduledQueryRunner runner;

    @Autowired
    private JdbcTemplate jdbc;

    @GetMapping("/status")
    public ResponseEntity<?> queryStatus() {
        Long enabledCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM query_configs WHERE schedule_enabled = 1", Long.class);
        List<Map<String, Object>> configs = jdbc.queryForList(
                "SELECT q.id, q.name, q.schedule_interval, q.time_range, q.limit_count " +
                "FROM query_configs q WHERE q.schedule_enabled = 1");

        for (Map<String, Object> c : configs) {
            Object id = c.get("id");
            long cid = id instanceof Number ? ((Number) id).longValue() : 0L;
            Long lastTs = runner.getLastExecution().get(cid);
            if (lastTs != null && lastTs > 0) {
                c.put("last_execution", LocalDateTime.ofEpochSecond(
                                lastTs, 0, java.time.ZoneId.systemDefault().getRules().getOffset(
                                        java.time.Instant.ofEpochSecond(lastTs)))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } else {
                c.put("last_execution", "尚未执行");
            }
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("running", runner.isRunning());
        ret.put("enabled_count", enabledCount == null ? 0L : enabledCount);
        ret.put("configs", configs);
        return ResponseEntity.ok(ret);
    }

    @GetMapping("/push-status")
    public ResponseEntity<?> pushStatus() {
        Long enabledCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM report_push_config WHERE schedule_enabled = 1", Long.class);
        List<Map<String, Object>> configs = jdbc.queryForList(
                "SELECT id, name, schedule_time, last_push_time FROM report_push_config WHERE schedule_enabled = 1");

        for (Map<String, Object> c : configs) {
            Object id = c.get("id");
            long cid = id instanceof Number ? ((Number) id).longValue() : 0L;
            c.put("last_execution_date", runner.getLastPushExecution().getOrDefault(cid, "尚未执行"));
            Object lp = c.get("last_push_time");
            if (lp instanceof java.sql.Timestamp) {
                c.put("last_push_time", ((java.sql.Timestamp) lp).toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("running", runner.isRunning());
        ret.put("enabled_count", enabledCount == null ? 0L : enabledCount);
        ret.put("configs", configs);
        return ResponseEntity.ok(ret);
    }

    /** 手动触发一次查询（对应 Python trigger_scheduled_query） */
    @PostMapping("/trigger/{configId}")
    public ResponseEntity<?> trigger(@PathVariable long configId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name FROM query_configs WHERE id = ?", configId);
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body(Collections.singletonMap("error", "查询配置不存在"));
        }
        String name = String.valueOf(rows.get(0).get("name"));
        // 强制重置该配置的上次执行时间为 0，让下一轮 tick 立刻触发
        runner.getLastExecution().put(configId, 0L);
        return ResponseEntity.ok(Collections.singletonMap("message", "查询 " + name + " 已安排在下次 tick 触发"));
    }
}
