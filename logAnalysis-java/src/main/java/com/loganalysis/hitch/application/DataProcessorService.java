package com.loganalysis.hitch.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.loganalysis.common.util.FilterEvaluator;
import com.loganalysis.common.util.JsonUtil;
import com.loganalysis.common.util.TransformUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 通用数据处理服务，对齐 Python services/data_processor.py DataProcessorService。
 * 当 target_table 有 topic_table_mappings 配置但不是专用表时使用。
 *
 * 核心职责：
 *   1. 读取 mapping 的 field_config + field_mappings
 *   2. 合并查询级 query_transform_config
 *   3. 每条日志 TransformUtils.transformLogToRow → FilterEvaluator 过滤 → INSERT 到 target_table
 *   4. 记 collection_logs
 */
@Service
public class DataProcessorService {

    private static final Logger log = LoggerFactory.getLogger(DataProcessorService.class);

    @Autowired
    private JdbcTemplate jdbc;

    /** 处理 CLS 响应 */
    @Transactional
    public Map<String, Object> processCls(long mappingId, Map<String, Object> clsResponse,
                                          Map<String, Map<String, Object>> queryTransformConfig,
                                          Map<String, Object> queryFilterConfig) {
        if (!(clsResponse.get("Response") instanceof Map)) {
            throw new IllegalArgumentException("无效的CLS响应格式");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) clsResponse.get("Response");
        if (response.get("Error") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> err = (Map<String, Object>) response.get("Error");
            throw new IllegalArgumentException("CLS API错误: " + err.getOrDefault("Message", "未知错误"));
        }
        Object resultsObj = response.get("Results");
        if (!(resultsObj instanceof List)) return empty(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resultsObj;
        if (results.isEmpty()) return empty(0);

        List<Map<String, Object>> logDataList = new ArrayList<>(results.size());
        for (Map<String, Object> r : results) {
            Object lj = r.get("LogJson");
            if (lj instanceof String && !((String) lj).isEmpty()) {
                Map<String, Object> parsed = JsonUtil.toMap((String) lj);
                if (parsed != null) {
                    // 对齐 Python：若有 Time，作为 _timestamp 注入
                    Object t = r.get("Time");
                    if (t != null) parsed.put("_timestamp", t);
                    logDataList.add(parsed);
                }
            } else {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("raw", String.valueOf(r.getOrDefault("RawLog", "")));
                logDataList.add(raw);
            }
        }
        return processLogData(mappingId, logDataList, queryTransformConfig, queryFilterConfig);
    }

    /** 处理预先解析好的日志数据 */
    @Transactional
    public Map<String, Object> processLogData(long mappingId, List<Map<String, Object>> logDataList,
                                               Map<String, Map<String, Object>> queryTransformConfig,
                                               Map<String, Object> queryFilterConfig) {
        // 1. 查 mapping
        List<Map<String, Object>> mrows = jdbc.queryForList(
                "SELECT m.*, t.topic_id as cls_topic_id FROM topic_table_mappings m " +
                "JOIN log_topics t ON m.topic_id = t.id WHERE m.id = ?", mappingId);
        if (mrows.isEmpty()) {
            throw new IllegalArgumentException("映射配置不存在: " + mappingId);
        }
        Map<String, Object> mapping = mrows.get(0);
        String tableName = String.valueOf(mapping.get("table_name"));
        List<Map<String, Object>> fieldConfig = parseFieldConfig(mapping.get("field_config"));

        // 2. 决定 filter_config：queryFilterConfig 优先，否则用 mapping.filter_config
        Map<String, Object> effectiveFilter = queryFilterConfig;
        if (effectiveFilter == null) {
            Object fc = mapping.get("filter_config");
            if (fc instanceof String && !((String) fc).isEmpty()) {
                effectiveFilter = JsonUtil.toMap((String) fc);
            }
        }

        // 3. 读 field_mappings
        List<Map<String, Object>> fmList = jdbc.queryForList(
                "SELECT * FROM field_mappings WHERE mapping_id = ?", mappingId);
        Map<String, Map<String, Object>> fieldMap = new HashMap<>();
        for (Map<String, Object> f : fmList) {
            Object tc = f.get("target_column");
            if (tc != null) fieldMap.put(String.valueOf(tc), f);
        }

        // 4. 合并查询级转换规则
        if (queryTransformConfig != null) {
            for (Map.Entry<String, Map<String, Object>> e : queryTransformConfig.entrySet()) {
                Map<String, Object> existing = fieldMap.get(e.getKey());
                if (existing != null) {
                    for (Map.Entry<String, Object> kv : e.getValue().entrySet()) {
                        if (kv.getValue() != null && !"".equals(kv.getValue())) {
                            existing.put(kv.getKey(), kv.getValue());
                        }
                    }
                } else {
                    fieldMap.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
                }
            }
        }

        // 5. 读目标表的实际列，用于过滤字段
        Set<String> actualColumns = new LinkedHashSet<>();
        try {
            jdbc.query("DESCRIBE `" + tableName + "`",
                    rs -> { actualColumns.add(rs.getString("Field")); });
        } catch (Exception e) {
            log.debug("读取表 {} 结构失败: {}", tableName, e.getMessage());
        }

        // 只保留存在于 DB 中的字段
        List<Map<String, Object>> effectiveFieldConfig = fieldConfig;
        if (!actualColumns.isEmpty()) {
            effectiveFieldConfig = new ArrayList<>();
            for (Map<String, Object> f : fieldConfig) {
                Object n = f.get("name");
                if (n != null && actualColumns.contains(String.valueOf(n))) {
                    effectiveFieldConfig.add(f);
                }
            }
        }

        // 6. 逐条转换 + 过滤 + INSERT
        int success = 0, error = 0, filtered = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        int idx = -1;
        for (Map<String, Object> logItem : logDataList) {
            idx++;
            try {
                Map<String, Object> row = TransformUtils.transformLogToRow(logItem, fieldMap, effectiveFieldConfig);
                if (!actualColumns.isEmpty()) {
                    // 最终过滤：只保留存在于 DB 中的字段
                    Map<String, Object> filteredRow = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : row.entrySet()) {
                        if (actualColumns.contains(e.getKey())) filteredRow.put(e.getKey(), e.getValue());
                    }
                    row = filteredRow;
                }
                if (effectiveFilter != null && Boolean.TRUE.equals(effectiveFilter.get("enabled"))) {
                    if (!FilterEvaluator.evaluate(row, effectiveFilter)) {
                        filtered++;
                        continue;
                    }
                }
                insertRow(tableName, row);
                success++;
            } catch (Exception e) {
                error++;
                if (errors.size() < 10) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("index", idx);
                    err.put("error", e.getMessage());
                    err.put("data", logItem);
                    errors.add(err);
                }
            }
        }

        // 7. 写 collection_logs
        try {
            LocalDateTime now = LocalDateTime.now();
            String errJson = errors.isEmpty() ? null : JsonUtil.toJson(errors);
            jdbc.update("INSERT INTO collection_logs " +
                       "(mapping_id, collected_count, success_count, error_count, error_message, started_at, finished_at) " +
                       "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    mappingId, logDataList.size(), success, error, errJson, now, now);
        } catch (Exception e) {
            log.warn("写 collection_logs 失败: {}", e.getMessage());
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("total", logDataList.size());
        ret.put("success", success);
        ret.put("filtered", filtered);
        ret.put("error", error);
        ret.put("errors", errors);
        return ret;
    }

    /** 通用 INSERT 到目标表 */
    private void insertRow(String tableName, Map<String, Object> rowData) {
        if (rowData == null || rowData.isEmpty()) return;
        List<String> columns = new ArrayList<>(rowData.keySet());
        List<String> placeholders = new ArrayList<>(columns.size());
        List<Object> values = new ArrayList<>(columns.size());
        for (String c : columns) {
            placeholders.add("?");
            Object v = rowData.get(c);
            // JSON 类型的 Map/List 先序列化为字符串
            if (v instanceof Map || v instanceof List) v = JsonUtil.toJson(v);
            values.add(v);
        }
        StringBuilder sql = new StringBuilder("INSERT INTO `").append(tableName).append("` (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("`").append(columns.get(i)).append("`");
        }
        sql.append(") VALUES (").append(String.join(", ", placeholders)).append(")");
        jdbc.update(sql.toString(), values.toArray());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFieldConfig(Object fc) {
        if (fc == null) return Collections.emptyList();
        if (fc instanceof List) return (List<Map<String, Object>>) fc;
        if (fc instanceof String) {
            try {
                return JsonUtil.mapper().readValue((String) fc,
                        new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    /** 查询统计（对齐 Python get_statistics） */
    public Map<String, Object> statistics(Long mappingId) {
        String sql = mappingId != null
                ? "SELECT COUNT(*) as total_collections, COALESCE(SUM(collected_count), 0) as total_collected, " +
                  "COALESCE(SUM(success_count), 0) as total_success, COALESCE(SUM(error_count), 0) as total_errors, " +
                  "MAX(finished_at) as last_collection FROM collection_logs WHERE mapping_id = ?"
                : "SELECT COUNT(*) as total_collections, COALESCE(SUM(collected_count), 0) as total_collected, " +
                  "COALESCE(SUM(success_count), 0) as total_success, COALESCE(SUM(error_count), 0) as total_errors, " +
                  "MAX(finished_at) as last_collection FROM collection_logs";
        return mappingId != null ? jdbc.queryForMap(sql, mappingId) : jdbc.queryForMap(sql);
    }

    private static Map<String, Object> empty(int total) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", total); r.put("success", 0); r.put("error", 0);
        r.put("filtered", 0); r.put("errors", Collections.emptyList());
        return r;
    }
}
