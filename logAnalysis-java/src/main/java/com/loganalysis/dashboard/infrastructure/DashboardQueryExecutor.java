package com.loganalysis.dashboard.infrastructure;

import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

/**
 * Dashboard 查询执行器。
 *
 * 抽象目的：屏蔽 MySQL / ClickHouse 差异，让 DashboardService 业务代码保持对数据源无感知。
 *
 * 实现策略（详见 REFACTOR_PLAN §4 + 用户 P3 决策）：
 *   - {@link DashboardMysqlQueryExecutor}（默认）：直接委托 MySQL JdbcTemplate
 *   - {@link DashboardClickHouseQueryExecutor}（enabled=true &amp; read-source=clickhouse 时激活）：
 *     委托 ClickHouse JdbcTemplate，失败时静默降级到 MySQL（用户选择 A 的行为）
 *
 * 仅暴露 DashboardService 实际使用到的 JdbcTemplate 方法子集，避免接口膨胀。
 */
public interface DashboardQueryExecutor {

    /** 对应 JdbcTemplate.queryForList(sql, args) */
    List<Map<String, Object>> queryForList(String sql, Object... args);

    /** 对应 JdbcTemplate.queryForObject(sql, requiredType, args) */
    <T> T queryForObject(String sql, Class<T> requiredType, Object... args);

    /** 对应 JdbcTemplate.queryForMap(sql, args) */
    Map<String, Object> queryForMap(String sql, Object... args);

    /** 对应 JdbcTemplate.query(sql, rowMapper, args) */
    <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args);

    /**
     * 检查表是否存在。
     *
     * 注意：MySQL 用 "SHOW TABLES LIKE ?"，ClickHouse 用 "EXISTS TABLE ?"，
     * 语法不同，故独立为接口方法而非直接透传 SQL。
     */
    boolean tableExists(String tableName);
}
