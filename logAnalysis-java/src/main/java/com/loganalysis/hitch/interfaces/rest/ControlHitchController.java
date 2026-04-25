package com.loganalysis.hitch.interfaces.rest;

import com.loganalysis.search.infrastructure.ClsQueryService;
import com.loganalysis.hitch.application.ControlHitchProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Control Hitch 处理器 Controller
 * 对齐 routes/control_hitch_routes.py，前缀 /api/control-hitch。
 */
@RestController
@RequestMapping("/api/control-hitch")
public class ControlHitchController {

    @Autowired
    private ControlHitchProcessor processor;

    @Autowired
    private ClsQueryService clsQueryService;

    @Autowired
    private JdbcTemplate jdbc;

    // ============ 数据查询 ============

    @GetMapping("/data")
    public ResponseEntity<?> data(@RequestParam(defaultValue = "100") int limit,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(value = "order_by", defaultValue = "id") String orderBy,
                                  @RequestParam(value = "order_dir", defaultValue = "DESC") String orderDir) {
        return ResponseEntity.ok(processor.getTableData(limit, offset, orderBy, orderDir));
    }

    @GetMapping("/statistics")
    public ResponseEntity<?> statistics() {
        return ResponseEntity.ok(processor.getErrorStatistics());
    }

    // ============ 数据处理 ============

    @SuppressWarnings("unchecked")
    @PostMapping("/process")
    public ResponseEntity<?> process(@RequestBody Map<String, Object> body) {
        Object logData = body.get("log_data");
        if (!(logData instanceof List)) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "缺少日志数据");
            return ResponseEntity.badRequest().body(err);
        }
        processor.clearConfigCache();
        Map<String, Object> result = processor.processLogs((List<Map<String, Object>>) logData, null, null);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/collect")
    public ResponseEntity<?> collect(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> data = body == null ? new HashMap<>() : body;
        Object credId = data.get("credential_id");
        String topicId = str(data.get("topic_id"));
        String query = strDefault(data.get("query"), "*");
        int timeRange = toInt(data.get("time_range"), 3600);
        int limit = toInt(data.get("limit"), 100);

        if (credId == null || topicId == null || topicId.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "缺少credential_id或topic_id参数");
            return ResponseEntity.badRequest().body(err);
        }

        String region = null;
        try {
            List<String> r = jdbc.query(
                    "SELECT region FROM log_topics WHERE topic_id = ? LIMIT 1",
                    (rs, i) -> rs.getString(1), topicId);
            if (!r.isEmpty()) region = r.get(0);
        } catch (Exception ignore) {}

        long now = System.currentTimeMillis();
        long fromTime = now - timeRange * 1000L;

        Map<String, Object> clsResponse = clsQueryService.searchLog(
                toLong(credId, 0L), topicId, query, fromTime, now, limit, "desc", 1, region);

        // 检查 CLS 错误
        if (clsResponse.get("Response") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = (Map<String, Object>) clsResponse.get("Response");
            if (resp.get("Error") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> clsErr = (Map<String, Object>) resp.get("Error");
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", clsErr.getOrDefault("Message", "未知错误"));
                err.put("code", clsErr.get("Code"));
                return ResponseEntity.status(500).body(err);
            }
        }

        processor.clearConfigCache();
        Map<String, Object> processResult = processor.processClsResponse(clsResponse, null, null);

        int totalCount = 0;
        if (clsResponse.get("Response") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = (Map<String, Object>) clsResponse.get("Response");
            if (resp.get("Results") instanceof List) totalCount = ((List<?>) resp.get("Results")).size();
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        Map<String, Object> clsResult = new LinkedHashMap<>();
        clsResult.put("total", totalCount);
        // 原 Python 里 Analysis 字段，保留透传
        if (clsResponse.get("Response") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = (Map<String, Object>) clsResponse.get("Response");
            clsResult.put("analysis", resp.getOrDefault("Analysis", false));
        }
        ret.put("cls_result", clsResult);
        ret.put("process_result", processResult);
        return ResponseEntity.ok(ret);
    }

    // ============ 辅助接口 ============

    @GetMapping("/schema")
    public ResponseEntity<?> schema() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("table_name", "control_hitch_error_mothod");
        ret.put("description", "顺风车错误方法监控表");
        ret.put("columns", Arrays.asList(
                col("id", "BIGINT", "主键ID，自增"),
                col("method_name", "VARCHAR(255)", "出错的方法名"),
                col("error_code", "VARCHAR(255)", "错误码"),
                col("error_message", "VARCHAR(1024)", "错误信息"),
                col("content", "VARCHAR(10240)", "响应内容"),
                col("count", "INT", "单次聚合周期内的错误次数"),
                col("total_count", "BIGINT", "累计错误总数"),
                col("create_time", "TIMESTAMP", "记录创建时间"),
                col("update_time", "TIMESTAMP", "记录最后更新时间")));
        // field_mapping 字段说明（对齐 Python 版）
        Map<String, String> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("method_name", "content中method:后面的值");
        fieldMapping.put("error_code", "content中code=后面的数字");
        fieldMapping.put("error_message", "content中desc=后面的内容");
        fieldMapping.put("content", "content原始值（截取前10240字符）");
        fieldMapping.put("count", "单次聚合周期内的错误次数");
        fieldMapping.put("total_count", "累计错误总数");
        fieldMapping.put("create_time", "记录创建时间");
        fieldMapping.put("update_time", "记录最后更新时间");
        ret.put("field_mapping", fieldMapping);
        return ResponseEntity.ok(ret);
    }

    @GetMapping("/transform-rules")
    public ResponseEntity<?> transformRules() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("table_name", "control_hitch_error_mothod");
        ret.put("table_display_name", "Control Hitch错误日志表");
        ret.put("description", "将Control服务日志数据按照特定规则转换并写入数据库，用于错误分析和统计");
        ret.put("field_config", ControlHitchProcessor.DEFAULT_FIELD_CONFIG);

        Map<String, Object> ex1In = new LinkedHashMap<>();
        ex1In.put("content", "[biz_worker_pool-thread-342] method:orderStatusUpdate,failed," +
                "time cost:1006,reason:请求过于频繁 BizException(code=700000, desc=请求过于频繁)");
        Map<String, Object> ex1Out = new LinkedHashMap<>();
        ex1Out.put("method_name", "orderStatusUpdate");
        ex1Out.put("error_code", "700000");
        ex1Out.put("error_message", "请求过于频繁");
        ex1Out.put("count", 1);
        ex1Out.put("total_count", 1);
        Map<String, Object> ex1 = new LinkedHashMap<>();
        ex1.put("input", ex1In);
        ex1.put("output", ex1Out);
        ret.put("transform_examples", Collections.singletonList(ex1));

        // 尝试读 DB 配置
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT table_display_name, description, field_config " +
                    "FROM topic_table_mappings WHERE table_name = ?", "control_hitch_error_mothod");
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                if (r.get("field_config") instanceof String) {
                    try {
                        ret.put("field_config", com.loganalysis.common.util.JsonUtil.mapper()
                                .readValue((String) r.get("field_config"),
                                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
                    } catch (Exception ignored) {}
                }
                if (r.get("table_display_name") != null) ret.put("table_display_name", r.get("table_display_name"));
                if (r.get("description") != null) ret.put("description", r.get("description"));
            }
        } catch (Exception ignore) {}
        return ResponseEntity.ok(ret);
    }

    @GetMapping("/processor-types")
    public ResponseEntity<?> processorTypes() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", "control_hitch_error");
        item.put("label", "Control Hitch错误日志处理器");
        item.put("description", "将Control日志按照特定规则转换并写入control_hitch_error_mothod表");
        item.put("target_table", "control_hitch_error_mothod");
        return ResponseEntity.ok(Collections.singletonMap("types", Collections.singletonList(item)));
    }

    @PostMapping("/clear-cache")
    public ResponseEntity<?> clearCache() {
        processor.clearConfigCache();
        return ResponseEntity.ok(Collections.singletonMap("message", "缓存已清除"));
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/transform-test")
    public ResponseEntity<?> transformTest(@RequestBody Map<String, Object> body) {
        Object logData = body.get("log_data");
        if (!(logData instanceof Map)) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "缺少日志数据");
            return ResponseEntity.badRequest().body(err);
        }
        processor.clearConfigCache();
        Map<String, Object> result = processor.transformLog((Map<String, Object>) logData, null);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("success", true);
        ret.put("result", result);
        return ResponseEntity.ok(ret);
    }

    // ============ helpers ============

    private static Map<String, String> col(String name, String type, String desc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name); m.put("type", type); m.put("description", desc);
        return m;
    }
    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
    private static String strDefault(Object v, String def) {
        String s = str(v);
        return s == null || s.isEmpty() ? def : s;
    }
    private static long toLong(Object v, long def) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return def;
    }
    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }
}
