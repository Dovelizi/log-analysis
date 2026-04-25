package com.loganalysis.service;

import com.loganalysis.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * query_configs 表 CRUD。
 *
 * 对齐原 app.py：
 *   GET    /api/query-configs
 *   POST   /api/query-configs
 *   PUT    /api/query-configs/<id>
 *   DELETE /api/query-configs/<id>
 *
 * 列：
 *   id, name, topic_id, query_statement, time_range, limit_count, sort_order,
 *   syntax_rule, processor_type, target_table, transform_config(JSON),
 *   filter_config(JSON), schedule_enabled, schedule_interval, created_at, updated_at
 */
@Service
public class QueryConfigService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT q.*, t.topic_id as cls_topic_id, t.topic_name, c.name as credential_name, c.region " +
                "FROM query_configs q JOIN log_topics t ON q.topic_id = t.id " +
                "JOIN api_credentials c ON t.credential_id = c.id");
        for (Map<String, Object> r : rows) {
            normalizeTimestamps(r);
            parseJsonColumn(r, "transform_config");
            parseJsonColumn(r, "filter_config");
        }
        return rows;
    }

    @Transactional
    public void create(Map<String, Object> data) {
        String name = str(data.get("name"));
        Long topicId = toLong(data.get("topic_id"));
        String queryStatement = str(data.get("query_statement"));
        if (isEmpty(name) || topicId == null || isEmpty(queryStatement)) {
            throw new IllegalArgumentException("缺少必要参数");
        }
        jdbcTemplate.update(
                "INSERT INTO query_configs " +
                "(name, topic_id, query_statement, time_range, limit_count, sort_order, syntax_rule, " +
                " processor_type, target_table, transform_config, filter_config, schedule_enabled, schedule_interval) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                name,
                topicId,
                queryStatement,
                toIntDefault(data.get("time_range"), 3600),
                toIntDefault(data.get("limit_count"), 100),
                strDefault(data.get("sort_order"), "desc"),
                toIntDefault(data.get("syntax_rule"), 1),
                str(data.get("processor_type")),
                str(data.get("target_table")),
                jsonCell(data.get("transform_config")),
                jsonCell(data.get("filter_config")),
                toIntDefault(data.get("schedule_enabled"), 0),
                toIntDefault(data.get("schedule_interval"), 300));
    }

    @Transactional
    public void update(long id, Map<String, Object> data) {
        jdbcTemplate.update(
                "UPDATE query_configs SET " +
                "name = ?, query_statement = ?, time_range = ?, limit_count = ?, sort_order = ?, " +
                "syntax_rule = ?, processor_type = ?, target_table = ?, transform_config = ?, " +
                "filter_config = ?, schedule_enabled = ?, schedule_interval = ? WHERE id = ?",
                str(data.get("name")),
                str(data.get("query_statement")),
                toIntDefault(data.get("time_range"), 3600),
                toIntDefault(data.get("limit_count"), 100),
                strDefault(data.get("sort_order"), "desc"),
                toIntDefault(data.get("syntax_rule"), 1),
                str(data.get("processor_type")),
                str(data.get("target_table")),
                jsonCell(data.get("transform_config")),
                jsonCell(data.get("filter_config")),
                toIntDefault(data.get("schedule_enabled"), 0),
                toIntDefault(data.get("schedule_interval"), 300),
                id);
    }

    @Transactional
    public void delete(long id) {
        // 对齐原 app.py delete_query_config：级联删除关联记录
        jdbcTemplate.update("DELETE FROM log_records WHERE query_config_id = ?", id);
        jdbcTemplate.update("DELETE FROM analysis_results WHERE query_config_id = ?", id);
        jdbcTemplate.update("DELETE FROM query_configs WHERE id = ?", id);
    }

    public Map<String, Object> findWithTopic(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT q.*, t.topic_id as cls_topic_id, t.credential_id, t.region " +
                "FROM query_configs q JOIN log_topics t ON q.topic_id = t.id WHERE q.id = ?",
                id);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        parseJsonColumn(r, "transform_config");
        parseJsonColumn(r, "filter_config");
        return r;
    }

    private static void normalizeTimestamps(Map<String, Object> m) {
        for (String k : new String[]{"created_at", "updated_at"}) {
            Object v = m.get(k);
            if (v instanceof LocalDateTime) m.put(k, v.toString());
            else if (v instanceof java.sql.Timestamp) m.put(k, ((java.sql.Timestamp) v).toLocalDateTime().toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void parseJsonColumn(Map<String, Object> row, String col) {
        Object v = row.get(col);
        if (v instanceof String) {
            Map<String, Object> parsed = JsonUtil.toMap((String) v);
            if (parsed != null) row.put(col, parsed);
        }
    }

    /** 接收 Map/List（前端 JSON）或字符串，写入时统一成字符串 */
    private static String jsonCell(Object v) {
        if (v == null) return null;
        if (v instanceof String) return ((String) v).isEmpty() ? null : (String) v;
        return JsonUtil.toJson(v);
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static String strDefault(Object v, String def) {
        String s = str(v);
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static int toIntDefault(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }

    private static Long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return null;
    }
}
