package com.loganalysis.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * log_topics 表 CRUD。
 *
 * 对齐原 app.py：
 *   GET    /api/topics              -> listAll()
 *   POST   /api/topics               -> create()
 *   PUT    /api/topics/<id>          -> update()
 *   DELETE /api/topics/<id>          -> delete()
 */
@Service
public class TopicService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.*, c.name as credential_name FROM log_topics t " +
                "JOIN api_credentials c ON t.credential_id = c.id");
        for (Map<String, Object> r : rows) normalizeTimestamps(r);
        return rows;
    }

    public long create(Map<String, Object> data) {
        Long credentialId = toLong(data.get("credential_id"));
        String topicId = str(data.get("topic_id"));
        if (credentialId == null || topicId == null || topicId.isEmpty()) {
            throw new IllegalArgumentException("缺少必要参数");
        }
        jdbcTemplate.update(
                "INSERT INTO log_topics (credential_id, region, topic_id, topic_name, description) " +
                "VALUES (?, ?, ?, ?, ?)",
                credentialId,
                strDefault(data.get("region"), "ap-guangzhou"),
                topicId,
                strDefault(data.get("topic_name"), ""),
                strDefault(data.get("description"), ""));
        return 1L;
    }

    public void update(long id, Map<String, Object> data) {
        jdbcTemplate.update(
                "UPDATE log_topics SET region = ?, topic_name = ?, description = ? WHERE id = ?",
                strDefault(data.get("region"), "ap-guangzhou"),
                strDefault(data.get("topic_name"), ""),
                strDefault(data.get("description"), ""),
                id);
    }

    public int delete(long id) {
        return jdbcTemplate.update("DELETE FROM log_topics WHERE id = ?", id);
    }

    private static Long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return null;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static String strDefault(Object v, String def) {
        String s = str(v);
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static void normalizeTimestamps(Map<String, Object> m) {
        Object v = m.get("created_at");
        if (v instanceof LocalDateTime) m.put("created_at", v.toString());
        else if (v instanceof java.sql.Timestamp) m.put("created_at", ((java.sql.Timestamp) v).toLocalDateTime().toString());
    }
}
