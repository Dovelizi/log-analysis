package com.loganalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.loganalysis.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 数据处理器路由，对齐 app.py search_logs 中的分发逻辑。
 *
 * 分发规则（优先级从高到低，与 Python 原实现一致）：
 *   1) 专用处理器强制使用（target_table 在 SPECIALIZED_PROCESSOR_TABLES 中）
 *   2) target_table 有 topic_table_mappings 映射 → DataProcessorService（通用处理器）
 *   3) 按 processor_type 分发到 gw_hitch / control_hitch / hitch_supplier_error_sp/total / hitch_control_cost_time
 *   4) 默认 → 写 log_records 表
 */
@Service
public class DataProcessorRouter {

    private static final Logger log = LoggerFactory.getLogger(DataProcessorRouter.class);

    /** 需要专用处理器的表（对齐 Python SPECIALIZED_PROCESSOR_TABLES） */
    public static final Map<String, String> SPECIALIZED = new LinkedHashMap<>();
    static {
        SPECIALIZED.put("control_hitch_error_mothod", "control_hitch_error");
        SPECIALIZED.put("gw_hitch_error_mothod", "gw_hitch_error");
        SPECIALIZED.put("hitch_supplier_error_sp", "hitch_supplier_error_sp");
        SPECIALIZED.put("hitch_supplier_error_total", "hitch_supplier_error_total");
        SPECIALIZED.put("hitch_control_cost_time", "hitch_control_cost_time");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GwHitchProcessor gwHitchProcessor;
    @Autowired private ControlHitchProcessor controlHitchProcessor;
    @Autowired private HitchSupplierErrorSpProcessor supplierSpProcessor;
    @Autowired private HitchSupplierErrorTotalProcessor supplierTotalProcessor;
    @Autowired private HitchControlCostTimeProcessor costTimeProcessor;
    @Autowired private DataProcessorService dataProcessorService;

    /**
     * 根据 processor_type / target_table 分发 CLS 响应到对应处理器。
     *
     * @param clsResponse            CLS 原始响应
     * @param processorType          处理器类型（可选，被专用表覆盖）
     * @param targetTable            目标表名（可选）
     * @param queryTransformConfig   查询级转换规则
     * @param queryFilterConfig      查询级过滤条件
     * @param configId               query_config id（用于默认 log_records 写入）
     * @param topicId                topicId（用于默认 log_records 写入）
     * @return 处理结果（_process_result 的内容）
     */
    public Map<String, Object> dispatch(Map<String, Object> clsResponse,
                                        String processorType,
                                        String targetTable,
                                        Map<String, Map<String, Object>> queryTransformConfig,
                                        Map<String, Object> queryFilterConfig,
                                        Long configId,
                                        String topicId) {
        // 1. 专用处理器强制使用
        String effective = processorType;
        if (targetTable != null && SPECIALIZED.containsKey(targetTable)) {
            effective = SPECIALIZED.get(targetTable);
            log.debug("使用专用处理器 for {}: {}", targetTable, effective);
        }

        // 2. 通用处理器（仅当非专用表 + 有 topic_table_mappings 配置）
        if (targetTable != null && !SPECIALIZED.containsKey(targetTable)) {
            Long mappingId = findMappingId(targetTable);
            if (mappingId != null) {
                log.debug("使用通用处理器 for {}, mapping_id={}", targetTable, mappingId);
                return dataProcessorService.processCls(mappingId, clsResponse,
                        queryTransformConfig, queryFilterConfig);
            }
        }

        // 3. 按 processor_type 分发
        if (effective != null) {
            switch (effective) {
                case "gw_hitch_error":
                    return gwHitchProcessor.processClsResponse(clsResponse,
                            queryTransformConfig, queryFilterConfig);
                case "control_hitch_error":
                    return controlHitchProcessor.processClsResponse(clsResponse,
                            queryTransformConfig, queryFilterConfig);
                case "hitch_supplier_error_sp":
                    return supplierSpProcessor.processClsResponse(clsResponse,
                            queryTransformConfig, queryFilterConfig);
                case "hitch_supplier_error_total":
                    return supplierTotalProcessor.processClsResponse(clsResponse,
                            queryTransformConfig, queryFilterConfig);
                case "hitch_control_cost_time":
                    return costTimeProcessor.processClsResponse(clsResponse,
                            queryTransformConfig, queryFilterConfig);
                default:
                    // 未知 processor_type，走默认写 log_records
            }
        }

        // 4. 默认：写 log_records
        return writeLogRecords(clsResponse, configId, topicId);
    }

    /** 查找 target_table 对应的 mapping_id */
    private Long findMappingId(String targetTable) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM topic_table_mappings WHERE table_name = ?", targetTable);
            if (!rows.isEmpty()) {
                Object id = rows.get(0).get("id");
                if (id instanceof Number) return ((Number) id).longValue();
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** 默认：把每条 Result 写入 log_records 表（对齐 Python 默认分支） */
    private Map<String, Object> writeLogRecords(Map<String, Object> clsResponse, Long configId, String topicId) {
        if (!(clsResponse.get("Response") instanceof Map)) return zero();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) clsResponse.get("Response");
        Object resultsObj = response.get("Results");
        if (!(resultsObj instanceof List)) return zero();

        int success = 0;
        for (Object o : (List<?>) resultsObj) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> log = (Map<String, Object>) o;
            Object time = log.get("Time");
            String logTime = null;
            if (time instanceof Number) {
                long t = ((Number) time).longValue();
                if (t > 1_000_000_000_000L) t = t / 1000L;
                logTime = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(t), java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            try {
                jdbc.update(
                        "INSERT INTO log_records (query_config_id, topic_id, log_time, log_content, log_json, source) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                        configId, topicId, logTime,
                        String.valueOf(log.getOrDefault("RawLog", "")),
                        String.valueOf(log.getOrDefault("LogJson", "")),
                        String.valueOf(log.getOrDefault("Source", "")));
                success++;
            } catch (Exception e) {
                log.put("_insert_error", e.getMessage());
            }
        }

        // 写 analysis_results（如果有）
        Object analysis = response.get("Analysis");
        if (analysis != null && Boolean.TRUE.equals(analysis)) {
            try {
                jdbc.update(
                        "INSERT INTO analysis_results (query_config_id, analysis_type, result_data, columns) " +
                        "VALUES (?, ?, ?, ?)",
                        configId, "sql_analysis",
                        JsonUtil.toJson(response.getOrDefault("AnalysisRecords", Collections.emptyList())),
                        JsonUtil.toJson(response.getOrDefault("Columns", Collections.emptyList())));
            } catch (Exception ignore) {}
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("default_processor", true);
        ret.put("success", success);
        return ret;
    }

    private static Map<String, Object> zero() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", 0);
        return r;
    }
}
