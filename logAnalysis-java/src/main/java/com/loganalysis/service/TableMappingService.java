package com.loganalysis.service;

import com.loganalysis.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Topic 与数据表的映射管理服务。
 * 对齐 Python models/table_mapping.py + routes/table_mapping_routes.py。
 *
 * 核心复杂点（均已做白名单校验）：
 *   1. 动态建表 / ALTER ADD COLUMN：表名/列名正则校验 + 类型白名单
 *   2. 动态 order by：列名白名单（必须存在于 DESCRIBE 结果）+ 方向 ASC/DESC
 *   3. 动态 SELECT 列：同样通过 DESCRIBE 校验后再拼
 *
 * ⚠️ 所有动态 SQL 都必须先过 _validateIdentifier 或 DESCRIBE 白名单校验，
 *    禁止把用户输入直接拼 SQL。
 */
@Service
public class TableMappingService {

    private static final Logger log = LoggerFactory.getLogger(TableMappingService.class);

    /** 合法的 SQL 标识符（表名/列名） */
    private static final Pattern IDENT = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    /** 保留字黑名单（不允许作为表名） */
    private static final Set<String> RESERVED = Set.of(
            "select", "insert", "update", "delete", "drop", "create", "table", "index");

    /** 字段类型白名单 → MySQL 列类型（来自原 FIELD_TYPES_MYSQL） */
    public static final Map<String, String> FIELD_TYPES = new LinkedHashMap<>();
    static {
        FIELD_TYPES.put("TEXT", "TEXT");
        FIELD_TYPES.put("VARCHAR", "VARCHAR(255)");
        FIELD_TYPES.put("INTEGER", "INT");
        FIELD_TYPES.put("REAL", "DOUBLE");
        FIELD_TYPES.put("BLOB", "BLOB");
        FIELD_TYPES.put("TIMESTAMP", "TIMESTAMP NULL");
        FIELD_TYPES.put("JSON", "JSON");
    }

    /** 允许用户自定义的列类型前缀白名单（自定义类型必须以这些开头） */
    private static final Set<String> CUSTOM_TYPE_PREFIXES = Set.of(
            "VARCHAR", "CHAR", "INT", "BIGINT", "SMALLINT", "TINYINT",
            "DECIMAL", "FLOAT", "DOUBLE", "DATE", "DATETIME", "TIMESTAMP",
            "TEXT", "MEDIUMTEXT", "LONGTEXT", "BLOB", "MEDIUMBLOB", "LONGBLOB", "JSON"
    );

    /** 括号内参数必须只含数字和逗号（可带空格），如 "100" 或 "10,2" */
    private static final Pattern CUSTOM_TYPE_ARGS = Pattern.compile("^\\s*\\d+\\s*(,\\s*\\d+\\s*)?$");

    /** TEXT / BLOB / JSON 等 MySQL 中不能加 DEFAULT 的类型关键字 */
    private static final Set<String> NO_DEFAULT_KEYWORDS = Set.of("TEXT", "BLOB", "JSON", "GEOMETRY");

    @Autowired
    private JdbcTemplate jdbc;

    // ============================== 校验 ==============================

    public static void validateTableName(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("表名 '" + name + "' 不合法，只能包含字母、数字、下划线，且必须以字母开头");
        }
        if (RESERVED.contains(name.toLowerCase())) {
            throw new IllegalArgumentException("表名 '" + name + "' 是保留关键字");
        }
    }

    public static void validateColumnName(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("列名 '" + name + "' 不合法");
        }
    }

    /**
     * 校验并规范化列类型：
     *   - 若在 FIELD_TYPES 中，返回映射值；
     *   - 若是以白名单关键字开头的自定义类型（如 VARCHAR(100)、DECIMAL(10,2)），直接返回；
     *     自定义类型只接受 "KEYWORD" 或 "KEYWORD(...)"，不允许空格、分号等任何其它字符，
     *     以避免 "TEXT; DROP TABLE x" 这类注入。
     *   - 否则拒绝。
     */
    public static String normalizeColumnType(String fieldType) {
        if (fieldType == null || fieldType.isEmpty()) return "TEXT";
        String mapped = FIELD_TYPES.get(fieldType);
        if (mapped != null) return mapped;
        String trimmed = fieldType.trim();
        String upper = trimmed.toUpperCase();
        for (String p : CUSTOM_TYPE_PREFIXES) {
            if (upper.equals(p)) return trimmed;
            if (upper.startsWith(p + "(") && upper.endsWith(")")
                    && CUSTOM_TYPE_ARGS.matcher(upper.substring(p.length() + 1, upper.length() - 1)).matches()) {
                return trimmed;
            }
        }
        throw new IllegalArgumentException("不支持的字段类型: " + fieldType);
    }

    /** 检查列名是否在 DESCRIBE 白名单中 */
    public boolean tableHasColumn(String table, String column) {
        validateTableName(table);
        List<String> cols = describeTable(table);
        return cols.contains(column);
    }

    /** DESCRIBE 表返回全部列名 */
    public List<String> describeTable(String table) {
        validateTableName(table);
        try {
            return jdbc.query("DESCRIBE `" + table + "`",
                    (rs, i) -> rs.getString("Field"));
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    public boolean tableExists(String table) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SHOW TABLES LIKE ?", table);
            return !rows.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ============================== 查询映射 ==============================

    public List<Map<String, Object>> getAllMappings() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.*, t.topic_id as cls_topic_id, t.topic_name, c.name as credential_name " +
                "FROM topic_table_mappings m " +
                "JOIN log_topics t ON m.topic_id = t.id " +
                "JOIN api_credentials c ON t.credential_id = c.id " +
                "ORDER BY m.created_at DESC");
        for (Map<String, Object> r : rows) postProcessMappingRow(r);
        return rows;
    }

    public Map<String, Object> getMapping(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.*, t.topic_id as cls_topic_id, t.topic_name, c.name as credential_name " +
                "FROM topic_table_mappings m " +
                "JOIN log_topics t ON m.topic_id = t.id " +
                "JOIN api_credentials c ON t.credential_id = c.id " +
                "WHERE m.id = ?", id);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        postProcessMappingRow(r);
        return r;
    }

    public List<Map<String, Object>> getMappingsByTopic(long topicId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT m.*, t.topic_id as cls_topic_id, t.topic_name " +
                "FROM topic_table_mappings m JOIN log_topics t ON m.topic_id = t.id " +
                "WHERE m.topic_id = ?", topicId);
        for (Map<String, Object> r : rows) postProcessMappingRow(r);
        return rows;
    }

    /**
     * 创建映射关系 + 同步创建目标表 + 写入 field_mappings。
     * 失败时会尝试回滚（包括 DROP 已创建的物理表）。
     */
    @Transactional
    public long createMapping(long topicId, String tableName,
                              List<Map<String, Object>> fieldConfig,
                              String displayName, String description,
                              boolean autoCollect,
                              Map<String, Object> filterConfig) {
        validateTableName(tableName);
        if (fieldConfig == null || fieldConfig.isEmpty()) {
            throw new IllegalArgumentException("字段配置不能为空");
        }
        for (Map<String, Object> f : fieldConfig) {
            validateColumnName(str(f.get("name")));
        }

        boolean tableCreated = false;
        try {
            // 1. 创建数据表
            createDataTable(tableName, fieldConfig);
            tableCreated = true;

            // 2. 写 topic_table_mappings
            KeyHolder kh = new GeneratedKeyHolder();
            String fcJson = JsonUtil.toJson(fieldConfig);
            String filterJson = filterConfig == null ? null : JsonUtil.toJson(filterConfig);
            jdbc.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO topic_table_mappings " +
                        "(topic_id, table_name, table_display_name, description, field_config, filter_config, auto_collect) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, topicId);
                ps.setString(2, tableName);
                ps.setString(3, displayName == null || displayName.isEmpty() ? tableName : displayName);
                ps.setString(4, description);
                ps.setString(5, fcJson);
                ps.setString(6, filterJson);
                ps.setInt(7, autoCollect ? 1 : 0);
                return ps;
            }, kh);
            Number key = kh.getKey();
            long mappingId = key == null ? 0L : key.longValue();

            // 3. 写 field_mappings
            for (Map<String, Object> f : fieldConfig) {
                String source = firstNonEmpty(str(f.get("source")), str(f.get("name")), "");
                jdbc.update("INSERT INTO field_mappings " +
                           "(mapping_id, source_field, target_column, field_type, is_required, default_value, transform_rule) " +
                           "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        mappingId,
                        source,
                        f.get("name"),
                        firstNonEmpty(str(f.get("type")), "TEXT"),
                        Boolean.TRUE.equals(f.get("required")) ? 1 : 0,
                        f.get("default"),
                        f.get("transform"));
            }
            return mappingId;
        } catch (DuplicateKeyException e) {
            if (tableCreated) safeDropTable(tableName);
            throw new IllegalArgumentException("创建映射失败: 表名已存在");
        } catch (RuntimeException e) {
            if (tableCreated) safeDropTable(tableName);
            throw e;
        }
    }

    public void updateMapping(long mappingId, Map<String, Object> data) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (String col : new String[]{"table_display_name", "description", "auto_collect", "status"}) {
            if (data.containsKey(col) && data.get(col) != null) {
                sets.add(col + " = ?");
                args.add(data.get(col));
            }
        }
        if (data.containsKey("field_config") && data.get("field_config") != null) {
            sets.add("field_config = ?");
            Object fc = data.get("field_config");
            args.add(fc instanceof String ? fc : JsonUtil.toJson(fc));
        }
        if (data.containsKey("filter_config")) {
            Object fc = data.get("filter_config");
            sets.add("filter_config = ?");
            args.add(fc == null ? null : (fc instanceof String ? fc : JsonUtil.toJson(fc)));
        }
        if (sets.isEmpty()) return;
        args.add(mappingId);
        jdbc.update("UPDATE topic_table_mappings SET " + String.join(", ", sets) + " WHERE id = ?",
                args.toArray());
    }

    /** 更新字段映射 + 按需对数据表加列。对齐 Python update_field_mappings。 */
    @Transactional
    public void updateFieldMappings(long mappingId, List<Map<String, Object>> fieldConfig) {
        Map<String, Object> mapping = getMappingRaw(mappingId);
        if (mapping == null) throw new IllegalArgumentException("映射配置不存在");
        String tableName = str(mapping.get("table_name"));
        validateTableName(tableName);

        // 确保表存在
        if (!tableExists(tableName)) {
            createDataTable(tableName, fieldConfig);
        }

        // 已有列（小写，忽略大小写）
        Set<String> existing = new HashSet<>();
        for (String c : describeTable(tableName)) existing.add(c.toLowerCase());

        // 加列
        for (Map<String, Object> f : fieldConfig) {
            String col = str(f.get("name"));
            if (col == null || col.isEmpty()) continue;
            validateColumnName(col);
            if (existing.contains(col.toLowerCase())) continue;

            String typeRaw = firstNonEmpty(str(f.get("type")), "TEXT");
            String colType = normalizeColumnType(typeRaw);
            StringBuilder alter = new StringBuilder("ALTER TABLE `").append(tableName)
                    .append("` ADD COLUMN `").append(col).append("` ").append(colType);

            Object def = f.get("default");
            if (def != null && !"".equals(def) && !isDbDefault(def)
                    && !isNoDefaultType(typeRaw.toUpperCase())) {
                if (def instanceof Number) alter.append(" DEFAULT ").append(def);
                else alter.append(" DEFAULT '").append(String.valueOf(def).replace("'", "''")).append("'");
            }
            jdbc.execute(alter.toString());
        }

        // 重写 field_mappings
        jdbc.update("DELETE FROM field_mappings WHERE mapping_id = ?", mappingId);
        for (Map<String, Object> f : fieldConfig) {
            String source = firstNonEmpty(str(f.get("source")), str(f.get("name")), "");
            jdbc.update("INSERT INTO field_mappings " +
                       "(mapping_id, source_field, target_column, field_type, is_required, default_value, transform_rule) " +
                       "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    mappingId, source, f.get("name"),
                    firstNonEmpty(str(f.get("type")), "TEXT"),
                    Boolean.TRUE.equals(f.get("required")) ? 1 : 0,
                    f.get("default"),
                    f.get("transform"));
        }

        // 同步 topic_table_mappings.field_config
        jdbc.update("UPDATE topic_table_mappings SET field_config = ? WHERE id = ?",
                JsonUtil.toJson(fieldConfig), mappingId);
    }

    public int deleteMapping(long mappingId, boolean dropTable) {
        Map<String, Object> m = getMappingRaw(mappingId);
        if (m == null) return 0;
        if (dropTable) {
            String tableName = str(m.get("table_name"));
            validateTableName(tableName);
            jdbc.execute("DROP TABLE IF EXISTS `" + tableName + "`");
        }
        return jdbc.update("DELETE FROM topic_table_mappings WHERE id = ?", mappingId);
    }

    public List<Map<String, Object>> getFieldMappings(long mappingId) {
        return jdbc.queryForList(
                "SELECT * FROM field_mappings WHERE mapping_id = ? ORDER BY id", mappingId);
    }

    // ============================== 表数据查询 ==============================

    /**
     * 获取表数据（对齐 Python get_table_data）
     * - order_by 必须在实际列白名单中
     * - order_dir 只允许 ASC/DESC
     * - display_columns 是要显示的列白名单（仅保留存在的列）
     */
    public Map<String, Object> getTableData(String tableName, int limit, int offset,
                                            String orderBy, String orderDir,
                                            List<String> displayColumns) {
        validateTableName(tableName);
        String dir = (orderDir != null && orderDir.equalsIgnoreCase("ASC")) ? "ASC" : "DESC";

        List<String> allColumns = describeTable(tableName);
        if (allColumns.isEmpty()) throw new IllegalArgumentException("表不存在或无法读取列: " + tableName);

        List<String> columns;
        if (displayColumns != null && !displayColumns.isEmpty()) {
            columns = new ArrayList<>();
            for (String c : displayColumns) if (allColumns.contains(c)) columns.add(c);
            if (columns.isEmpty()) columns = allColumns;
        } else {
            columns = allColumns;
        }

        String safeOrderBy = allColumns.contains(orderBy) ? orderBy : "id";

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM `" + tableName + "` ORDER BY `" + safeOrderBy + "` " + dir +
                " LIMIT ? OFFSET ?", limit, offset);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM `" + tableName + "`", Long.class);

        List<Map<String, Object>> filtered = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String c : columns) out.put(c, r.get(c));
            filtered.add(out);
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("columns", columns);
        ret.put("data", filtered);
        ret.put("total", total == null ? 0L : total);
        ret.put("limit", limit);
        ret.put("offset", offset);
        return ret;
    }

    /** 获取表结构（对齐 Python get_table_schema） */
    public List<Map<String, Object>> getTableSchema(String tableName) {
        validateTableName(tableName);
        return jdbc.query("DESCRIBE `" + tableName + "`", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rs.getString("Field"));
            m.put("type", rs.getString("Type"));
            m.put("notnull", "NO".equals(rs.getString("Null")));
            m.put("dflt_value", rs.getString("Default"));
            m.put("pk", "PRI".equals(rs.getString("Key")));
            return m;
        });
    }

    // ============================== 采集日志 ==============================

    public void logCollection(long mappingId, int collectedCount, int successCount, int errorCount, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO collection_logs " +
                   "(mapping_id, collected_count, success_count, error_count, error_message, started_at, finished_at) " +
                   "VALUES (?, ?, ?, ?, ?, ?, ?)",
                mappingId, collectedCount, successCount, errorCount, errorMessage, now, now);
    }

    public Map<String, Object> getCollectionLogs(Long mappingId, int limit, int offset) {
        long total;
        List<Map<String, Object>> rows;
        if (mappingId != null) {
            Long t = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM collection_logs WHERE mapping_id = ?", Long.class, mappingId);
            total = t == null ? 0L : t;
            rows = jdbc.queryForList(
                    "SELECT cl.*, m.table_name, m.table_display_name " +
                    "FROM collection_logs cl JOIN topic_table_mappings m ON cl.mapping_id = m.id " +
                    "WHERE cl.mapping_id = ? ORDER BY cl.created_at DESC LIMIT ? OFFSET ?",
                    mappingId, limit, offset);
        } else {
            Long t = jdbc.queryForObject("SELECT COUNT(*) FROM collection_logs", Long.class);
            total = t == null ? 0L : t;
            rows = jdbc.queryForList(
                    "SELECT cl.*, m.table_name, m.table_display_name " +
                    "FROM collection_logs cl JOIN topic_table_mappings m ON cl.mapping_id = m.id " +
                    "ORDER BY cl.created_at DESC LIMIT ? OFFSET ?",
                    limit, offset);
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("data", rows);
        ret.put("total", total);
        ret.put("limit", limit);
        ret.put("offset", offset);
        return ret;
    }

    public Map<String, Object> statistics(Long mappingId) {
        String sql = mappingId != null
                ? "SELECT COUNT(*) as total_collections, COALESCE(SUM(collected_count), 0) as total_collected, " +
                  "COALESCE(SUM(success_count), 0) as total_success, COALESCE(SUM(error_count), 0) as total_errors, " +
                  "MAX(finished_at) as last_collection FROM collection_logs WHERE mapping_id = ?"
                : "SELECT COUNT(*) as total_collections, COALESCE(SUM(collected_count), 0) as total_collected, " +
                  "COALESCE(SUM(success_count), 0) as total_success, COALESCE(SUM(error_count), 0) as total_errors, " +
                  "MAX(finished_at) as last_collection FROM collection_logs";
        Map<String, Object> row = mappingId != null
                ? jdbc.queryForMap(sql, mappingId)
                : jdbc.queryForMap(sql);
        return row;
    }

    // ============================== 内部 ==============================

    private Map<String, Object> getMappingRaw(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM topic_table_mappings WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private static void postProcessMappingRow(Map<String, Object> row) {
        Object fc = row.get("field_config");
        if (fc instanceof String) {
            try {
                List<Map<String, Object>> list =
                        JsonUtil.mapper().readValue((String) fc,
                                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                row.put("field_config", list);
            } catch (Exception ignore) { /* keep raw */ }
        }
        Object filter = row.get("filter_config");
        if (filter instanceof String && !((String) filter).isEmpty()) {
            Map<String, Object> parsed = JsonUtil.toMap((String) filter);
            if (parsed != null) row.put("filter_config", parsed);
        }
        for (String k : new String[]{"created_at", "updated_at"}) {
            Object v = row.get(k);
            if (v instanceof LocalDateTime) row.put(k, v.toString());
            else if (v instanceof java.sql.Timestamp) row.put(k, ((java.sql.Timestamp) v).toLocalDateTime().toString());
        }
    }

    /** 创建数据表 */
    private void createDataTable(String tableName, List<Map<String, Object>> fieldConfig) {
        validateTableName(tableName);
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS `").append(tableName)
                .append("` (id INT PRIMARY KEY AUTO_INCREMENT");
        for (Map<String, Object> f : fieldConfig) {
            String col = str(f.get("name"));
            if (col == null || "id".equalsIgnoreCase(col)) continue;
            validateColumnName(col);
            String typeRaw = firstNonEmpty(str(f.get("type")), "TEXT");
            String colType = normalizeColumnType(typeRaw);
            sql.append(", `").append(col).append("` ").append(colType);
            if (Boolean.TRUE.equals(f.get("required"))) sql.append(" NOT NULL");
            Object def = f.get("default");
            if (def != null && !"".equals(def) && !isDbDefault(def)
                    && !isNoDefaultType(typeRaw.toUpperCase())) {
                if (def instanceof Number) sql.append(" DEFAULT ").append(def);
                else sql.append(" DEFAULT '").append(String.valueOf(def).replace("'", "''")).append("'");
            }
        }
        sql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute(sql.toString());
    }

    private void safeDropTable(String table) {
        try {
            validateTableName(table);
            jdbc.execute("DROP TABLE IF EXISTS `" + table + "`");
        } catch (Exception e) {
            log.warn("DROP 失败（忽略）: {}", e.getMessage());
        }
    }

    private static boolean isDbDefault(Object v) {
        if (v == null) return true;
        String s = String.valueOf(v);
        return "CURRENT_TIMESTAMP".equals(s) || "NOW()".equals(s) || "NULL".equals(s);
    }

    private static boolean isNoDefaultType(String upperType) {
        for (String kw : NO_DEFAULT_KEYWORDS) if (upperType.contains(kw)) return true;
        return false;
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }

    private static String firstNonEmpty(String... ss) {
        for (String s : ss) if (s != null && !s.isEmpty()) return s;
        return null;
    }
}
