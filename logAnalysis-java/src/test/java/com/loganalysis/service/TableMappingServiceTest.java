package com.loganalysis.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * TableMappingService 的纯静态白名单校验测试。
 * 安全关键：动态 DDL 必须有白名单防护，否则 SQL 注入。
 */
class TableMappingServiceTest {

    /* ========== 表名 ========== */

    @Test
    void validateTableName_valid() {
        // 不抛异常 = 通过
        TableMappingService.validateTableName("user_log_2026");
        TableMappingService.validateTableName("a");
    }

    @Test
    void validateTableName_rejectsInjection() {
        String[] bad = {
                "users; DROP TABLE x",
                "users--",
                "users/*",
                "`users`",
                "'users'",
                "1users",        // 不能数字开头
                "",
                null,
                "u-ser",         // 不能含 -
                "u ser",         // 不能含空格
                "u.ser"          // 不能含 .
        };
        for (String b : bad) {
            assertThatThrownBy(() -> TableMappingService.validateTableName(b))
                    .as("should reject: %s", b)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validateTableName_rejectsReserved() {
        assertThatThrownBy(() -> TableMappingService.validateTableName("select"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableMappingService.validateTableName("DROP"))  // 大小写不敏感
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ========== 列名 ========== */

    @Test
    void validateColumnName() {
        TableMappingService.validateColumnName("error_code");
        assertThatThrownBy(() -> TableMappingService.validateColumnName("DROP TABLE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ========== 列类型 ========== */

    @Test
    void normalizeColumnType_mapping() {
        assertThat(TableMappingService.normalizeColumnType("TEXT")).isEqualTo("TEXT");
        assertThat(TableMappingService.normalizeColumnType("INTEGER")).isEqualTo("INT");
        assertThat(TableMappingService.normalizeColumnType("REAL")).isEqualTo("DOUBLE");
        assertThat(TableMappingService.normalizeColumnType("JSON")).isEqualTo("JSON");
    }

    @Test
    void normalizeColumnType_customAllowed() {
        // 自定义类型以白名单前缀开头，直接透传
        assertThat(TableMappingService.normalizeColumnType("VARCHAR(100)")).isEqualTo("VARCHAR(100)");
        assertThat(TableMappingService.normalizeColumnType("DECIMAL(10,2)")).isEqualTo("DECIMAL(10,2)");
    }

    @Test
    void normalizeColumnType_rejectsEvil() {
        assertThatThrownBy(() -> TableMappingService.normalizeColumnType("TEXT; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableMappingService.normalizeColumnType("SOMEWEIRD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeColumnType_nullOrEmptyFallsBack() {
        assertThat(TableMappingService.normalizeColumnType(null)).isEqualTo("TEXT");
        assertThat(TableMappingService.normalizeColumnType("")).isEqualTo("TEXT");
    }
}
