package com.loganalysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 推送编排服务，对齐 Python report_routes.trigger_push：
 *   1. 根据 config_id 查 push_config
 *   2. 拉 summary 数据
 *   3. 写 pending 的 push_log
 *   4. 调 WecomPushService.pushToWecom
 *   5. 回写 success/failed 状态
 */
@Service
public class ReportPushService {

    private static final Logger log = LoggerFactory.getLogger(ReportPushService.class);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private ReportPushConfigService pushConfigService;

    @Autowired
    private ReportSummaryService summaryService;

    @Autowired
    private WecomPushService wecomService;

    @Autowired
    private JdbcTemplate jdbc;

    /** 推送执行结果 */
    public static class TriggerResult {
        public final boolean success;
        public final String message;
        public final String responseText;
        public final long logId;
        public final int httpStatus;
        public TriggerResult(boolean success, String message, String responseText, long logId, int httpStatus) {
            this.success = success;
            this.message = message;
            this.responseText = responseText;
            this.logId = logId;
            this.httpStatus = httpStatus;
        }
    }

    public TriggerResult trigger(long configId, String date, String imageBase64) {
        String targetDate = (date == null || date.isEmpty()) ? LocalDate.now().format(D) : date;

        Map<String, Object> config = pushConfigService.findById(configId);
        if (config == null) {
            return new TriggerResult(false, "推送配置不存在", null, 0L, 404);
        }
        String pushType = String.valueOf(config.getOrDefault("push_type", ""));
        String webhookUrl = (String) config.get("webhook_url");
        String configName = String.valueOf(config.getOrDefault("name", ""));

        if ("email".equals(pushType)) {
            return new TriggerResult(false, "邮件推送功能暂未实现", null, 0L, 501);
        }
        if (!"wecom".equals(pushType)) {
            return new TriggerResult(false, "不支持的推送类型: " + pushType, null, 0L, 400);
        }
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return new TriggerResult(false, "企微Webhook地址未配置", null, 0L, 400);
        }

        Map<String, Object> reportData = summaryService.summary(targetDate);

        String pushMode = (imageBase64 != null && !imageBase64.isEmpty()) ? "image" : "markdown";

        // 写 pending
        long logId = insertPendingLog(configId, configName, pushMode, targetDate, webhookUrl, imageBase64);

        WecomPushService.PushResult result = wecomService.pushToWecom(webhookUrl, reportData, imageBase64);

        LocalDateTime now = LocalDateTime.now();
        if (result.success) {
            jdbc.update(
                    "UPDATE report_push_log SET status = ?, response_text = ?, update_time = ? WHERE id = ?",
                    "success", result.responseText, now, logId);
            jdbc.update("UPDATE report_push_config SET last_push_time = ? WHERE id = ?", now, configId);
            String msg = "推送成功（" + (pushMode.equals("image") ? "图片" : "Markdown") + "模式）";
            return new TriggerResult(true, msg, result.responseText, logId, 200);
        } else {
            jdbc.update(
                    "UPDATE report_push_log SET status = ?, error_message = ?, update_time = ? WHERE id = ?",
                    "failed", result.errorMessage, now, logId);
            return new TriggerResult(false, "推送失败: " + result.errorMessage,
                    result.responseText, logId, 500);
        }
    }

    private long insertPendingLog(long configId, String configName, String pushMode,
                                  String reportDate, String webhookUrl, String imageBase64) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO report_push_log " +
                    "(config_id, config_name, push_type, push_mode, report_date, status, webhook_url, image_data) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, configId);
            ps.setString(2, configName);
            ps.setString(3, "wecom");
            ps.setString(4, pushMode);
            ps.setString(5, reportDate);
            ps.setString(6, "pending");
            ps.setString(7, webhookUrl);
            ps.setString(8, imageBase64);
            return ps;
        }, kh);
        Number k = kh.getKey();
        return k == null ? 0L : k.longValue();
    }

    // ==================== push-logs 查询 ====================

    public Map<String, Object> listLogs(int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM report_push_log", Long.class);
        // 不返回 image_data 字段（对齐 Python 原实现）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, config_id, config_name, push_type, push_mode, report_date, " +
                "status, webhook_url, response_text, error_message, create_time " +
                "FROM report_push_log ORDER BY create_time DESC LIMIT ? OFFSET ?",
                pageSize, offset);
        for (Map<String, Object> r : rows) normalizeTimes(r);

        // 对齐 Python: 返回 {data, pagination}
        long totalVal = total == null ? 0L : total;
        long totalPages = totalVal == 0 ? 1L : (totalVal + pageSize - 1) / pageSize;
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("current_page", page);
        pagination.put("page_size", pageSize);
        pagination.put("total", totalVal);
        pagination.put("total_pages", totalPages);
        pagination.put("has_next", page < totalPages);
        pagination.put("has_prev", page > 1);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("data", rows);
        ret.put("pagination", pagination);
        return ret;
    }

    public Map<String, Object> getLogDetail(long logId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM report_push_log WHERE id = ?", logId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        normalizeTimes(r);
        return r;
    }

    private static void normalizeTimes(Map<String, Object> r) {
        for (String k : new String[]{"create_time", "update_time"}) {
            Object v = r.get(k);
            if (v instanceof LocalDateTime) r.put(k, v.toString());
            else if (v instanceof java.sql.Timestamp) r.put(k, ((java.sql.Timestamp) v).toLocalDateTime().toString());
        }
    }
}
