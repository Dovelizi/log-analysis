package com.loganalysis.hitch.infrastructure.writeback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.common.config.ClickHouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ClickHouse 异步双写组件。
 *
 * 激活条件（ConditionalOnProperty）：
 *   loganalysis.clickhouse.enabled=true 且 loganalysis.clickhouse.dual-write=true
 *
 * 使用模式：
 *   - 5 个 Processor 在 MySQL 写入成功后，调用 {@link #writeAsync(String, long, String, Map)}
 *   - 本组件把任务投递到 clickHouseAsyncExecutor 线程池异步执行
 *   - 执行失败时写入 ch_writeback_queue 补偿表，由 {@link ChWritebackRunner} 定时重放
 *
 * 重要约束：
 *   - Processor 侧用 @Autowired(required=false) 注入，CH 未启用时 dualWriter 为 null，
 *     跳过双写逻辑即可；不会影响现有 MySQL 写路径
 *   - 双写失败不影响业务流程（MySQL 是权威源）
 */
@Component
@ConditionalOnProperty(prefix = "loganalysis.clickhouse", name = {"enabled", "dual-write"}, havingValue = "true")
public class ChDualWriter {

    private static final Logger log = LoggerFactory.getLogger(ChDualWriter.class);

    /** 对齐 CH 建表 DDL 的时区，确保 DateTime 字段被正确解析 */
    private static final String TZ_SHANGHAI = "Asia/Shanghai";

    @Autowired
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    /** 主 MySQL JdbcTemplate，用于失败时写补偿队列（避免误用 CH JdbcTemplate 写 MySQL） */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("clickHouseAsyncExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    @Autowired
    private ClickHouseProperties props;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 异步写入 ClickHouse。
     *
     * @param operation  insert / update（当前 Processor 仅用 insert；update 时由补偿脚本使用）
     * @param targetId   MySQL 主键 id（CH 行 id 保持一致，便于追溯）
     * @param targetTable CH 表名
     * @param payload    字段值 Map（列名 → 值）；列名必须与 CH DDL 字段名对齐
     */
    public void writeAsync(String operation, long targetId, String targetTable, Map<String, Object> payload) {
        if (asyncExecutor == null) {
            enqueueWriteback(operation, targetId, targetTable, payload, "async-executor-unavailable");
            return;
        }
        // 按 CallerRunsPolicy 队列满时会阻塞调用方短暂跑任务
        asyncExecutor.execute(() -> {
            try {
                doWrite(operation, targetTable, payload);
            } catch (Exception e) {
                log.warn("异步写 ClickHouse 失败，进补偿队列: table={}, id={}, err={}",
                        targetTable, targetId, e.getMessage());
                enqueueWriteback(operation, targetId, targetTable, payload, e.getMessage());
            }
        });
    }

    /**
     * 同步执行写入（供补偿任务直接调用，不经过线程池）。
     */
    public void doWrite(String operation, String targetTable, Map<String, Object> payload) {
        if ("insert".equalsIgnoreCase(operation)) {
            doInsert(targetTable, payload);
        } else if ("update".equalsIgnoreCase(operation)) {
            doUpdate(targetTable, payload);
        } else {
            throw new IllegalArgumentException("未知 operation: " + operation);
        }
    }

    private void doInsert(String targetTable, Map<String, Object> payload) {
        // ClickHouse INSERT：列顺序不敏感，但值与列一一对应
        StringBuilder cols = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        Object[] args = new Object[payload.size()];
        int i = 0;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (i > 0) {
                cols.append(',');
                placeholders.append(',');
            }
            cols.append('`').append(e.getKey()).append('`');
            placeholders.append('?');
            args[i++] = e.getValue();
        }
        String sql = "INSERT INTO " + targetTable + " (" + cols + ") VALUES (" + placeholders + ")";
        clickHouseJdbcTemplate.update(sql, args);
    }

    private void doUpdate(String targetTable, Map<String, Object> payload) {
        // ClickHouse 用 ALTER TABLE ... UPDATE（ReplacingMergeTree 场景更推荐 INSERT 覆盖）
        // 当前实现：对 ReplacingMergeTree 表，"update" 也转为 INSERT 同 id 的新版本
        // （update_time 字段由 payload 携带，保证 Replacing 引擎按新时间戳去重）
        doInsert(targetTable, payload);
    }

    /** 入补偿队列（MySQL 主库）。失败时最后兜底：仅记日志，避免无限递归 */
    private void enqueueWriteback(String operation, long targetId, String targetTable,
                                  Map<String, Object> payload, String lastError) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String truncatedErr = lastError == null ? null
                    : (lastError.length() > 1000 ? lastError.substring(0, 1000) : lastError);
            jdbcTemplate.update(
                    "INSERT INTO ch_writeback_queue " +
                    "(target_table, operation, target_id, payload_json, retry_count, last_error, next_retry_at) " +
                    "VALUES (?, ?, ?, ?, 0, ?, NOW())",
                    targetTable, operation, targetId, payloadJson, truncatedErr);
        } catch (Exception e) {
            // 最后一道防线：补偿表也写不进去只能打 ERROR
            log.error("补偿队列写入失败（数据将丢失）: table={}, id={}, err={}",
                    targetTable, targetId, e.getMessage());
        }
    }

    // ============================== 便利方法：5 张 CH 表专用 ==============================

    public void writeGwHitchAsync(long id, Object methodName, Object errorCode, Object errorMessage,
                                   Object content, int count, long totalCount) {
        Map<String, Object> m = buildPayloadBase(id);
        m.put("method_name", nullSafe(methodName));
        m.put("error_code", intOrDefault(errorCode, 0));
        m.put("error_message", nullSafe(errorMessage));
        m.put("content", nullSafe(content));
        m.put("count", count);
        m.put("total_count", totalCount);
        writeAsync("insert", id, "gw_hitch_error_mothod", m);
    }

    public void writeControlHitchAsync(long id, Object methodName, Object errorCode, Object errorMessage,
                                        Object content, int count, long totalCount) {
        Map<String, Object> m = buildPayloadBase(id);
        m.put("method_name", nullSafe(methodName));
        // ControlHitch error_code 是 String（区别于 GwHitch 的 Int）
        m.put("error_code", errorCode == null ? "" : String.valueOf(errorCode));
        m.put("error_message", nullSafe(errorMessage));
        m.put("content", nullSafe(content));
        m.put("count", count);
        m.put("total_count", totalCount);
        writeAsync("insert", id, "control_hitch_error_mothod", m);
    }

    public void writeSupplierSpAsync(long id, Object spId, Object spName, Object methodName,
                                      Object content, Object errorCode, Object errorMessage,
                                      int count, long totalCount) {
        Map<String, Object> m = buildPayloadBase(id);
        m.put("sp_id", intOrDefault(spId, 0));
        m.put("sp_name", nullSafe(spName));
        m.put("method_name", nullSafe(methodName));
        m.put("content", nullSafe(content));
        m.put("error_code", intOrDefault(errorCode, 0));
        m.put("error_message", nullSafe(errorMessage));
        m.put("count", count);
        m.put("total_count", totalCount);
        writeAsync("insert", id, "hitch_supplier_error_sp", m);
    }

    public void writeSupplierTotalAsync(long id, Object spId, Object methodName,
                                         Object errorCode, Object errorMessage, Object content,
                                         int count, long totalCount) {
        Map<String, Object> m = buildPayloadBase(id);
        m.put("sp_id", intOrDefault(spId, 0));
        m.put("method_name", nullSafe(methodName));
        m.put("error_code", intOrDefault(errorCode, 0));
        m.put("error_message", nullSafe(errorMessage));
        m.put("content", nullSafe(content));
        m.put("count", count);
        m.put("total_count", totalCount);
        writeAsync("insert", id, "hitch_supplier_error_total", m);
    }

    public void writeCostTimeAsync(long id, Object traceId, Object methodName, Object content,
                                    int timeCost, Object logTime) {
        // CostTime 表无 update_time，只有 create_time
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("trace_id", nullSafe(traceId));
        m.put("method_name", nullSafe(methodName));
        m.put("content", nullSafe(content));
        m.put("time_cost", timeCost);
        m.put("log_time", nullSafe(logTime));
        m.put("create_time", now());
        writeAsync("insert", id, "hitch_control_cost_time", m);
    }

    // ============================== helpers ==============================

    /** 聚合表的通用 payload 基础：id + create_time + update_time */
    private Map<String, Object> buildPayloadBase(long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        String now = now();
        m.put("create_time", now);
        m.put("update_time", now);
        return m;
    }

    private static String now() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String nullSafe(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int intOrDefault(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }
}
