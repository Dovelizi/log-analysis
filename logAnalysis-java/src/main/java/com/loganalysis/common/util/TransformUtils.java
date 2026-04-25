package com.loganalysis.common.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的数据转换工具类，Python 版 services/transform_utils.py 的逐条移植。
 *
 * 支持的 transform DSL（多规则用 | 分隔，但规则内部的 | 不参与分割）：
 *   trim / lower / upper
 *   regex:pattern[:group]
 *   regex_fallback:pattern1||pattern2||...[:group]
 *   split:delimiter:index
 *   replace:old:new
 *   substr:length  或 substr:start:end
 *   json_path:path
 *   fallback:field
 *   default_if_empty:value
 *   default_if_timeout[:value]
 *   default_code:value
 *   datetime_parse
 *
 * 所有规则必须与 Python 源码 transform_utils.py 行为严格一致，
 * 否则会影响 gw_hitch / control_hitch 等所有 processor 的入库结果。
 */
public final class TransformUtils {

    private static final List<String> RULE_KEYWORDS = List.of(
            "regex_fallback", "regex", "split", "replace", "trim", "lower", "upper",
            "substr", "substring", "json_path", "fallback",
            "default_if_empty", "default_if_timeout", "default_code", "datetime_parse"
    );

    private static final Pattern DT_SPACE_MILLIS = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+\\d+$");
    private static final Pattern TRAILING_GROUP = Pattern.compile(":(\\d+)$");

    private TransformUtils() {}

    /* ==================== extract_value ==================== */

    /**
     * 对齐 TransformUtils.extract_value：
     * 从嵌套 Map/JSON 中提取字段值，支持 'a.b.c' 路径。
     * 若中间节点是字符串，尝试当作 JSON 解析后继续。
     */
    public static Object extractValue(Object data, String fieldPath) {
        if (fieldPath == null || fieldPath.isEmpty() || data == null) {
            return null;
        }
        Object value = data;
        for (String part : fieldPath.split("\\.")) {
            if (value == null) return null;
            if (value instanceof Map) {
                value = ((Map<?, ?>) value).get(part);
            } else if (value instanceof JsonNode) {
                JsonNode n = (JsonNode) value;
                value = n.has(part) ? n.get(part) : null;
            } else if (value instanceof String) {
                JsonNode node = JsonUtil.safeReadTree((String) value);
                if (node != null && node.has(part)) {
                    value = node.get(part);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }
        return unwrap(value);
    }

    private static Object unwrap(Object v) {
        if (v instanceof JsonNode) {
            JsonNode n = (JsonNode) v;
            if (n.isNull()) return null;
            if (n.isTextual()) return n.asText();
            if (n.isInt()) return n.asInt();
            if (n.isLong()) return n.asLong();
            if (n.isDouble() || n.isFloat()) return n.asDouble();
            if (n.isBoolean()) return n.asBoolean();
            return n.toString();
        }
        return v;
    }

    /* ==================== apply_transform ==================== */

    /** 规则拆分：只在 | 后紧跟规则关键字时拆分，避免拆散正则中的 | */
    static List<String> splitTransforms(String transformRule) {
        List<String> transforms = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String part : transformRule.split("\\|", -1)) {
            String stripped = part.trim();
            boolean isNewRule = false;
            for (String kw : RULE_KEYWORDS) {
                if (stripped.equals(kw) || stripped.startsWith(kw + ":")) {
                    isNewRule = true;
                    break;
                }
            }
            if (isNewRule && !current.isEmpty()) {
                transforms.add(String.join("|", current));
                current.clear();
                current.add(part);
            } else {
                current.add(part);
            }
        }
        if (!current.isEmpty()) transforms.add(String.join("|", current));
        return transforms;
    }

    /** 对齐 TransformUtils.apply_transform */
    public static Object applyTransform(Object value, String transformRule, Map<String, Object> fullData) {
        if (transformRule == null || transformRule.isEmpty()) {
            return value;
        }
        Object result = value;
        for (String rule : splitTransforms(transformRule)) {
            String trimmed = rule.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(":", -1);
            String type = parts[0];
            try {
                result = applyOne(result, type, parts, trimmed, fullData);
            } catch (Exception ignore) {
                // 与 Python 的 except Exception: pass 对齐
            }
        }
        return result;
    }

    private static Object applyOne(Object result, String type, String[] parts, String wholeRule, Map<String, Object> fullData) {
        switch (type) {
            case "trim":
                return result == null ? null : String.valueOf(result).trim();
            case "lower":
                return result == null ? null : String.valueOf(result).toLowerCase();
            case "upper":
                return result == null ? null : String.valueOf(result).toUpperCase();

            case "regex": {
                if (parts.length < 2 || result == null) return result;
                String s = String.valueOf(result);
                String sUn = s.replace("\\\"", "\"").replace("\\\\", "\\");
                String last = parts[parts.length - 1];
                int group;
                String pattern;
                if (last.matches("\\d+")) {
                    group = Integer.parseInt(last);
                    pattern = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
                } else {
                    group = 0;
                    pattern = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length));
                }
                Matcher m = Pattern.compile(pattern).matcher(sUn);
                if (!m.find()) {
                    m = Pattern.compile(pattern).matcher(s);
                    if (!m.find()) return null;
                }
                return group <= m.groupCount() ? m.group(group) : m.group(0);
            }

            case "regex_fallback": {
                if (parts.length < 2 || result == null) return result;
                String s = String.valueOf(result);
                String sUn = s.replace("\\\"", "\"").replace("\\\\", "\\");
                String full = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length));
                int group = 1;
                Matcher gm = TRAILING_GROUP.matcher(full);
                if (gm.find()) {
                    group = Integer.parseInt(gm.group(1));
                    full = full.substring(0, gm.start());
                }
                String[] patterns = full.contains("||") ? full.split("\\|\\|") : new String[]{full};
                for (String p : patterns) {
                    String pt = p.trim();
                    if (pt.isEmpty()) continue;
                    Matcher m = Pattern.compile(pt).matcher(sUn);
                    if (!m.find()) {
                        m = Pattern.compile(pt).matcher(s);
                        if (!m.find()) continue;
                    }
                    return group <= m.groupCount() ? m.group(group) : m.group(0);
                }
                return null;
            }

            case "split": {
                if (parts.length < 3 || result == null) return result;
                String s = String.valueOf(result);
                int idx = Integer.parseInt(parts[2]);
                String[] arr = s.split(Pattern.quote(parts[1]), -1);
                return (idx >= 0 && idx < arr.length) ? arr[idx] : null;
            }

            case "replace": {
                if (parts.length < 3 || result == null) return result;
                String s = String.valueOf(result);
                return s.replace(parts[1], parts[2]);
            }

            case "substr":
            case "substring": {
                if (parts.length < 2 || result == null) return result;
                String s = String.valueOf(result);
                if (parts.length == 2) {
                    int len = Integer.parseInt(parts[1]);
                    return s.substring(0, Math.min(len, s.length()));
                }
                int start = Integer.parseInt(parts[1]);
                int end = parts[2].isEmpty() ? s.length() : Integer.parseInt(parts[2]);
                if (start < 0) start = 0;
                if (end > s.length()) end = s.length();
                return start <= end ? s.substring(start, end) : "";
            }

            case "json_path": {
                if (parts.length < 2 || result == null) return result;
                String path = parts[1];
                Object data;
                if (result instanceof String) {
                    data = JsonUtil.safeReadTree((String) result);
                } else {
                    data = result;
                }
                return data == null ? null : extractValue(data, path);
            }

            case "fallback": {
                if (parts.length < 2) return result;
                if (!isNullOrEmpty(result)) return result;
                String field = parts[1];
                if (fullData == null) return null;
                Object rb = fullData.get("response_body");
                if (rb instanceof String) {
                    JsonNode n = JsonUtil.safeReadTree((String) rb);
                    if (n != null) {
                        Object v = extractValue(n, field);
                        if (!isNullOrEmpty(v)) return v;
                    }
                }
                return extractValue(fullData, field);
            }

            case "default_if_empty": {
                if (parts.length < 2) return result;
                if (!isNullOrEmpty(result)) return result;
                // 支持默认值中包含冒号
                return String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            }

            case "default_if_timeout": {
                if (!isNullOrEmpty(result)) return result;
                String defaultVal = parts.length > 1
                        ? String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length))
                        : "system_error";
                if (fullData == null) return defaultVal;
                String dump = JsonUtil.toJson(fullData);
                return dump != null && dump.toLowerCase().contains("timeout") ? "timeout" : defaultVal;
            }

            case "default_code": {
                if (!isNullOrEmpty(result)) return result;
                try {
                    return Integer.parseInt(parts[1]);
                } catch (Exception e) {
                    return 500;
                }
            }

            case "datetime_parse": {
                if (!(result instanceof String)) return result;
                String s = (String) result;
                if (s.contains(".")) {
                    s = s.substring(0, s.indexOf('.'));
                }
                Matcher m = DT_SPACE_MILLIS.matcher(s);
                if (m.find()) s = m.group(1);
                return s;
            }

            default:
                return result;
        }
    }

    private static boolean isNullOrEmpty(Object v) {
        return v == null || "".equals(v);
    }

    /* ==================== convert_type ==================== */

    public static Object convertType(Object value, String fieldType) {
        if (value == null) return null;
        if (fieldType == null) fieldType = "TEXT";
        String up = fieldType.toUpperCase();
        try {
            if (up.equals("INTEGER") || up.equals("INT") || up.equals("BIGINT")
                    || up.equals("SMALLINT") || up.equals("TINYINT")) {
                String s = String.valueOf(value);
                if (s.isEmpty()) return null;
                return (long) Double.parseDouble(s);
            }
            if (up.equals("REAL") || up.equals("FLOAT") || up.equals("DOUBLE") || up.equals("DECIMAL")) {
                String s = String.valueOf(value);
                return s.isEmpty() ? null : Double.parseDouble(s);
            }
            if ("TIMESTAMP".equals(up)) {
                if (value instanceof Number) {
                    long t = ((Number) value).longValue();
                    if (t > 1_000_000_000_000L) t = t / 1000L;
                    return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(t), ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
                return String.valueOf(value);
            }
            if ("JSON".equals(up)) {
                if (value instanceof Map || value instanceof List) {
                    return JsonUtil.toJson(value);
                }
                return String.valueOf(value);
            }
            return String.valueOf(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /* ==================== transform_log_to_row ==================== */

    /**
     * 对齐 TransformUtils.transform_log_to_row
     *
     * @param log          原始日志 Map
     * @param fieldMap     field_mappings 表数据，target_column -> mapping 行
     *                     期望字段：source_field, transform_rule, default_value, empty_handler, is_required
     * @param fieldConfig  topic_table_mappings.field_config 的 List，每项含 name/type/source/transform/default
     * @return 转换后的行数据（只包含需写入 DB 的字段）
     */
    public static Map<String, Object> transformLogToRow(
            Map<String, Object> log,
            Map<String, Map<String, Object>> fieldMap,
            List<Map<String, Object>> fieldConfig) {

        Map<String, Object> rowData = new java.util.LinkedHashMap<>();

        for (Map<String, Object> field : fieldConfig) {
            String fieldName = str(field.get("name"));
            String srcInConfig = str(field.get("source"));
            if (srcInConfig == null) srcInConfig = fieldName;

            Map<String, Object> fm = fieldMap.getOrDefault(fieldName, java.util.Collections.emptyMap());

            String actualSource = firstNonEmpty(str(fm.get("source_field")), srcInConfig);
            Object value = actualSource == null ? null : extractValue(log, actualSource);

            // 1. 严格优先使用 fm.transform_rule
            String tr = str(fm.get("transform_rule"));
            if (tr != null && !tr.isEmpty()) {
                value = applyTransform(value, tr, log);
            } else {
                String ftr = str(field.get("transform"));
                if (ftr != null && !ftr.isEmpty()) {
                    value = applyTransform(value, ftr, log);
                }
            }

            // 2. 类型转换
            String fieldType = str(field.get("type"));
            if (fieldType == null) fieldType = "TEXT";
            value = convertType(value, fieldType);

            // 3. empty_handler
            String emptyHandler = firstNonEmpty(str(fm.get("empty_handler")), str(field.get("empty_handler")));
            if (isNullOrEmpty(value) && emptyHandler != null) {
                switch (emptyHandler) {
                    case "default_if_timeout": {
                        String dump = JsonUtil.toJson(log);
                        value = dump != null && dump.toLowerCase().contains("timeout") ? "timeout" : "system_error";
                        break;
                    }
                    case "default_code":
                        value = 500;
                        break;
                    case "use_default":
                        value = firstNonEmpty(str(fm.get("default_value")), str(field.get("default")));
                        break;
                    default:
                }
            }

            // 4. default 值（仅在没有 empty_handler 时）
            if (isNullOrEmpty(value) && emptyHandler == null) {
                String fmDefault = str(fm.get("default_value"));
                if (fmDefault != null && !fmDefault.isEmpty()) {
                    value = convertType(fmDefault, fieldType);
                } else {
                    Object fieldDefault = field.get("default");
                    if (fieldDefault != null && !"".equals(fieldDefault)) {
                        String s = String.valueOf(fieldDefault);
                        if (!"CURRENT_TIMESTAMP".equals(s) && !"NOW()".equals(s) && !"NULL".equals(s)) {
                            value = convertType(fieldDefault, fieldType);
                        }
                    }
                }
            }

            // 5. 跳过自增主键 id（source/source_field 都为空）
            if ("id".equals(fieldName)
                    && isNullOrEmpty(field.get("source"))
                    && isNullOrEmpty(fm.get("source_field"))) {
                continue;
            }

            // 6. 跳过 source 为空且使用数据库默认值的字段
            boolean hasQueryConfig = !isNullOrEmpty(fm.get("source_field"))
                    || !isNullOrEmpty(fm.get("transform_rule"))
                    || !isNullOrEmpty(fm.get("default_value"));
            if (!hasQueryConfig
                    && isNullOrEmpty(field.get("source"))
                    && isDbDefault(field.get("default"))) {
                continue;
            }

            // 7. 跳过 default_value 是数据库函数且 value 为空
            Object fmDv = fm.get("default_value");
            if (("CURRENT_TIMESTAMP".equals(fmDv) || "NOW()".equals(fmDv)) && value == null) {
                continue;
            }
            if (value != null && ("CURRENT_TIMESTAMP".equals(value) || "NOW()".equals(value))) {
                continue;
            }

            // 8. 必填检查
            Object req = fm.get("is_required");
            boolean required = req instanceof Number && ((Number) req).intValue() == 1;
            if (required && value == null) {
                throw new IllegalArgumentException("必填字段 '" + fieldName + "' 值为空");
            }

            rowData.put(fieldName, value);
        }
        return rowData;
    }

    private static boolean isDbDefault(Object v) {
        if (v == null || "".equals(v)) return true;
        String s = String.valueOf(v);
        return "CURRENT_TIMESTAMP".equals(s) || "NOW()".equals(s);
    }

    private static String str(Object v) {
        if (v == null) return null;
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    private static String firstNonEmpty(String... ss) {
        for (String s : ss) {
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }
}
