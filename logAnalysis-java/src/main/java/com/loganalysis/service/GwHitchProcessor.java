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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GW Hitch 日志处理器，对齐 Python services/gw_hitch_processor.py。
 *
 * 核心流程（严格与 Python 一致）：
 *   1) transformLog：走 TransformUtils.transformLogToRow + 兜底字段
 *   2) 过滤（FilterEvaluator）
 *   3) 内存聚合：key=(method_name, error_code, error_message)，count 累加
 *   4) 每组先查 Redis：
 *        命中 → 覆盖 count、累加 total_count、UPDATE MySQL
 *        未命中 → 查 MySQL 当日同组：
 *              命中 → 累加 total_count、UPDATE MySQL、写 Redis
 *              未命中 → INSERT，写 Redis
 *   5) 每成功一组调用 InsertRecordService.record(LOG_FROM_GW_HITCH, ...)
 */
@Service
public class GwHitchProcessor {

    private static final Logger log = LoggerFactory.getLogger(GwHitchProcessor.class);

    public static final String TABLE = "gw_hitch_error_mothod";
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 默认字段配置（数据库无映射时使用），对齐 Python gw_hitch_processor._get_field_config 的 default_config */
    public static final List<Map<String, Object>> DEFAULT_FIELD_CONFIG;
    static {
        List<Map<String, Object>> dc = new ArrayList<>();
        dc.add(field("method_name", "VARCHAR(255)", "path", "regex:/.*:0"));
        dc.add(field("error_code", "INT", "response_body", "json_path:resData.code|fallback:errCode"));
        dc.add(field("error_message", "VARCHAR(1024)", "response_body", "json_path:resData.message|fallback:errMsg"));
        dc.add(field("content", "VARCHAR(10240)", "response_body", "substr:10240"));
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

    /** 字段配置缓存（对齐 Python _field_config_cache） */
    private volatile List<Map<String, Object>> fieldConfigCache;

    public void clearConfigCache() {
        this.fieldConfigCache = null;
    }

    // ============================== 对外入口 ==============================

    /** 处理 CLS API 响应。对齐 Python process_cls_response */
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

        // 解析 LogJson
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

    /** 处理日志列表（对齐 Python process_logs） */
    @Transactional
    public Map<String, Object> processLogs(List<Map<String, Object>> logDataList,
                                           Map<String, Map<String, Object>> queryTransformConfig,
                                           Map<String, Object> queryFilterConfig) {
        if (logDataList == null || logDataList.isEmpty()) return zeroResult();

        // ========== 阶段 1：转换 + 过滤 ==========
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
            } catch (Exception e) {
                // 与 Python 忽略转换失败一致
            }
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

        // ========== 阶段 2：内存聚合（key=(method_name, error_code, error_message)） ==========
        Map<String, Map<String, Object>> aggregated = new LinkedHashMap<>();
        for (Map<String, Object> row : transformed) {
            String k = buildAggKey(row);
            int currentCount = toInt(row.get("count"), 1);
            if (currentCount <= 0) currentCount = 1;
            Map<String, Object> exist = aggregated.get(k);
            if (exist != null) {
                exist.put("count", toInt(exist.get("count"), 0) + currentCount);
            } else {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("method_name", row.get("method_name"));
                g.put("error_code", row.get("error_code"));
                g.put("error_message", row.get("error_message"));
                g.put("content", row.get("content"));
                g.put("count", currentCount);
                aggregated.put(k, g);
            }
        }

        // ========== 阶段 3：写库 ==========
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
            Object methodName = agg.get("method_name");
            Object errorCode = agg.get("error_code");
            Object errorMessage = agg.get("error_message");
            Object content = agg.get("content");

            Map<String, Object> uniqueFields = new LinkedHashMap<>();
            uniqueFields.put("method_name", methodName);
            uniqueFields.put("error_code", errorCode);
            uniqueFields.put("error_message", errorMessage);

            try {
                Map<String, Object> cached = redisCache.get(TABLE, uniqueFields);
                if (cached != null) {
                    // 命中 Redis
                    long oldTotal = toLong(cached.get("total_count"), 0L);
                    long newTotal = oldTotal + batchCount;
                    cached.put("count", batchCount);
                    cached.put("total_count", newTotal);
                    cached.put("content", content);
                    cached.put("update_time", nowString());
                    redisCache.set(TABLE, cached, uniqueFields);

                    Object existingId = cached.get("id");
                    if (existingId instanceof Number) {
                        jdbc.update(
                                "UPDATE `gw_hitch_error_mothod` SET `count` = ?, `total_count` = ?, `content` = ? WHERE id = ?",
                                batchCount, newTotal, content, ((Number) existingId).longValue());
                    }
                } else {
                    // Redis 未命中，查当日 MySQL
                    Map<String, Object> existing = findTodaySameGroup(methodName, errorCode, errorMessage,
                            todayStart, todayEnd);
                    if (existing != null) {
                        long existingId = toLong(existing.get("id"), 0L);
                        long existingTotal = toLong(existing.get("total_count"), 0L);
                        long newTotal = existingTotal + batchCount;

                        Map<String, Object> cache = new LinkedHashMap<>();
                        cache.put("id", existingId);
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
                                "UPDATE `gw_hitch_error_mothod` SET `count` = ?, `total_count` = ?, `content` = ? WHERE id = ?",
                                batchCount, newTotal, content, existingId);
                    } else {
                        // INSERT
                        jdbc.update(
                                "INSERT INTO `gw_hitch_error_mothod` " +
                                "(`method_name`, `error_code`, `error_message`, `content`, `count`, `total_count`) " +
                                "VALUES (?, ?, ?, ?, ?, ?)",
                                methodName, errorCode, errorMessage, content, batchCount, (long) batchCount);
                        Long newId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

                        Map<String, Object> cache = new LinkedHashMap<>();
                        cache.put("id", newId);
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

                // 记录入库日志
                insertRecord.record(InsertRecordService.LOG_FROM_GW_HITCH,
                        methodName == null ? null : String.valueOf(methodName),
                        content == null ? null : String.valueOf(content),
                        batchCount);
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

    /** 转换单条日志（对齐 Python transform_log + 兜底逻辑） */
    public Map<String, Object> transformLog(Map<String, Object> logData,
                                            Map<String, Map<String, Object>> queryTransformConfig) {
        Map<String, Object> result;
        // 优先使用数据库映射配置（若存在）
        Map<String, Map<String, Object>> fieldMap = loadFieldMappingsFromDb();
        List<Map<String, Object>> fieldConfig = loadFieldConfigFromDb();

        if (fieldConfig == null || fieldConfig.isEmpty()) {
            // 降级到默认配置
            result = transformByDefault(logData);
        } else {
            // 若有查询级转换规则，合并覆盖
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

        // 兜底逻辑（对齐 Python transform_log 兜底分支）
        if (isBlank(result.get("method_name"))) {
            result.put("method_name", extractInterfaceName(String.valueOf(logData.get("path"))));
        }
        if (result.get("error_code") == null) {
            ParsedResp parsed = parseResponseBody(String.valueOf(logData.getOrDefault("response_body", "")));
            result.put("error_code", parsed.code);
        }
        if (isBlank(result.get("error_message"))) {
            ParsedResp parsed = parseResponseBody(String.valueOf(logData.getOrDefault("response_body", "")));
            result.put("error_message", parsed.message);
        }
        if (isBlank(result.get("content"))) {
            Object rb = logData.get("response_body");
            if (rb != null) {
                String s = String.valueOf(rb);
                result.put("content", s.length() > 10240 ? s.substring(0, 10240) : s);
            }
        }
        if (!(result.get("count") instanceof Number) || toInt(result.get("count"), 0) == 0) {
            result.put("count", 1);
        }
        // error_message 截断 1024
        Object em = result.get("error_message");
        if (em instanceof String && ((String) em).length() > 1024) {
            result.put("error_message", ((String) em).substring(0, 1024));
        }
        return result;
    }

    // ============================== 查询接口 ==============================

    public Map<String, Object> getTableData(int limit, int offset, String orderBy, String orderDir) {
        String dir = "ASC".equalsIgnoreCase(orderDir) ? "ASC" : "DESC";
        Set<String> allowed = Set.of("id", "method_name", "error_code", "error_message",
                "content", "count", "total_count", "create_time", "update_time");
        String safeOrderBy = allowed.contains(orderBy) ? orderBy : "id";

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `gw_hitch_error_mothod` ORDER BY `" + safeOrderBy + "` " + dir +
                " LIMIT ? OFFSET ?", limit, offset);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM `gw_hitch_error_mothod`", Long.class);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("columns", Arrays.asList("id", "method_name", "error_code", "error_message",
                "content", "count", "total_count", "create_time", "update_time"));
        ret.put("data", rows);
        ret.put("total", total == null ? 0L : total);
        ret.put("limit", limit);
        ret.put("offset", offset);
        return ret;
    }

    public Map<String, Object> getErrorStatistics() {
        List<Map<String, Object>> stats = jdbc.queryForList(
                "SELECT error_code, error_message, method_name, SUM(total_count) as count, MAX(update_time) as last_time " +
                "FROM gw_hitch_error_mothod GROUP BY error_code, error_message, method_name " +
                "ORDER BY count DESC LIMIT 50");
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("statistics", stats);
        return ret;
    }

    // ============================== 内部 ==============================

    private Map<String, Object> findTodaySameGroup(Object methodName, Object errorCode, Object errorMessage,
                                                   String todayStart, String todayEnd) {
        // method_name <=> ? 的 NULL-safe 比较（对齐 Python MySQL）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, count, total_count, create_time, update_time " +
                "FROM `gw_hitch_error_mothod` " +
                "WHERE method_name <=> ? AND error_code <=> ? AND error_message <=> ? " +
                "AND create_time >= ? AND create_time <= ? " +
                "ORDER BY create_time DESC LIMIT 1",
                methodName, errorCode, errorMessage, todayStart, todayEnd);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 读取数据库里的 topic_table_mappings 对应字段配置（缓存） */
    @SuppressWarnings("unchecked")
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

    /** 读取 field_mappings 表到 {target_column → mapping} */
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

    /** 默认字段转换路径（数据库无配置时） */
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

    /** 解析 response_body JSON：优先 resData.code/message，否则 errCode/errMsg */
    static ParsedResp parseResponseBody(String responseBody) {
        ParsedResp r = new ParsedResp();
        if (responseBody == null || responseBody.isEmpty()) return r;
        Map<String, Object> data = JsonUtil.toMap(responseBody);
        if (data == null) return r;
        Object resDataObj = data.get("resData");
        if (resDataObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rd = (Map<String, Object>) resDataObj;
            if (rd.get("code") != null || rd.get("message") != null) {
                r.code = rd.get("code");
                r.message = rd.get("message") == null ? null : String.valueOf(rd.get("message"));
                return r;
            }
        }
        r.code = data.get("errCode");
        r.message = data.get("errMsg") == null ? null : String.valueOf(data.get("errMsg"));
        return r;
    }

    /** 从 path 提取 interface_name：从第一个 '/' 开始 */
    static String extractInterfaceName(String path) {
        if (path == null || path.isEmpty()) return "";
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(slash);
    }

    // ============================== 工具 ==============================

    private static Map<String, Object> zeroResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", 0); r.put("success", 0); r.put("error", 0);
        r.put("filtered", 0); r.put("errors", Collections.emptyList());
        return r;
    }

    private static String buildAggKey(Map<String, Object> row) {
        String m = row.get("method_name") == null ? "" : String.valueOf(row.get("method_name"));
        String c = row.get("error_code") == null ? "" : String.valueOf(row.get("error_code"));
        String e = row.get("error_message") == null ? "" : String.valueOf(row.get("error_message"));
        return m + "\u0001" + c + "\u0001" + e;
    }

    private static boolean isBlank(Object v) {
        return v == null || "".equals(v);
    }

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
        return java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 内部小工具：封装 response_body 解析结果 */
    static class ParsedResp {
        Object code;
        String message;
    }
}
