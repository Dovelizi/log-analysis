package com.loganalysis.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class FilterEvaluatorTest {

    private Map<String, Object> log;

    @BeforeEach
    void setUp() {
        log = new HashMap<>();
        log.put("error_code", 500);
        log.put("method", "/api/user");
        log.put("msg", "timeout occurred");
        log.put("tag", "");
    }

    private Map<String, Object> cfg(String logic, List<Map<String, Object>> conds) {
        Map<String, Object> c = new HashMap<>();
        c.put("enabled", true);
        c.put("logic", logic);
        c.put("conditions", conds);
        return c;
    }

    private Map<String, Object> cond(String field, String op, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("field", field);
        m.put("operator", op);
        m.put("value", value);
        return m;
    }

    @Test
    void disabledReturnsTrue() {
        Map<String, Object> c = new HashMap<>();
        c.put("enabled", false);
        c.put("conditions", Collections.singletonList(cond("method", "equals", "NOT_MATCH")));
        assertThat(FilterEvaluator.evaluate(log, c)).isTrue();
    }

    @Test
    void nullConfigReturnsTrue() {
        assertThat(FilterEvaluator.evaluate(log, null)).isTrue();
    }

    @Test
    void equalsAndNotEquals() {
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("method", "equals", "/api/user"))))).isTrue();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("method", "not_equals", "/api/user"))))).isFalse();
    }

    @Test
    void containsAndNotContains() {
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("msg", "contains", "timeout"))))).isTrue();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("msg", "not_contains", "timeout"))))).isFalse();
    }

    @Test
    void startsAndEndsWith() {
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("method", "starts_with", "/api"))))).isTrue();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("method", "ends_with", "user"))))).isTrue();
    }

    @Test
    void numericCompare() {
        // error_code = 500
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("error_code", "gt", 400))))).isTrue();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("error_code", "gte", 500))))).isTrue();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("error_code", "lt", 500))))).isFalse();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("error_code", "lte", 500))))).isTrue();
    }

    @Test
    void inAndNotIn() {
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("error_code", "in", "400,500,502"))))).isTrue();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("error_code", "not_in", "400,502"))))).isTrue();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("error_code", "not_in", "500,502"))))).isFalse();
    }

    @Test
    void regex() {
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("method", "regex", "^/api/\\w+$"))))).isTrue();
    }

    @Test
    void isEmptyAndIsNotEmpty() {
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("tag", "is_empty", ""))))).isTrue();
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("method", "is_not_empty", ""))))).isTrue();
        // 不存在的字段视为 empty
        assertThat(FilterEvaluator.evaluate(log,
                cfg("and", List.of(cond("nonexistent", "is_empty", ""))))).isTrue();
    }

    @Test
    void andLogicAllMustPass() {
        Map<String, Object> config = cfg("and", List.of(
                cond("method", "equals", "/api/user"),
                cond("error_code", "gt", 400)));
        assertThat(FilterEvaluator.evaluate(log, config)).isTrue();

        Map<String, Object> fail = cfg("and", List.of(
                cond("method", "equals", "/api/user"),
                cond("error_code", "lt", 100)));  // 500 < 100 假
        assertThat(FilterEvaluator.evaluate(log, fail)).isFalse();
    }

    @Test
    void orLogicAnyPass() {
        Map<String, Object> config = cfg("or", List.of(
                cond("method", "equals", "NOT_MATCH"),
                cond("error_code", "gt", 400)));
        assertThat(FilterEvaluator.evaluate(log, config)).isTrue();

        Map<String, Object> fail = cfg("or", List.of(
                cond("method", "equals", "NOT_MATCH"),
                cond("error_code", "lt", 100)));
        assertThat(FilterEvaluator.evaluate(log, fail)).isFalse();
    }
}
