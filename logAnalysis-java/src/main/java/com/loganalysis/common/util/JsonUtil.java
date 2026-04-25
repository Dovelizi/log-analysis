package com.loganalysis.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON 工具，统一使用 Jackson（Spring Boot 自带），不引入额外依赖。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 安全解析 JSON 字符串，支持一层转义（如 {\"k\":\"v\"}）。
     * 对齐原 TransformUtils.safe_json_loads。
     */
    public static JsonNode safeReadTree(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        // 1. 直接解析
        try {
            return MAPPER.readTree(s);
        } catch (Exception ignore) { /* fall-through */ }
        // 2. 去除转义
        try {
            String un = s.replace("\\\"", "\"").replace("\\\\", "\\");
            return MAPPER.readTree(un);
        } catch (Exception ignore) { /* fall-through */ }
        // 3. 去外层引号再解
        try {
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                String inner = s.substring(1, s.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
                return MAPPER.readTree(inner);
            }
        } catch (Exception ignore) { /* fall-through */ }
        return null;
    }

    public static String toJson(Object o) {
        if (o == null) return null;
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
