package com.loganalysis.hitch.application;

import com.loganalysis.hitch.infrastructure.persistence.mapper.HitchControlCostTimeMapper;
import com.loganalysis.hitch.infrastructure.persistence.po.HitchControlCostTimePO;
import com.loganalysis.hitch.infrastructure.writeback.ChDualWriter;
import com.loganalysis.search.infrastructure.InsertRecordService;
import com.loganalysis.tablemapping.infrastructure.persistence.mapper.FieldMappingReadMapper;
import com.loganalysis.tablemapping.infrastructure.persistence.mapper.TopicTableMappingReadMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.loganalysis.common.util.FilterEvaluator;
import com.loganalysis.common.util.JsonUtil;
import com.loganalysis.common.util.TransformUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Hitch Control Cost Time 处理器，对齐 Python hitch_control_cost_time_processor.py。
 *
 * 特点：
 *   - 不聚合（每条日志独立 INSERT）
 *   - 不使用 Redis
 *   - 字段：trace_id / method_name / content / time_cost / log_time
 *   - time_cost 兜底支持 cost_time 字段别名
 */
@Service
public class HitchControlCostTimeProcessor {

    private static final Logger log = LoggerFactory.getLogger(HitchControlCostTimeProcessor.class);

    public static final String TABLE = "hitch_control_cost_time";

    public static final List<Map<String, Object>> DEFAULT_FIELD_CONFIG;
    static {
        List<Map<String, Object>> dc = new ArrayList<>();
        dc.add(field("trace_id", "VARCHAR(255)", "trace_id", null));
        dc.add(field("method_name", "VARCHAR(255)", "method_name", null));
        dc.add(field("content", "VARCHAR(10240)", "content", "substr:10240"));
        dc.add(field("time_cost", "INT", "time_cost", null));
        dc.add(field("log_time", "VARCHAR(255)", "log_time", null));
        DEFAULT_FIELD_CONFIG = Collections.unmodifiableList(dc);
    }

    private static Map<String, Object> field(String name, String type, String source, String transform) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("type", type);
        m.put("source", source); m.put("transform", transform);
        return m;
    }

    @Autowired private HitchControlCostTimeMapper hitchControlCostTimeMapper;
    @Autowired private TopicTableMappingReadMapper topicTableMappingReadMapper;
    @Autowired private FieldMappingReadMapper fieldMappingReadMapper;
    @Autowired private InsertRecordService insertRecord;
    @Autowired(required = false) private ChDualWriter chDualWriter;

    private volatile List<Map<String, Object>> fieldConfigCache;

    public void clearConfigCache() { this.fieldConfigCache = null; }

    @Transactional
    public Map<String, Object> processClsResponse(Map<String, Object> clsResponse,
                                                  Map<String, Map<String, Object>> queryTransformConfig,
                                                  Map<String, Object> queryFilterConfig) {
        if (clsResponse == null || !(clsResponse.get("Response") instanceof Map)) {
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
        if (!(resultsObj instanceof List)) return zeroResult();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resultsObj;
        if (results.isEmpty()) return zeroResult();

        List<Map<String, Object>> logDataList = new ArrayList<>(results.size());
        for (Map<String, Object> r : results) {
            Object lj = r.get("LogJson");
            if (lj instanceof String && !((String) lj).isEmpty()) {
                Map<String, Object> parsed = JsonUtil.toMap((String) lj);
                if (parsed != null) logDataList.add(parsed);
            }
        }
        return processLogs(logDataList, queryTransformConfig, queryFilterConfig);
    }

    @Transactional
    public Map<String, Object> processLogs(List<Map<String, Object>> logDataList,
                                           Map<String, Map<String, Object>> queryTransformConfig,
                                           Map<String, Object> queryFilterConfig) {
        if (logDataList == null || logDataList.isEmpty()) return zeroResult();

        // 转换所有日志
        List<Map<String, Object>> transformed = new ArrayList<>(logDataList.size());
        for (Map<String, Object> lg : logDataList) {
            try {
                transformed.add(transformLog(lg, queryTransformConfig));
            } catch (Exception ignore) {}
        }

        int success = 0, error = 0, filteredCount = 0;
        List<Map<String, Object>> errors = new ArrayList<>();

        int idx = -1;
        for (Map<String, Object> row : transformed) {
            idx++;
            try {
                if (queryFilterConfig != null && Boolean.TRUE.equals(queryFilterConfig.get("enabled"))) {
                    if (!FilterEvaluator.evaluate(row, queryFilterConfig)) {
                        filteredCount++;
                        continue;
                    }
                }
                // 直接 INSERT（不聚合）—— MP 自动回填 id
                HitchControlCostTimePO po = new HitchControlCostTimePO();
                po.setTraceId(str(row.get("trace_id")));
                po.setMethodName(str(row.get("method_name")));
                po.setContent(str(row.get("content")));
                po.setTimeCost(toInt(row.get("time_cost"), 0));
                po.setLogTime(str(row.get("log_time")));
                hitchControlCostTimeMapper.insert(po);
                if (chDualWriter != null && po.getId() != null) {
                    chDualWriter.writeCostTimeAsync(po.getId(),
                            po.getTraceId(), po.getMethodName(), po.getContent(),
                            po.getTimeCost() == null ? 0 : po.getTimeCost(), po.getLogTime());
                }

                insertRecord.record(InsertRecordService.LOG_FROM_COST_TIME,
                        str(row.get("method_name")),
                        str(row.get("content")),
                        1);
                success++;
            } catch (Exception e) {
                error++;
                if (errors.size() < 10) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("index", idx);
                    err.put("error", e.getMessage());
                    err.put("data", row);
                    errors.add(err);
                }
            }
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("total", logDataList.size());
        ret.put("transformed", transformed.size());
        ret.put("success", success);
        ret.put("error", error);
        ret.put("filtered", filteredCount);
        ret.put("errors", errors);
        return ret;
    }

    public Map<String, Object> transformLog(Map<String, Object> logData,
                                            Map<String, Map<String, Object>> queryTransformConfig) {
        Map<String, Map<String, Object>> fieldMap = loadFieldMappingsFromDb();
        List<Map<String, Object>> fieldConfig = loadFieldConfigFromDb();

        Map<String, Object> result;
        if (fieldConfig == null || fieldConfig.isEmpty()) {
            result = transformByDefault(logData);
        } else {
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
            result = TransformUtils.transformLogToRow(logData, fieldMap, fieldConfig);
        }

        // 兜底
        if (isBlank(result.get("trace_id"))) {
            result.put("trace_id", strDefault(logData.get("trace_id"), ""));
        }
        if (isBlank(result.get("method_name"))) {
            result.put("method_name", strDefault(logData.get("method_name"), ""));
        }
        if (isBlank(result.get("content"))) {
            Object raw = logData.get("content");
            String s = raw == null ? "" : String.valueOf(raw);
            result.put("content", s.length() > 10240 ? s.substring(0, 10240) : s);
        }
        if (result.get("time_cost") == null) {
            Object tc = logData.get("time_cost");
            if (tc == null) tc = logData.get("cost_time");   // 别名支持
            result.put("time_cost", tc == null ? 0 : tc);
        }
        if (isBlank(result.get("log_time"))) {
            result.put("log_time", strDefault(logData.get("log_time"), ""));
        }
        return result;
    }

    // ============================== 内部 ==============================

    private List<Map<String, Object>> loadFieldConfigFromDb() {
        if (fieldConfigCache != null) return fieldConfigCache;
        try {
            String json = topicTableMappingReadMapper.findFieldConfigJson(TABLE);
            if (json != null && !json.isEmpty()) {
                List<Map<String, Object>> list = JsonUtil.mapper().readValue(
                        json, new TypeReference<List<Map<String, Object>>>() {});
                if (list != null && !list.isEmpty()) {
                    fieldConfigCache = list;
                    return list;
                }
            }
        } catch (Exception e) {
            log.debug("读取 {} 映射配置失败: {}", TABLE, e.getMessage());
        }
        fieldConfigCache = DEFAULT_FIELD_CONFIG;
        return DEFAULT_FIELD_CONFIG;
    }

    private Map<String, Map<String, Object>> loadFieldMappingsFromDb() {
        try {
            List<Map<String, Object>> rows = fieldMappingReadMapper.findByTableName(TABLE);
            Map<String, Map<String, Object>> map = new HashMap<>();
            for (Map<String, Object> r : rows) {
                Object tc = r.get("target_column");
                if (tc != null) map.put(String.valueOf(tc), r);
            }
            return map;
        } catch (Exception e) { return new HashMap<>(); }
    }

    private Map<String, Object> transformByDefault(Map<String, Object> logData) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map<String, Object> f : DEFAULT_FIELD_CONFIG) {
            String name = String.valueOf(f.get("name"));
            String source = (String) f.get("source");
            String transform = (String) f.get("transform");
            Object value = source == null ? null : logData.get(source);
            if (transform != null) value = TransformUtils.applyTransform(value, transform, logData);
            row.put(name, value);
        }
        return row;
    }

    private static Map<String, Object> zeroResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", 0); r.put("success", 0); r.put("error", 0);
        r.put("filtered", 0); r.put("errors", Collections.emptyList());
        return r;
    }

    private static boolean isBlank(Object v) { return v == null || "".equals(v); }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static String strDefault(Object v, String def) {
        String s = str(v);
        return s == null || s.isEmpty() ? def : s;
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }
}
