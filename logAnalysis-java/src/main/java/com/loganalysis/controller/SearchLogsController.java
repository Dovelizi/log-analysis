package com.loganalysis.controller;

import com.loganalysis.service.ClsQueryService;
import com.loganalysis.service.DataProcessorRouter;
import com.loganalysis.service.QueryConfigService;
import com.loganalysis.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 统一日志查询 Controller，对齐 app.py 中：
 *   - POST /api/search-logs          核心入口（按 config_id 或自定义参数查 CLS 再分发到 processor）
 *   - POST /api/test-cls             调试：用传入的 secret_id/key 直接调一次 CLS
 *   - GET  /api/log-records          查 log_records 表
 *   - GET  /api/analysis-results     查 analysis_results 表
 *   - GET  /api/statistics           全局统计
 */
@RestController
public class SearchLogsController {

    @Autowired private ClsQueryService clsQueryService;
    @Autowired private DataProcessorRouter processorRouter;
    @Autowired private QueryConfigService queryConfigService;
    @Autowired private JdbcTemplate jdbc;

    @PostMapping("/api/search-logs")
    public ResponseEntity<?> searchLogs(@RequestBody Map<String, Object> body) {
        Long configId = toLongOrNull(body.get("config_id"));
        String customQuery = str(body.get("query"));
        Long customFrom = toLongOrNull(body.get("from_time"));
        Long customTo = toLongOrNull(body.get("to_time"));
        // 支持 (日期 + 时间区间) 请求参数，优先级低于已有的 from_time/to_time 毫秒时间戳
        String startDate = str(body.get("start_date"));
        String endDate = str(body.get("end_date"));
        String startTimeOfDay = str(body.get("start_time"));
        String endTimeOfDay = str(body.get("end_time"));
        if (customFrom == null && !isEmpty(startDate)) {
            customFrom = parseDateTimeToMillis(startDate, isEmpty(startTimeOfDay) ? "00:00:00" : startTimeOfDay);
        }
        if (customTo == null && !isEmpty(endDate)) {
            customTo = parseDateTimeToMillis(endDate, isEmpty(endTimeOfDay) ? "23:59:59" : endTimeOfDay);
        }

        long credentialId;
        String topicId;
        String region;
        String query;
        long fromTime, toTime;
        int limit;
        String sort;
        int syntaxRule;
        String processorType;
        String targetTable;
        Map<String, Map<String, Object>> queryTransformConfig = null;
        Map<String, Object> queryFilterConfig = null;

        if (configId != null) {
            // 使用预配置的查询
            Map<String, Object> config = queryConfigService.findWithTopic(configId);
            if (config == null) {
                return ResponseEntity.status(404).body(Collections.singletonMap("error", "查询配置不存在"));
            }
            credentialId = toLongOrDefault(config.get("credential_id"), 0L);
            topicId = str(config.get("cls_topic_id"));
            region = str(config.get("region"));
            query = customQuery != null ? customQuery : str(config.get("query_statement"));
            int timeRange = toIntOrDefault(config.get("time_range"), 3600);
            limit = toIntOrDefault(config.get("limit_count"), 100);
            sort = strDefault(config.get("sort_order"), "desc");
            syntaxRule = toIntOrDefault(config.get("syntax_rule"), 1);

            processorType = firstNonEmpty(str(body.get("processor_type")), str(config.get("processor_type")));
            targetTable = firstNonEmpty(str(body.get("target_table")), str(config.get("target_table")));

            Object tcObj = config.get("transform_config");
            if (tcObj instanceof Map) queryTransformConfig = castTransformConfig(tcObj);
            Object fcObj = config.get("filter_config");
            if (fcObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) fcObj;
                queryFilterConfig = m;
            }

            long now = System.currentTimeMillis();
            toTime = customTo != null ? customTo : now;
            fromTime = customFrom != null ? customFrom : (toTime - timeRange * 1000L);
        } else {
            // 使用自定义参数
            if (body.get("credential_id") == null || body.get("topic_id") == null || body.get("query") == null) {
                return ResponseEntity.badRequest().body(Collections.singletonMap("error", "缺少必要参数"));
            }
            credentialId = toLongOrDefault(body.get("credential_id"), 0L);
            topicId = str(body.get("topic_id"));
            query = str(body.get("query"));
            long now = System.currentTimeMillis();
            fromTime = customFrom != null ? customFrom : (now - 3600_000L);
            toTime = customTo != null ? customTo : now;
            limit = toIntOrDefault(body.get("limit"), 100);
            sort = strDefault(body.get("sort"), "desc");
            syntaxRule = toIntOrDefault(body.get("syntax_rule"), 1);
            processorType = str(body.get("processor_type"));
            targetTable = str(body.get("target_table"));

            region = null;
            try {
                List<String> r = jdbc.query(
                        "SELECT region FROM log_topics WHERE topic_id = ? LIMIT 1",
                        (rs, i) -> rs.getString(1), topicId);
                if (!r.isEmpty()) region = r.get(0);
            } catch (Exception ignore) {}
        }

        // 调 CLS
        Map<String, Object> result = clsQueryService.searchLog(
                credentialId, topicId, query, fromTime, toTime, limit, sort, syntaxRule, region);

        // 分发到 processor（只有在真的有结果时）
        boolean hasResults = false;
        int resultsCount = 0;
        if (result.get("Response") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = (Map<String, Object>) result.get("Response");
            if (response.get("Results") instanceof List) {
                resultsCount = ((List<?>) response.get("Results")).size();
                hasResults = resultsCount > 0;
            }
        }

        Map<String, Object> debugParams = new LinkedHashMap<>();
        debugParams.put("processor_type", processorType);
        debugParams.put("target_table", targetTable);
        debugParams.put("config_id", configId);
        result.put("_debug_params", debugParams);

        if (hasResults) {
            try {
                Map<String, Object> processResult = processorRouter.dispatch(
                        result, processorType, targetTable,
                        queryTransformConfig, queryFilterConfig, configId, topicId);
                result.put("_process_result", processResult);
            } catch (Exception e) {
                result.put("_process_error", e.getMessage());
            }
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/test-cls")
    public ResponseEntity<?> testCls(@RequestBody Map<String, Object> body) {
        String secretId = str(body.get("secret_id"));
        String secretKey = str(body.get("secret_key"));
        String topicId = str(body.get("topic_id"));
        String region = strDefault(body.get("region"), "ap-nanjing");
        String query = strDefault(body.get("query"), "*");
        if (isEmpty(secretId) || isEmpty(secretKey) || isEmpty(topicId)) {
            return ResponseEntity.badRequest().body(Collections.singletonMap(
                    "error", "缺少必要参数: secret_id, secret_key, topic_id"));
        }

        long now = System.currentTimeMillis();
        long fromTime = now - 3600_000L;

        // 为了复用 ClsQueryService 的 SDK 初始化逻辑，我们临时直接用 SDK：
        try {
            com.tencentcloudapi.common.Credential cred =
                    new com.tencentcloudapi.common.Credential(secretId, secretKey);
            com.tencentcloudapi.common.profile.HttpProfile hp =
                    new com.tencentcloudapi.common.profile.HttpProfile();
            hp.setEndpoint("cls.internal.tencentcloudapi.com");
            hp.setReqMethod("POST");
            com.tencentcloudapi.common.profile.ClientProfile cp =
                    new com.tencentcloudapi.common.profile.ClientProfile();
            cp.setHttpProfile(hp);
            com.tencentcloudapi.cls.v20201016.ClsClient client =
                    new com.tencentcloudapi.cls.v20201016.ClsClient(cred, region, cp);

            com.tencentcloudapi.cls.v20201016.models.SearchLogRequest req =
                    new com.tencentcloudapi.cls.v20201016.models.SearchLogRequest();
            req.setTopicId(topicId);
            req.setFrom(fromTime);
            req.setTo(now);
            req.setQuery(query);
            req.setLimit(10L);
            req.setSort("desc");
            req.setSyntaxRule(1L);
            req.setUseNewAnalysis(true);

            com.tencentcloudapi.cls.v20201016.models.SearchLogResponse resp = client.SearchLog(req);
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("Response", JsonUtil.mapper().readValue(
                    com.tencentcloudapi.common.AbstractModel.toJsonString(resp),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("region", region);
            debug.put("topic_id", topicId);
            debug.put("timestamp", now / 1000);
            debug.put("from_time", fromTime);
            debug.put("to_time", now);
            ret.put("_debug", debug);
            return ResponseEntity.ok(ret);
        } catch (com.tencentcloudapi.common.exception.TencentCloudSDKException e) {
            Map<String, Object> response = new LinkedHashMap<>();
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("Code", e.getErrorCode());
            err.put("Message", e.getMessage());
            response.put("Error", err);
            response.put("RequestId", e.getRequestId());

            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("Response", response);
            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("region", region); debug.put("topic_id", topicId);
            ret.put("_debug", debug);
            return ResponseEntity.ok(ret);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    // ==================== 数据展示 ====================

    @GetMapping("/api/log-records")
    public ResponseEntity<?> logRecords(@RequestParam(value = "config_id", required = false) Long configId,
                                        @RequestParam(value = "limit", defaultValue = "100") int limit,
                                        @RequestParam(value = "offset", defaultValue = "0") int offset) {
        List<Map<String, Object>> records;
        long total;
        if (configId != null) {
            records = jdbc.queryForList(
                    "SELECT * FROM log_records WHERE query_config_id = ? ORDER BY log_time DESC LIMIT ? OFFSET ?",
                    configId, limit, offset);
            Long t = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM log_records WHERE query_config_id = ?", Long.class, configId);
            total = t == null ? 0L : t;
        } else {
            records = jdbc.queryForList(
                    "SELECT * FROM log_records ORDER BY log_time DESC LIMIT ? OFFSET ?", limit, offset);
            Long t = jdbc.queryForObject("SELECT COUNT(*) FROM log_records", Long.class);
            total = t == null ? 0L : t;
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("records", records);
        ret.put("total", total);
        ret.put("limit", limit);
        ret.put("offset", offset);
        return ResponseEntity.ok(ret);
    }

    @GetMapping("/api/analysis-results")
    public ResponseEntity<?> analysisResults(@RequestParam(value = "config_id", required = false) Long configId) {
        List<Map<String, Object>> rows = configId != null
                ? jdbc.queryForList(
                        "SELECT * FROM analysis_results WHERE query_config_id = ? ORDER BY created_at DESC",
                        configId)
                : jdbc.queryForList("SELECT * FROM analysis_results ORDER BY created_at DESC");
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/api/statistics")
    public ResponseEntity<?> statistics() {
        // 并行 5 个独立查询：3 个 COUNT + 2 个 GROUP BY
        java.util.concurrent.CompletableFuture<Long> fLogs =
                java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        jdbc.queryForObject("SELECT COUNT(*) FROM log_records", Long.class));
        java.util.concurrent.CompletableFuture<Long> fConfigs =
                java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        jdbc.queryForObject("SELECT COUNT(*) FROM query_configs", Long.class));
        java.util.concurrent.CompletableFuture<Long> fTopics =
                java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        jdbc.queryForObject("SELECT COUNT(*) FROM log_topics", Long.class));
        java.util.concurrent.CompletableFuture<List<Map<String, Object>>> fTime =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> jdbc.queryForList(
                        "SELECT DATE(log_time) as date, COUNT(*) as count FROM log_records " +
                        "WHERE log_time IS NOT NULL GROUP BY DATE(log_time) ORDER BY date DESC LIMIT 30"));
        java.util.concurrent.CompletableFuture<List<Map<String, Object>>> fTopic =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> jdbc.queryForList(
                        "SELECT topic_id, COUNT(*) as count FROM log_records GROUP BY topic_id"));

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("total_logs", joinOrZero(fLogs));
        ret.put("total_configs", joinOrZero(fConfigs));
        ret.put("total_topics", joinOrZero(fTopics));
        ret.put("time_distribution", joinOrEmptyList(fTime));
        ret.put("topic_distribution", joinOrEmptyList(fTopic));
        return ResponseEntity.ok(ret);
    }

    private static long joinOrZero(java.util.concurrent.CompletableFuture<Long> f) {
        try { Long v = f.get(15, java.util.concurrent.TimeUnit.SECONDS); return v == null ? 0L : v; }
        catch (Exception e) { return 0L; }
    }
    private static List<Map<String, Object>> joinOrEmptyList(java.util.concurrent.CompletableFuture<List<Map<String, Object>>> f) {
        try { return f.get(15, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    /** "yyyy-MM-dd" + "HH:mm:ss"/"HH:mm" → epoch millis (系统默认时区) */
    private static Long parseDateTimeToMillis(String date, String time) {
        try {
            String t = (time == null || time.isEmpty()) ? "00:00:00" : time;
            if (t.length() == 5) t = t + ":00";
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(
                    date + "T" + t, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> castTransformConfig(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) out.put(String.valueOf(e.getKey()), (Map<String, Object>) v);
        }
        return out;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
    private static String strDefault(Object v, String def) {
        String s = str(v);
        return s == null || s.isEmpty() ? def : s;
    }
    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b;
    }
    private static Long toLongOrNull(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return null;
    }
    private static long toLongOrDefault(Object v, long def) {
        Long x = toLongOrNull(v);
        return x == null ? def : x;
    }
    private static int toIntOrDefault(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }
}
