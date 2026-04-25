package com.loganalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.loganalysis.util.FilterEvaluator;
import com.loganalysis.util.JsonUtil;
import com.loganalysis.util.TransformUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Hitch Supplier Error SP 处理器，对齐 Python services/hitch_supplier_error_sp_processor.py。
 *
 * 与 Gw/Control 的差异：
 *   - 聚合 key：(sp_id, method_name, error_code, error_message) 四元组
 *   - 新增 sp_id / sp_name 字段，sp_name 在 UPDATE 时也会被更新
 *   - INSERT 8 字段
 *   - insertRecord 第 5 参数传 sp_id
 */
@Service
public class HitchSupplierErrorSpProcessor {

    private static final Logger log = LoggerFactory.getLogger(HitchSupplierErrorSpProcessor.class);

    public static final String TABLE = "hitch_supplier_error_sp";
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static final List<Map<String, Object>> DEFAULT_FIELD_CONFIG;
    static {
        List<Map<String, Object>> dc = new ArrayList<>();
        dc.add(field("sp_id", "INT", "sp_id", null));
        dc.add(field("sp_name", "VARCHAR(255)", "sp_name", null));
        dc.add(field("method_name", "VARCHAR(255)", "method_name", null));
        dc.add(field("content", "VARCHAR(10240)", "content", "substr:10240"));
        dc.add(field("error_code", "INT", "error_code", null));
        dc.add(field("error_message", "VARCHAR(255)", "error_message", "substr:255"));
        DEFAULT_FIELD_CONFIG = Collections.unmodifiableList(dc);
    }

    private static Map<String, Object> field(String name, String type, String source, String transform) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("type", type);
        m.put("source", source); m.put("transform", transform);
        return m;
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisCacheService redisCache;

    @Autowired
    private InsertRecordService insertRecord;

    private volatile List<Map<String, Object>> fieldConfigCache;

    public void clearConfigCache() {
        this.fieldConfigCache = null;
    }

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

        List<Map<String, Object>> transformed = new ArrayList<>(logDataList.size());
        int filteredCount = 0;
        for (Map<String, Object> lg : logDataList) {
            try {
                Map<String, Object> row = transformLog(lg, queryTransformConfig);
                if (queryFilterConfig != null && Boolean.TRUE.equals(queryFilterConfig.get("enabled"))) {
                    if (!FilterEvaluator.evaluate(row, queryFilterConfig)) {
                        filteredCount++;
                        continue;
                    }
                }
                transformed.add(row);
            } catch (Exception ignore) {}
        }
        if (transformed.isEmpty()) {
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("total", logDataList.size());
            ret.put("transformed", 0);
            ret.put("aggregated", 0);
            ret.put("success", 0);
            ret.put("error", 0);
            ret.put("filtered", filteredCount);
            ret.put("errors", Collections.emptyList());
            return ret;
        }

        // 聚合 key 含 sp_id
        Map<String, Map<String, Object>> aggregated = new LinkedHashMap<>();
        for (Map<String, Object> row : transformed) {
            String k = buildAggKey(row);
            int currentCount = toInt(row.get("count"), 1);
            if (currentCount <= 0) currentCount = 1;
            Map<String, Object> exist = aggregated.get(k);
            if (exist != null) {
                exist.put("count", toInt(exist.get("count"), 0) + currentCount);
                // sp_name 可能后来才有：保留后到的非空值
                if (isBlank(exist.get("sp_name")) && !isBlank(row.get("sp_name"))) {
                    exist.put("sp_name", row.get("sp_name"));
                }
            } else {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("sp_id", row.get("sp_id"));
                g.put("sp_name", row.get("sp_name"));
                g.put("method_name", row.get("method_name"));
                g.put("error_code", row.get("error_code"));
                g.put("error_message", row.get("error_message"));
                g.put("content", row.get("content"));
                g.put("count", currentCount);
                aggregated.put(k, g);
            }
        }

        int success = 0, error = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        String today = LocalDate.now().format(D);
        String todayStart = today + " 00:00:00";
        String todayEnd = today + " 23:59:59";

        int idx = -1;
        for (Map.Entry<String, Map<String, Object>> entry : aggregated.entrySet()) {
            idx++;
            Map<String, Object> agg = entry.getValue();
            int batchCount = toInt(agg.get("count"), 1);
            Object spId = agg.get("sp_id");
            Object spName = agg.get("sp_name");
            Object methodName = agg.get("method_name");
            Object errorCode = agg.get("error_code");
            Object errorMessage = agg.get("error_message");
            Object content = agg.get("content");

            Map<String, Object> uniqueFields = new LinkedHashMap<>();
            uniqueFields.put("sp_id", spId);
            uniqueFields.put("method_name", methodName);
            uniqueFields.put("error_code", errorCode);
            uniqueFields.put("error_message", errorMessage);

            try {
                Map<String, Object> cached = redisCache.get(TABLE, uniqueFields);
                if (cached != null) {
                    long oldTotal = toLong(cached.get("total_count"), 0L);
                    long newTotal = oldTotal + batchCount;
                    cached.put("count", batchCount);
                    cached.put("total_count", newTotal);
                    cached.put("content", content);
                    cached.put("sp_name", spName);
                    cached.put("update_time", nowString());
                    redisCache.set(TABLE, cached, uniqueFields);

                    Object existingId = cached.get("id");
                    if (existingId instanceof Number) {
                        jdbc.update(
                                "UPDATE `hitch_supplier_error_sp` SET `count` = ?, `total_count` = ?, `content` = ?, `sp_name` = ? WHERE id = ?",
                                batchCount, newTotal, content, spName, ((Number) existingId).longValue());
                    }
                } else {
                    Map<String, Object> existing = findTodaySameGroup(spId, methodName, errorCode, errorMessage,
                            todayStart, todayEnd);
                    if (existing != null) {
                        long existingId = toLong(existing.get("id"), 0L);
                        long existingTotal = toLong(existing.get("total_count"), 0L);
                        long newTotal = existingTotal + batchCount;

                        Map<String, Object> cache = new LinkedHashMap<>();
                        cache.put("id", existingId);
                        cache.put("sp_id", spId);
                        cache.put("sp_name", spName);
                        cache.put("method_name", methodName);
                        cache.put("error_code", errorCode);
                        cache.put("error_message", errorMessage);
                        cache.put("content", content);
                        cache.put("count", batchCount);
                        cache.put("total_count", newTotal);
                        cache.put("create_time", String.valueOf(existing.getOrDefault("create_time", "")));
                        cache.put("update_time", nowString());
                        redisCache.set(TABLE, cache, uniqueFields);

                        jdbc.update(
                                "UPDATE `hitch_supplier_error_sp` SET `count` = ?, `total_count` = ?, `content` = ?, `sp_name` = ? WHERE id = ?",
                                batchCount, newTotal, content, spName, existingId);
                    } else {
                        jdbc.update(
                                "INSERT INTO `hitch_supplier_error_sp` " +
                                "(`sp_id`, `sp_name`, `method_name`, `content`, `error_code`, `error_message`, `count`, `total_count`) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                spId, spName, methodName, content, errorCode, errorMessage,
                                batchCount, (long) batchCount);
                        Long newId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

                        Map<String, Object> cache = new LinkedHashMap<>();
                        cache.put("id", newId);
                        cache.put("sp_id", spId);
                        cache.put("sp_name", spName);
                        cache.put("method_name", methodName);
                        cache.put("error_code", errorCode);
                        cache.put("error_message", errorMessage);
                        cache.put("content", content);
                        cache.put("count", batchCount);
                        cache.put("total_count", (long) batchCount);
                        cache.put("create_time", nowString());
                        cache.put("update_time", nowString());
                        redisCache.set(TABLE, cache, uniqueFields);
                    }
                }

                insertRecord.record(InsertRecordService.LOG_FROM_SUPPLIER_SP,
                        methodName == null ? null : String.valueOf(methodName),
                        content == null ? null : String.valueOf(content),
                        batchCount,
                        toInt(spId, 0));
                success++;
            } catch (Exception e) {
                error++;
                if (errors.size() < 10) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("index", idx);
                    err.put("error", e.getMessage());
                    err.put("data", agg);
                    errors.add(err);
                }
            }
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("total", logDataList.size());
        ret.put("transformed", transformed.size());
        ret.put("aggregated", aggregated.size());
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

        // 兜底（对齐 Python transform_log）
        if (result.get("sp_id") == null) result.put("sp_id", logData.get("sp_id"));
        if (isBlank(result.get("sp_name"))) result.put("sp_name", logData.get("sp_name"));
        if (isBlank(result.get("method_name"))) result.put("method_name", logData.get("method_name"));
        if (isBlank(result.get("content"))) {
            Object raw = logData.get("content");
            if (raw != null) {
                String s = String.valueOf(raw);
                result.put("content", s.length() > 10240 ? s.substring(0, 10240) : s);
            }
        }
        if (result.get("error_code") == null) result.put("error_code", logData.get("error_code"));
        if (isBlank(result.get("error_message"))) {
            Object raw = logData.get("error_message");
            if (raw != null) {
                String s = String.valueOf(raw);
                result.put("error_message", s.length() > 255 ? s.substring(0, 255) : s);
            }
        }
        if (!(result.get("count") instanceof Number) || toInt(result.get("count"), 0) == 0) {
            result.put("count", 1);
        }
        return result;
    }

    // ============================== 查询接口 ==============================

    public Map<String, Object> getTableData(int limit, int offset, String orderBy, String orderDir) {
        String dir = "ASC".equalsIgnoreCase(orderDir) ? "ASC" : "DESC";
        Set<String> allowed = Set.of("id", "sp_id", "sp_name", "method_name", "content", "error_code",
                "error_message", "count", "total_count", "create_time", "update_time");
        String safeOrderBy = allowed.contains(orderBy) ? orderBy : "id";

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `hitch_supplier_error_sp` ORDER BY `" + safeOrderBy + "` " + dir +
                " LIMIT ? OFFSET ?", limit, offset);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM `hitch_supplier_error_sp`", Long.class);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("columns", Arrays.asList("id", "sp_id", "sp_name", "method_name", "content",
                "error_code", "error_message", "count", "total_count", "create_time", "update_time"));
        ret.put("data", rows);
        ret.put("total", total == null ? 0L : total);
        ret.put("limit", limit);
        ret.put("offset", offset);
        return ret;
    }

    public Map<String, Object> getErrorStatistics() {
        List<Map<String, Object>> stats = jdbc.queryForList(
                "SELECT sp_id, sp_name, error_code, error_message, method_name, SUM(total_count) as count, MAX(update_time) as last_time " +
                "FROM hitch_supplier_error_sp GROUP BY sp_id, sp_name, error_code, error_message, method_name " +
                "ORDER BY count DESC LIMIT 50");
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("statistics", stats);
        return ret;
    }

    // ============================== 内部 ==============================

    private Map<String, Object> findTodaySameGroup(Object spId, Object methodName, Object errorCode, Object errorMessage,
                                                   String todayStart, String todayEnd) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, count, total_count, create_time, update_time " +
                "FROM `hitch_supplier_error_sp` " +
                "WHERE sp_id <=> ? AND method_name <=> ? AND error_code <=> ? AND error_message <=> ? " +
                "AND create_time >= ? AND create_time <= ? " +
                "ORDER BY create_time DESC LIMIT 1",
                spId, methodName, errorCode, errorMessage, todayStart, todayEnd);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> loadFieldConfigFromDb() {
        if (fieldConfigCache != null) return fieldConfigCache;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT field_config FROM topic_table_mappings WHERE table_name = ?", TABLE);
            if (!rows.isEmpty()) {
                Object fc = rows.get(0).get("field_config");
                if (fc instanceof String) {
                    List<Map<String, Object>> list = JsonUtil.mapper().readValue(
                            (String) fc, new TypeReference<List<Map<String, Object>>>() {});
                    if (list != null && !list.isEmpty()) {
                        fieldConfigCache = list;
                        return list;
                    }
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
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT fm.* FROM field_mappings fm " +
                    "JOIN topic_table_mappings m ON fm.mapping_id = m.id " +
                    "WHERE m.table_name = ?", TABLE);
            Map<String, Map<String, Object>> map = new HashMap<>();
            for (Map<String, Object> r : rows) {
                Object tc = r.get("target_column");
                if (tc != null) map.put(String.valueOf(tc), r);
            }
            return map;
        } catch (Exception e) {
            return new HashMap<>();
        }
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

    // ============================== 工具 ==============================

    private static Map<String, Object> zeroResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", 0); r.put("success", 0); r.put("error", 0);
        r.put("filtered", 0); r.put("errors", Collections.emptyList());
        return r;
    }

    private static String buildAggKey(Map<String, Object> row) {
        String sp = row.get("sp_id") == null ? "" : String.valueOf(row.get("sp_id"));
        String m = row.get("method_name") == null ? "" : String.valueOf(row.get("method_name"));
        String c = row.get("error_code") == null ? "" : String.valueOf(row.get("error_code"));
        String e = row.get("error_message") == null ? "" : String.valueOf(row.get("error_message"));
        return sp + "\u0001" + m + "\u0001" + c + "\u0001" + e;
    }

    private static boolean isBlank(Object v) { return v == null || "".equals(v); }

    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }

    private static long toLong(Object v, long def) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return def;
    }

    private static String nowString() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
