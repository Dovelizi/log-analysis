package com.loganalysis.queryconfig.application;

import com.loganalysis.common.util.JsonUtil;
import com.loganalysis.queryconfig.infrastructure.persistence.mapper.QueryConfigMapper;
import com.loganalysis.queryconfig.infrastructure.persistence.po.QueryConfigPO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * query_configs 表 CRUD。
 *
 * 对齐原 app.py：
 *   GET    /api/query-configs
 *   POST   /api/query-configs
 *   PUT    /api/query-configs/&lt;id&gt;
 *   DELETE /api/query-configs/&lt;id&gt;
 *
 * 列：
 *   id, name, topic_id, query_statement, time_range, limit_count, sort_order,
 *   syntax_rule, processor_type, target_table, transform_config(JSON),
 *   filter_config(JSON), schedule_enabled, schedule_interval, created_at, updated_at
 *
 * P2b-3 起：底层走 MyBatis-Plus {@link QueryConfigMapper}；JOIN 查询继续用 @Select 保留原 SQL。
 */
@Service
public class QueryConfigService {

    @Autowired
    private QueryConfigMapper queryConfigMapper;

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> rows = queryConfigMapper.listAllWithTopic();
        for (Map<String, Object> r : rows) {
            normalizeTimestamps(r);
            parseJsonColumn(r, "transform_config");
            parseJsonColumn(r, "filter_config");
        }
        return rows;
    }

    @Transactional
    public void create(Map<String, Object> data) {
        String name = str(data.get("name"));
        Long topicId = toLong(data.get("topic_id"));
        String queryStatement = str(data.get("query_statement"));
        if (isEmpty(name) || topicId == null || isEmpty(queryStatement)) {
            throw new IllegalArgumentException("缺少必要参数");
        }
        QueryConfigPO po = new QueryConfigPO();
        po.setName(name);
        po.setTopicId(topicId);
        po.setQueryStatement(queryStatement);
        po.setTimeRange(toIntDefault(data.get("time_range"), 3600));
        po.setLimitCount(toIntDefault(data.get("limit_count"), 100));
        po.setSortOrder(strDefault(data.get("sort_order"), "desc"));
        po.setSyntaxRule(toIntDefault(data.get("syntax_rule"), 1));
        po.setProcessorType(str(data.get("processor_type")));
        po.setTargetTable(str(data.get("target_table")));
        po.setTransformConfig(jsonCell(data.get("transform_config")));
        po.setFilterConfig(jsonCell(data.get("filter_config")));
        po.setScheduleEnabled(toIntDefault(data.get("schedule_enabled"), 0));
        po.setScheduleInterval(toIntDefault(data.get("schedule_interval"), 300));
        queryConfigMapper.insert(po);
    }

    @Transactional
    public void update(long id, Map<String, Object> data) {
        QueryConfigPO po = new QueryConfigPO();
        po.setId(id);
        po.setName(str(data.get("name")));
        po.setQueryStatement(str(data.get("query_statement")));
        po.setTimeRange(toIntDefault(data.get("time_range"), 3600));
        po.setLimitCount(toIntDefault(data.get("limit_count"), 100));
        po.setSortOrder(strDefault(data.get("sort_order"), "desc"));
        po.setSyntaxRule(toIntDefault(data.get("syntax_rule"), 1));
        po.setProcessorType(str(data.get("processor_type")));
        po.setTargetTable(str(data.get("target_table")));
        po.setTransformConfig(jsonCell(data.get("transform_config")));
        po.setFilterConfig(jsonCell(data.get("filter_config")));
        po.setScheduleEnabled(toIntDefault(data.get("schedule_enabled"), 0));
        po.setScheduleInterval(toIntDefault(data.get("schedule_interval"), 300));
        queryConfigMapper.updateById(po);
    }

    @Transactional
    public void delete(long id) {
        // 对齐原 app.py delete_query_config：级联删除关联记录
        queryConfigMapper.deleteLogRecordsByConfigId(id);
        queryConfigMapper.deleteAnalysisResultsByConfigId(id);
        queryConfigMapper.deleteById(id);
    }

    public Map<String, Object> findWithTopic(long id) {
        Map<String, Object> r = queryConfigMapper.findWithTopic(id);
        if (r == null) return null;
        Map<String, Object> out = new LinkedHashMap<>(r);
        parseJsonColumn(out, "transform_config");
        parseJsonColumn(out, "filter_config");
        return out;
    }

    private static void normalizeTimestamps(Map<String, Object> m) {
        for (String k : new String[]{"created_at", "updated_at"}) {
            Object v = m.get(k);
            if (v instanceof LocalDateTime) m.put(k, v.toString());
            else if (v instanceof java.sql.Timestamp) m.put(k, ((java.sql.Timestamp) v).toLocalDateTime().toString());
        }
    }

    private static void parseJsonColumn(Map<String, Object> row, String col) {
        Object v = row.get(col);
        if (v instanceof String) {
            Map<String, Object> parsed = JsonUtil.toMap((String) v);
            if (parsed != null) row.put(col, parsed);
        }
    }

    /** 接收 Map/List（前端 JSON）或字符串，写入时统一成字符串 */
    private static String jsonCell(Object v) {
        if (v == null) return null;
        if (v instanceof String) return ((String) v).isEmpty() ? null : (String) v;
        return JsonUtil.toJson(v);
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static String strDefault(Object v, String def) {
        String s = str(v);
        return (s == null || s.isEmpty()) ? def : s;
    }

    private static int toIntDefault(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }

    private static Long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return null;
    }
}
