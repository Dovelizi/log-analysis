package com.loganalysis.common.util;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据入库过滤条件评估器
 * 对齐 Python services/transform_utils.py 的 FilterEvaluator。
 *
 * filter_config 格式：
 * { "enabled": true, "logic": "and"|"or", "conditions": [ {field, operator, value}, ... ] }
 *
 * 支持的 operator：
 *   equals / not_equals / contains / not_contains / starts_with / ends_with
 *   gt / gte / lt / lte / in / not_in / regex / is_empty / is_not_empty
 */
public final class FilterEvaluator {

    private FilterEvaluator() {}

    @SuppressWarnings("unchecked")
    public static boolean evaluate(Map<String, Object> logData, Map<String, Object> filterConfig) {
        if (filterConfig == null) return true;
        Object enabled = filterConfig.get("enabled");
        if (!(enabled instanceof Boolean && (Boolean) enabled)) return true;
        Object condsObj = filterConfig.get("conditions");
        if (!(condsObj instanceof List)) return true;
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) condsObj;
        if (conditions.isEmpty()) return true;
        String logic = String.valueOf(filterConfig.getOrDefault("logic", "and")).toLowerCase();

        boolean isAnd = !"or".equals(logic);
        for (Map<String, Object> c : conditions) {
            boolean ok = evaluateCondition(logData, c);
            if (isAnd && !ok) return false;
            if (!isAnd && ok) return true;
        }
        return isAnd;
    }

    private static boolean evaluateCondition(Map<String, Object> log, Map<String, Object> cond) {
        String field = String.valueOf(cond.getOrDefault("field", ""));
        String op = String.valueOf(cond.getOrDefault("operator", "equals"));
        Object compare = cond.getOrDefault("value", "");

        Object actual = TransformUtils.extractValue(log, field);
        String actualStr;
        if (actual == null) actualStr = "";
        else if (actual instanceof Map || actual instanceof List) actualStr = JsonUtil.toJson(actual);
        else actualStr = String.valueOf(actual);

        String compareStr = compare == null ? "" : String.valueOf(compare);
        try {
            switch (op) {
                case "equals": return actualStr.equals(compareStr);
                case "not_equals": return !actualStr.equals(compareStr);
                case "contains": return actualStr.contains(compareStr);
                case "not_contains": return !actualStr.contains(compareStr);
                case "starts_with": return actualStr.startsWith(compareStr);
                case "ends_with": return actualStr.endsWith(compareStr);
                case "gt": return compareNumeric(actual, compare, 1);
                case "gte": return compareNumeric(actual, compare, 2);
                case "lt": return compareNumeric(actual, compare, -1);
                case "lte": return compareNumeric(actual, compare, -2);
                case "in": {
                    for (String v : compareStr.split(",", -1)) {
                        if (actualStr.equals(v.trim())) return true;
                    }
                    return false;
                }
                case "not_in": {
                    for (String v : compareStr.split(",", -1)) {
                        if (actualStr.equals(v.trim())) return false;
                    }
                    return true;
                }
                case "regex":
                    try {
                        return Pattern.compile(compareStr).matcher(actualStr).find();
                    } catch (Exception e) {
                        return false;
                    }
                case "is_empty":
                    return actual == null || actualStr.isEmpty() || actualStr.trim().isEmpty();
                case "is_not_empty":
                    return actual != null && !actualStr.isEmpty() && !actualStr.trim().isEmpty();
                default:
                    return true;
            }
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * code: 1=gt, 2=gte, -1=lt, -2=lte
     */
    private static boolean compareNumeric(Object actual, Object compare, int code) {
        try {
            double a = actual == null ? 0d : Double.parseDouble(String.valueOf(actual));
            double b = Double.parseDouble(String.valueOf(compare));
            switch (code) {
                case 1: return a > b;
                case 2: return a >= b;
                case -1: return a < b;
                case -2: return a <= b;
                default: return false;
            }
        } catch (Exception e) {
            // 退化到字符串比较
            try {
                int cmp = String.valueOf(actual).compareTo(String.valueOf(compare));
                switch (code) {
                    case 1: return cmp > 0;
                    case 2: return cmp >= 0;
                    case -1: return cmp < 0;
                    case -2: return cmp <= 0;
                    default: return false;
                }
            } catch (Exception ignore) {
                return false;
            }
        }
    }
}
