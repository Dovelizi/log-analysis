package com.loganalysis.tablemapping.interfaces.rest;

import com.loganalysis.tablemapping.application.TableMappingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Topic-数据表映射管理 Controller
 * 对齐 routes/table_mapping_routes.py，前缀 /api/table-mappings。
 */
@RestController
@RequestMapping("/api/table-mappings")
public class TableMappingController {

    @Autowired
    private TableMappingService service;

    // ============ 映射 CRUD ============

    @GetMapping
    public ResponseEntity<?> listAll() {
        return ResponseEntity.ok(service.getAllMappings());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable long id) {
        Map<String, Object> m = service.getMapping(id);
        if (m == null) return notFound("映射配置不存在");
        return ResponseEntity.ok(m);
    }

    @GetMapping("/by-topic/{topicId}")
    public ResponseEntity<?> byTopic(@PathVariable long topicId) {
        return ResponseEntity.ok(service.getMappingsByTopic(topicId));
    }

    @SuppressWarnings("unchecked")
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Object topicId = body.get("topic_id");
        String tableName = str(body.get("table_name"));
        Object fcRaw = body.get("field_config");
        if (topicId == null || tableName == null || tableName.isEmpty() || !(fcRaw instanceof List)) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "缺少必要参数");
            return ResponseEntity.badRequest().body(err);
        }
        List<Map<String, Object>> fc = (List<Map<String, Object>>) fcRaw;
        long id = service.createMapping(
                toLong(topicId),
                tableName,
                fc,
                str(body.get("table_display_name")),
                str(body.get("description")),
                body.get("auto_collect") == null || Boolean.TRUE.equals(body.get("auto_collect")),
                (Map<String, Object>) body.get("filter_config"));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("message", "创建成功");
        ok.put("mapping_id", id);
        return ResponseEntity.status(201).body(ok);
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (service.getMapping(id) == null) return notFound("映射配置不存在");
        service.updateMapping(id, body);
        if (body.get("field_config") instanceof List) {
            service.updateFieldMappings(id, (List<Map<String, Object>>) body.get("field_config"));
        }
        return ResponseEntity.ok(Collections.singletonMap("message", "更新成功"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id,
                                    @RequestParam(value = "drop_table", defaultValue = "false") boolean dropTable) {
        if (service.getMapping(id) == null) return notFound("映射配置不存在");
        service.deleteMapping(id, dropTable);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("message", "删除成功");
        ret.put("table_dropped", dropTable);
        return ResponseEntity.ok(ret);
    }

    // ============ 字段 / 数据 / schema ============

    @GetMapping("/{id}/fields")
    public ResponseEntity<?> fields(@PathVariable long id) {
        return ResponseEntity.ok(service.getFieldMappings(id));
    }

    @GetMapping("/{id}/data")
    public ResponseEntity<?> data(@PathVariable long id,
                                  @RequestParam(defaultValue = "100") int limit,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(value = "order_by", defaultValue = "id") String orderBy,
                                  @RequestParam(value = "order_dir", defaultValue = "DESC") String orderDir) {
        Map<String, Object> m = service.getMapping(id);
        if (m == null) return notFound("映射配置不存在");
        String tableName = str(m.get("table_name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fc = (List<Map<String, Object>>) m.get("field_config");
        List<String> displayColumns = null;
        if (fc != null) {
            displayColumns = new ArrayList<>();
            for (Map<String, Object> f : fc) {
                Object n = f.get("name");
                if (n != null) displayColumns.add(n.toString());
            }
        }
        return ResponseEntity.ok(service.getTableData(tableName, limit, offset, orderBy, orderDir, displayColumns));
    }

    @GetMapping("/{id}/schema")
    public ResponseEntity<?> schema(@PathVariable long id) {
        Map<String, Object> m = service.getMapping(id);
        if (m == null) return notFound("映射配置不存在");
        String tableName = str(m.get("table_name"));
        return ResponseEntity.ok(service.getTableSchema(tableName));
    }

    // ============ 采集日志 / 统计 ============

    @GetMapping("/collection-logs")
    public ResponseEntity<?> collectionLogs(@RequestParam(value = "mapping_id", required = false) Long mappingId,
                                            @RequestParam(defaultValue = "20") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.getCollectionLogs(mappingId, limit, offset));
    }

    @GetMapping("/statistics")
    public ResponseEntity<?> statistics(@RequestParam(value = "mapping_id", required = false) Long mappingId) {
        return ResponseEntity.ok(service.statistics(mappingId));
    }

    // ============ 辅助接口（前端下拉值） ============

    @GetMapping("/field-types")
    public ResponseEntity<?> fieldTypes() {
        List<Map<String, String>> types = Arrays.asList(
                typeEntry("TEXT", "文本", "字符串类型"),
                typeEntry("INTEGER", "整数", "整数类型"),
                typeEntry("REAL", "浮点数", "浮点数类型"),
                typeEntry("TIMESTAMP", "时间戳", "时间类型"),
                typeEntry("JSON", "JSON", "JSON对象类型"),
                typeEntry("BLOB", "二进制", "二进制数据"));
        return ResponseEntity.ok(Collections.singletonMap("types", types));
    }

    @GetMapping("/transform-rules")
    public ResponseEntity<?> transformRules() {
        List<Map<String, String>> rules = Arrays.asList(
                ruleEntry("trim", "去除空白", "trim"),
                ruleEntry("lower", "转小写", "lower"),
                ruleEntry("upper", "转大写", "upper"),
                ruleEntry("regex", "正则提取", "regex:pattern:group"),
                ruleEntry("split", "分割取值", "split:,:0"),
                ruleEntry("replace", "替换", "replace:old:new"),
                ruleEntry("json_path", "JSON路径", "json_path:data.value"));
        return ResponseEntity.ok(Collections.singletonMap("rules", rules));
    }

    @GetMapping("/filter-operators")
    public ResponseEntity<?> filterOperators() {
        String[][] data = {
                {"equals", "等于", "字段值等于指定值"},
                {"not_equals", "不等于", "字段值不等于指定值"},
                {"contains", "包含", "字段值包含指定字符串"},
                {"not_contains", "不包含", "字段值不包含指定字符串"},
                {"starts_with", "以...开头", "字段值以指定字符串开头"},
                {"ends_with", "以...结尾", "字段值以指定字符串结尾"},
                {"gt", "大于", "字段值大于指定数值"},
                {"gte", "大于等于", "字段值大于等于指定数值"},
                {"lt", "小于", "字段值小于指定数值"},
                {"lte", "小于等于", "字段值小于等于指定数值"},
                {"in", "在列表中", "字段值在指定列表中（逗号分隔）"},
                {"not_in", "不在列表中", "字段值不在指定列表中（逗号分隔）"},
                {"regex", "正则匹配", "字段值匹配指定正则表达式"},
                {"is_empty", "为空", "字段值为空"},
                {"is_not_empty", "不为空", "字段值不为空"}
        };
        List<Map<String, String>> ops = new ArrayList<>(data.length);
        for (String[] d : data) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("value", d[0]); m.put("label", d[1]); m.put("description", d[2]);
            ops.add(m);
        }
        return ResponseEntity.ok(Collections.singletonMap("operators", ops));
    }

    // ============ helpers ============

    private static ResponseEntity<Map<String, Object>> notFound(String msg) {
        Map<String, Object> err = new HashMap<>();
        err.put("error", msg);
        return ResponseEntity.status(404).body(err);
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(String.valueOf(v));
    }

    private static Map<String, String> typeEntry(String v, String l, String d) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("value", v); m.put("label", l); m.put("description", d);
        return m;
    }

    private static Map<String, String> ruleEntry(String v, String l, String e) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("value", v); m.put("label", l); m.put("example", e);
        return m;
    }
}
