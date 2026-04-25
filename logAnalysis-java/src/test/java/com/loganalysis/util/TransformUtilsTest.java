package com.loganalysis.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * TransformUtils 单元测试，对照 Python services/transform_utils.py 的行为。
 */
class TransformUtilsTest {

    /* ========== 单规则 ========== */

    @Test
    void trimLowerUpper() {
        assertThat(TransformUtils.applyTransform("  HeLLo  ", "trim|lower", null))
                .isEqualTo("hello");
        assertThat(TransformUtils.applyTransform("aBc", "upper", null))
                .isEqualTo("ABC");
    }

    @Test
    void regexBasic() {
        // group 0 = 整匹配
        assertThat(TransformUtils.applyTransform("POST /api/user/add", "regex:/[^ ]+:0", null))
                .isEqualTo("/api/user/add");
    }

    @Test
    void regexWithGroup() {
        assertThat(TransformUtils.applyTransform("code=123", "regex:code=(\\d+):1", null))
                .isEqualTo("123");
    }

    @Test
    void regexNoMatchReturnsNull() {
        assertThat(TransformUtils.applyTransform("abc", "regex:\\d+:0", null)).isNull();
    }

    @Test
    void regexFallbackMultiPatterns() {
        // 第一个模式 \d+ 匹配不到，回退到 [a-z]+ 匹配 "xyz"
        Object r = TransformUtils.applyTransform("xyz-abc", "regex_fallback:(\\d+)||([a-z]+):1", null);
        assertThat(r).isEqualTo("xyz");
    }

    @Test
    void splitByDelimiter() {
        assertThat(TransformUtils.applyTransform("a,b,c,d", "split:,:2", null))
                .isEqualTo("c");
    }

    @Test
    void substrLengthAndRange() {
        assertThat(TransformUtils.applyTransform("hello world", "substr:5", null))
                .isEqualTo("hello");
        assertThat(TransformUtils.applyTransform("hello world", "substr:6:11", null))
                .isEqualTo("world");
    }

    @Test
    void replaceString() {
        assertThat(TransformUtils.applyTransform("foo bar", "replace:foo:baz", null))
                .isEqualTo("baz bar");
    }

    @Test
    void jsonPathOnString() {
        Object r = TransformUtils.applyTransform("{\"a\":{\"b\":42}}", "json_path:a.b", null);
        assertThat(r).isInstanceOf(Number.class);
        assertThat(((Number) r).intValue()).isEqualTo(42);
    }

    @Test
    void fallbackReadsResponseBody() {
        Map<String, Object> full = new HashMap<>();
        full.put("response_body", "{\"errCode\":500,\"errMsg\":\"x\"}");
        Object r = TransformUtils.applyTransform(null, "fallback:errCode", full);
        assertThat(r).isInstanceOf(Number.class);
        assertThat(((Number) r).intValue()).isEqualTo(500);
    }

    @Test
    void defaultIfEmpty() {
        assertThat(TransformUtils.applyTransform(null, "default_if_empty:hello", null))
                .isEqualTo("hello");
        // 非空不改
        assertThat(TransformUtils.applyTransform("x", "default_if_empty:hello", null))
                .isEqualTo("x");
        // 默认值含冒号
        assertThat(TransformUtils.applyTransform(null, "default_if_empty:k1:v1", null))
                .isEqualTo("k1:v1");
    }

    @Test
    void defaultCode() {
        Object r = TransformUtils.applyTransform("", "default_code:404", null);
        assertThat(r).isInstanceOf(Number.class);
        assertThat(((Number) r).intValue()).isEqualTo(404);
    }

    @Test
    void defaultIfTimeoutDetectsTimeout() {
        Map<String, Object> log = new HashMap<>();
        log.put("msg", "Something TIMEOUT happened");
        Object r = TransformUtils.applyTransform("", "default_if_timeout:system_error", log);
        assertThat(r).isEqualTo("timeout");

        Map<String, Object> log2 = new HashMap<>();
        log2.put("msg", "all good");
        Object r2 = TransformUtils.applyTransform("", "default_if_timeout:system_error", log2);
        assertThat(r2).isEqualTo("system_error");
    }

    @Test
    void datetimeParse() {
        assertThat(TransformUtils.applyTransform("2025-12-30 15:01:53.378", "datetime_parse", null))
                .isEqualTo("2025-12-30 15:01:53");
        assertThat(TransformUtils.applyTransform("2025-12-30 15:01:53 378", "datetime_parse", null))
                .isEqualTo("2025-12-30 15:01:53");
    }

    /* ========== 多规则串联 ========== */

    @Test
    void chainedRegexThenTrim() {
        Object r = TransformUtils.applyTransform(
                "POST /hitchride/order/addition",
                "regex:/[^ ]+:0|trim",
                null);
        assertThat(r).isEqualTo("/hitchride/order/addition");
    }

    /* ========== convertType ========== */

    @Test
    void convertTypeInteger() {
        Object r = TransformUtils.convertType("12.5", "INTEGER");
        assertThat(r).isInstanceOf(Number.class);
        assertThat(((Number) r).longValue()).isEqualTo(12L);

        // 空串 → null
        assertThat(TransformUtils.convertType("", "INTEGER")).isNull();
        // null → null
        assertThat(TransformUtils.convertType(null, "INTEGER")).isNull();
    }

    @Test
    void convertTypeReal() {
        Object r = TransformUtils.convertType("3.14", "REAL");
        assertThat(r).isInstanceOf(Double.class);
        assertThat((Double) r).isEqualTo(3.14);
    }

    @Test
    void convertTypeJson() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("k", "v");
        Object r = TransformUtils.convertType(v, "JSON");
        assertThat(r).isInstanceOf(String.class);
        assertThat((String) r).contains("\"k\":\"v\"");
    }

    /* ========== extractValue ========== */

    @Test
    void extractValueNested() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("b", "c");
        Map<String, Object> data = new HashMap<>();
        data.put("a", inner);
        assertThat(TransformUtils.extractValue(data, "a.b")).isEqualTo("c");
    }

    @Test
    void extractValueThroughJsonString() {
        Map<String, Object> data = new HashMap<>();
        data.put("body", "{\"x\":1}");
        Object r = TransformUtils.extractValue(data, "body.x");
        assertThat(r).isInstanceOf(Number.class);
        assertThat(((Number) r).intValue()).isEqualTo(1);
    }

    @Test
    void extractValueEmptyPathReturnsNull() {
        assertThat(TransformUtils.extractValue(new HashMap<>(), "")).isNull();
        assertThat(TransformUtils.extractValue(null, "a.b")).isNull();
    }

    /* ========== transformLogToRow（小场景） ========== */

    @Test
    void transformLogToRowBasicFlow() {
        // field_config: 两个字段
        //   method_name: source=path, transform=regex:/[^ ]+:0
        //   code: type=INTEGER, fm 中 default_value=500
        Map<String, Object> f1 = new LinkedHashMap<>();
        f1.put("name", "method_name"); f1.put("type", "TEXT"); f1.put("source", "path");
        f1.put("transform", "regex:/[^ ]+:0");

        Map<String, Object> f2 = new LinkedHashMap<>();
        f2.put("name", "code"); f2.put("type", "INTEGER"); f2.put("source", "errcode");

        // field_map 仅对 code 配置 default_value
        Map<String, Map<String, Object>> fieldMap = new HashMap<>();
        Map<String, Object> fmCode = new HashMap<>();
        fmCode.put("default_value", "500");
        fieldMap.put("code", fmCode);

        Map<String, Object> log = new HashMap<>();
        log.put("path", "POST /api/x");
        // 没有 errcode 字段 → code 应该走 default_value → 500
        Map<String, Object> row = TransformUtils.transformLogToRow(
                log, fieldMap, Arrays.asList(f1, f2));

        assertThat(row).containsEntry("method_name", "/api/x");
        assertThat(row.get("code")).isInstanceOf(Number.class);
        assertThat(((Number) row.get("code")).longValue()).isEqualTo(500L);
    }
}
