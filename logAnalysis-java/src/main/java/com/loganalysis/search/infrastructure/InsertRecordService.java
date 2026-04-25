package com.loganalysis.search.infrastructure;

import com.loganalysis.search.infrastructure.persistence.mapper.LogRecordMapper;
import com.loganalysis.search.infrastructure.persistence.po.LogRecordPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 入库记录服务，对齐 services/insert_record_service.py。
 * 把 5 张专用表的入库动作记录到 hitch_error_log_insert_record。
 *
 * P2b-3 起：底层走 MyBatis-Plus {@link LogRecordMapper}。
 * 方法签名和错误处理语义保持与迁移前完全一致。
 */
@Service
public class InsertRecordService {

    private static final Logger log = LoggerFactory.getLogger(InsertRecordService.class);

    public static final int LOG_FROM_CONTROL_HITCH = 1;
    public static final int LOG_FROM_GW_HITCH = 2;
    public static final int LOG_FROM_SUPPLIER_SP = 3;
    public static final int LOG_FROM_SUPPLIER_TOTAL = 4;
    public static final int LOG_FROM_COST_TIME = 5;

    /** content 字段超过此长度时截断，避免 VARCHAR(10240) 截断异常 */
    private static final int CONTENT_MAX = 10_000;

    @Autowired
    private LogRecordMapper logRecordMapper;

    public boolean record(int logFrom, String methodName, String content, int count, int spId) {
        try {
            LogRecordPO po = new LogRecordPO();
            po.setLogFrom(logFrom);
            po.setSpId(spId);
            po.setMethodName(methodName);
            po.setContent(truncate(content));
            po.setCount(count);
            logRecordMapper.insert(po);
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
                LogRecordPO po = new LogRecordPO();
                po.setLogFrom(logFrom);
                po.setSpId(intValue(r.get("sp_id"), 0));
                po.setMethodName(str(r.get("method_name")));
                po.setContent(truncate(str(r.get("content"))));
                po.setCount(intValue(r.get("count"), 1));
                logRecordMapper.insert(po);
            }
            return true;
        } catch (Exception e) {
            log.warn("[InsertRecordService] 批量记录入库日志失败: {}", e.getMessage());
            return false;
        }
    }

    private static String truncate(String s) {
        return (s != null && s.length() > CONTENT_MAX) ? s.substring(0, CONTENT_MAX) : s;
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
