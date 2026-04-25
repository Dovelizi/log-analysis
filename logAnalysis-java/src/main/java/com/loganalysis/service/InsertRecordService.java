package com.loganalysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 入库记录服务，对齐 services/insert_record_service.py。
 * 把 5 张专用表的入库动作记录到 hitch_error_log_insert_record。
 */
@Service
public class InsertRecordService {

    private static final Logger log = LoggerFactory.getLogger(InsertRecordService.class);

    public static final int LOG_FROM_CONTROL_HITCH = 1;
    public static final int LOG_FROM_GW_HITCH = 2;
    public static final int LOG_FROM_SUPPLIER_SP = 3;
    public static final int LOG_FROM_SUPPLIER_TOTAL = 4;
    public static final int LOG_FROM_COST_TIME = 5;

    private static final int CONTENT_MAX = 10_000;

    private static final String INSERT_SQL =
            "INSERT INTO `hitch_error_log_insert_record` " +
            "(`log_from`, `sp_id`, `method_name`, `content`, `count`) VALUES (?, ?, ?, ?, ?)";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public boolean record(int logFrom, String methodName, String content, int count, int spId) {
        try {
            String c = content;
            if (c != null && c.length() > CONTENT_MAX) {
                c = c.substring(0, CONTENT_MAX);
            }
            jdbcTemplate.update(INSERT_SQL, logFrom, spId, methodName, c, count);
            return true;
        } catch (Exception e) {
            log.warn("[InsertRecordService] 记录入库日志失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean record(int logFrom, String methodName, String content, int count) {
        return record(logFrom, methodName, content, count, 0);
    }

    public boolean batchRecord(int logFrom, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return true;
        try {
            for (Map<String, Object> r : records) {
                String method = str(r.get("method_name"));
                String content = str(r.get("content"));
                int count = intValue(r.get("count"), 1);
                int spId = intValue(r.get("sp_id"), 0);
                if (content != null && content.length() > CONTENT_MAX) content = content.substring(0, CONTENT_MAX);
                jdbcTemplate.update(INSERT_SQL, logFrom, spId, method, content, count);
            }
            return true;
        } catch (Exception e) {
            log.warn("[InsertRecordService] 批量记录入库日志失败: {}", e.getMessage());
            return false;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intValue(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }
}
