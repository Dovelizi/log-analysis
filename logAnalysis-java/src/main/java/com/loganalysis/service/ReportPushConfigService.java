package com.loganalysis.service;

import com.loganalysis.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * report_push_config 表 CRUD，对齐 routes/report_routes.py push-configs 四个接口。
 *
 * push_mode 枚举: daily / date / relative
 */
@Service
public class ReportPushConfigService {

    private static final Set<String> ALLOWED_MODES = Set.of("daily", "date", "relative");

    @Autowired
    private JdbcTemplate jdbc;

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM report_push_config ORDER BY create_time DESC");
        for (Map<String, Object> r : rows) normalizeRow(r);
        return rows;
    }

    public long create(Map<String, Object> data) {
        validate(data, true);
        String pushMode = strDefault(data.get("push_mode"), "daily");
        Object pushDate = nullIfEmpty(data.get("push_date"));
        int relativeDays = toInt(data.get("relative_days"), 0);

        jdbc.update(
                "INSERT INTO report_push_config (name, push_type, webhook_url, email_config, " +
                "schedule_enabled, schedule_cron, schedule_time, push_mode, push_date, relative_days) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                str(data.get("name")),
                strDefault(data.get("push_type"), "wecom"),
                str(data.get("webhook_url")),
                jsonCell(data.get("email_config")),
                Boolean.TRUE.equals(data.get("schedule_enabled")) ? 1 : toInt(data.get("schedule_enabled"), 0),
                str(data.get("schedule_cron")),
                str(data.get("schedule_time")),
                pushMode,
                pushDate,
                relativeDays);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    public void update(long id, Map<String, Object> data) {
        validate(data, false);
        String pushMode = strDefault(data.get("push_mode"), "daily");
        Object pushDate = nullIfEmpty(data.get("push_date"));
        int relativeDays = toInt(data.get("relative_days"), 0);

        jdbc.update(
                "UPDATE report_push_config SET " +
                "name = ?, push_type = ?, webhook_url = ?, email_config = ?, " +
                "schedule_enabled = ?, schedule_cron = ?, schedule_time = ?, " +
                "push_mode = ?, push_date = ?, relative_days = ? " +
                "WHERE id = ?",
                str(data.get("name")),
                strDefault(data.get("push_type"), "wecom"),
                str(data.get("webhook_url")),
                jsonCell(data.get("email_config")),
                Boolean.TRUE.equals(data.get("schedule_enabled")) ? 1 : toInt(data.get("schedule_enabled"), 0),
                str(data.get("schedule_cron")),
                str(data.get("schedule_time")),
                pushMode,
                pushDate,
                relativeDays,
                id);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM report_push_config WHERE id = ?", id);
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM report_push_config WHERE id = ?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        normalizeRow(r);
        return r;
    }

    // ============================== 内部 ==============================

    private void validate(Map<String, Object> data, boolean forCreate) {
        if (forCreate) {
            if (isEmpty(str(data.get("name")))) {
                throw new IllegalArgumentException("缺少必要参数: name");
            }
            if (isEmpty(str(data.get("push_type")))) {
                throw new IllegalArgumentException("缺少必要参数: push_type");
            }
        }
        String pushMode = strDefault(data.get("push_mode"), "daily");
        if (!ALLOWED_MODES.contains(pushMode)) {
            throw new IllegalArgumentException("push_mode 必须是 daily/date/relative");
        }
        if ("date".equals(pushMode)) {
            Object pushDate = nullIfEmpty(data.get("push_date"));
            if (pushDate == null) throw new IllegalArgumentException("push_mode=date 时必须提供 push_date");
        }
        if ("relative".equals(pushMode)) {
            int rd = toInt(data.get("relative_days"), -1);
            if (rd < 0) throw new IllegalArgumentException("push_mode=relative 时 relative_days 必须 >= 0");
        }
    }

    private static void normalizeRow(Map<String, Object> r) {
        for (String k : new String[]{"create_time", "update_time", "last_push_time", "last_scheduled_push_time"}) {
            Object v = r.get(k);
            if (v instanceof LocalDateTime) r.put(k, v.toString());
            else if (v instanceof java.sql.Timestamp) r.put(k, ((java.sql.Timestamp) v).toLocalDateTime().toString());
        }
        Object emailConfig = r.get("email_config");
        if (emailConfig instanceof String && !((String) emailConfig).isEmpty()) {
            Map<String, Object> parsed = JsonUtil.toMap((String) emailConfig);
            if (parsed != null) r.put("email_config", parsed);
        }
    }

    private static String jsonCell(Object v) {
        if (v == null) return null;
        if (v instanceof String) return ((String) v).isEmpty() ? null : (String) v;
        return JsonUtil.toJson(v);
    }

    private static Object nullIfEmpty(Object v) {
        if (v == null) return null;
        if (v instanceof String && ((String) v).isEmpty()) return null;
        return v;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
    private static String strDefault(Object v, String def) {
        String s = str(v);
        return s == null || s.isEmpty() ? def : s;
    }
    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }
}
