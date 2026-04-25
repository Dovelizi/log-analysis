package com.loganalysis.controller;

import com.loganalysis.service.ClsQueryService;
import com.loganalysis.service.GwHitchProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * GW Hitch 处理器 Controller
 * 对齐 routes/gw_hitch_routes.py，前缀 /api/gw-hitch。
 */
@RestController
@RequestMapping("/api/gw-hitch")
public class GwHitchController {

    @Autowired
    private GwHitchProcessor processor;

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
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("message", "处理完成");
        ret.put("result", result);
        return ResponseEntity.ok(ret);
    }

    @PostMapping("/collect")
    public ResponseEntity<?> collect(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> data = body == null ? new HashMap<>() : body;
        Object credId = data.get("credential_id");
        String topicId = str(data.get("topic_id"));
        String query = strDefault(data.get("query"), "*");
        if (credId == null || topicId == null || topicId.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "缺少credential_id或topic_id参数");
            return ResponseEntity.badRequest().body(err);
        }

        // 查 topic region
        String region = null;
        try {
            List<String> r = jdbc.query(
                    "SELECT region FROM log_topics WHERE topic_id = ? LIMIT 1",
                    (rs, i) -> rs.getString(1), topicId);
            if (!r.isEmpty()) region = r.get(0);
        } catch (Exception ignore) {}

        long now = System.currentTimeMillis();
        long fromTime = toLong(data.get("from_time"), now - 3600_000L);
        long toTime = toLong(data.get("to_time"), now);
        int limit = toInt(data.get("limit"), 100);

        Map<String, Object> clsResponse = clsQueryService.searchLog(
                toLong(credId, 0L), topicId, query, fromTime, toTime, limit, "desc", 1, region);

        processor.clearConfigCache();
        Map<String, Object> result = processor.processClsResponse(clsResponse, null, null);

        int cnt = 0;
        if (clsResponse.get("Response") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = (Map<String, Object>) clsResponse.get("Response");
            if (resp.get("Results") instanceof List) cnt = ((List<?>) resp.get("Results")).size();
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("message", "采集并处理完成");
        ret.put("result", result);
        ret.put("cls_response_count", cnt);
        return ResponseEntity.ok(ret);
    }

    // ============ 辅助接口 ============

    @GetMapping("/schema")
    public ResponseEntity<?> schema() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("table_name", "gw_hitch_error_mothod");
        ret.put("description", "网关顺风车业务错误方法聚合监控表");
        ret.put("columns", Arrays.asList(
                col("id", "BIGINT", "主键ID，自增"),
                col("method_name", "VARCHAR(255)", "发生异常的接口或方法名称"),
                col("error_code", "INT", "错误码"),
                col("error_message", "VARCHAR(1024)", "错误信息"),
                col("content", "VARCHAR(10240)", "响应内容"),
                col("count", "INT", "单次聚合周期内的错误次数"),
                col("total_count", "BIGINT", "历史累计错误总次数"),
                col("create_time", "TIMESTAMP", "记录创建时间"),
                col("update_time", "TIMESTAMP", "记录最后更新时间")));
        // field_mapping 字段说明（对齐 Python 版）
        Map<String, String> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("method_name", "path (去掉前缀，从第一个/开始)");
        fieldMapping.put("error_code", "response_body.resData.code 或 errCode");
        fieldMapping.put("error_message", "response_body.resData.message 或 errMsg");
        fieldMapping.put("content", "response_body原始值（截取前10240字符）");
        fieldMapping.put("count", "单次聚合周期内的错误次数");
        fieldMapping.put("total_count", "历史累计错误总次数");
        fieldMapping.put("create_time", "记录创建时间");
        fieldMapping.put("update_time", "记录最后更新时间");
        ret.put("field_mapping", fieldMapping);
        return ResponseEntity.ok(ret);
    }

    @GetMapping("/transform-rules")
    public ResponseEntity<?> transformRules() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("table_name", "gw_hitch_error_mothod");
        ret.put("table_display_name", "GW Hitch错误日志表");
        ret.put("description", "将GW日志数据按照特定规则转换并写入数据库，用于错误分析和统计");
        ret.put("field_config", GwHitchProcessor.DEFAULT_FIELD_CONFIG);

        // transform_examples
        Map<String, Object> ex1In = new LinkedHashMap<>();
        ex1In.put("path", "POST /hitchride/order/addition");
        ex1In.put("response_body",
                "{\"errCode\":0,\"errMsg\":\"success\",\"resData\":{\"code\":37,\"message\":\"出发时间太近，追单失败\"}}");
        Map<String, Object> ex1Out = new LinkedHashMap<>();
        ex1Out.put("method_name", "/hitchride/order/addition");
        ex1Out.put("error_code", 37);
        ex1Out.put("error_message", "出发时间太近，追单失败");
        ex1Out.put("count", 1);
        ex1Out.put("total_count", 1);

        Map<String, Object> ex1 = new LinkedHashMap<>();
        ex1.put("input", ex1In);
        ex1.put("output", ex1Out);

        // 第 2 个 example（对齐 Python: GET /api/user/info）
        Map<String, Object> ex2In = new LinkedHashMap<>();
        ex2In.put("path", "GET /api/user/info");
        ex2In.put("response_body", "{\"errCode\":500,\"errMsg\":\"系统错误\"}");
        Map<String, Object> ex2Out = new LinkedHashMap<>();
        ex2Out.put("method_name", "/api/user/info");
        ex2Out.put("error_code", 500);
        ex2Out.put("error_message", "系统错误");
        ex2Out.put("count", 1);
        ex2Out.put("total_count", 1);
        Map<String, Object> ex2 = new LinkedHashMap<>();
        ex2.put("input", ex2In);
        ex2.put("output", ex2Out);

        ret.put("transform_examples", Arrays.asList(ex1, ex2));

        // 尝试读 DB 配置
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT table_display_name, description, field_config " +
                    "FROM topic_table_mappings WHERE table_name = ?", "gw_hitch_error_mothod");
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                if (r.get("field_config") instanceof String) {
                    try {
                        ret.put("field_config", com.loganalysis.util.JsonUtil.mapper()
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
        item.put("value", "gw_hitch_error");
        item.put("label", "GW Hitch错误日志处理器");
        item.put("description", "将GW日志按照特定规则转换并写入gw_hitch_error_mothod表");
        item.put("target_table", "gw_hitch_error_mothod");
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
