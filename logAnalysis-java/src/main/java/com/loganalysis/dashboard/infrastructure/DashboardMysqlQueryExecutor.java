package com.loganalysis.dashboard.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MySQL 实现的 Dashboard 查询执行器（默认 bean）。
 *
 * 仅做 JdbcTemplate 的透传，保留原 DashboardService JdbcTemplate 调用语义。
 *
 * 用 {@link Primary} 保证 CH 配置未启用时这是唯一候选；
 * CH 启用时由 {@link DashboardClickHouseQueryExecutor} 接管。
 */
@Component
@Primary
public class DashboardMysqlQueryExecutor implements DashboardQueryExecutor {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return jdbcTemplate.queryForObject(sql, requiredType, args);
    }

    @Override
    public Map<String, Object> queryForMap(String sql, Object... args) {
        return jdbcTemplate.queryForMap(sql, args);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        return jdbcTemplate.query(sql, rowMapper, args);
    }

    @Override
    public boolean tableExists(String tableName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SHOW TABLES LIKE ?", tableName);
            return !rows.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
