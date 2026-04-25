package com.loganalysis.dashboard.infrastructure;

import com.loganalysis.common.config.ClickHouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ClickHouse 实现的 Dashboard 查询执行器。
 *
 * 激活条件（两个同时满足）：
 *   1. {@code loganalysis.clickhouse.enabled=true}（由 ClickHouseConfig 主开关控制）
 *   2. {@code loganalysis.clickhouse.read-source=clickhouse}（本类 ConditionalOnProperty）
 *
 * 行为（对齐用户选 A：自动降级到 MySQL）：
 *   - 所有查询先打 CH；若抛异常则 WARN 日志 + 降级到 MySQL JdbcTemplate 原路径
 *   - 未抛异常但结果为空视为正常（和数据库真实情况对齐，不触发降级）
 *   - {@link #tableExists(String)} 同样先 CH 后 MySQL
 *
 * 注意事项：
 *   - ClickHouse SQL 方言与 MySQL 有差异（{@code DATE(col)} 改 {@code toDate(col)} 等）。
 *     本类不做 SQL 翻译，DashboardService 的查询 SQL 需用跨方言兼容写法
 *     （如用 {@code CAST(... AS DATE)} / 避免 {@code SHOW TABLES}）。
 *   - 对强烈依赖 MySQL 方言的查询，DashboardService 应显式调用 MySQL 兜底路径
 *     （当前策略：任何 CH 失败自动静默降级到 MySQL，前端不感知）。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "loganalysis.clickhouse", name = "read-source", havingValue = "clickhouse")
public class DashboardClickHouseQueryExecutor implements DashboardQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DashboardClickHouseQueryExecutor.class);

    /** 同一 pkg 内的 MySQL 实现，降级时复用 */
    @Autowired
    private DashboardMysqlQueryExecutor mysqlFallback;

    @Autowired
    @Qualifier("clickHouseJdbcTemplate")
    private JdbcTemplate clickHouseJdbcTemplate;

    @Autowired
    private ClickHouseProperties props;

    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... args) {
        try {
            return clickHouseJdbcTemplate.queryForList(sql, args);
        } catch (Exception e) {
            warnAndFallback("queryForList", sql, e);
            return mysqlFallback.queryForList(sql, args);
        }
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        try {
            return clickHouseJdbcTemplate.queryForObject(sql, requiredType, args);
        } catch (Exception e) {
            warnAndFallback("queryForObject", sql, e);
            return mysqlFallback.queryForObject(sql, requiredType, args);
        }
    }

    @Override
    public Map<String, Object> queryForMap(String sql, Object... args) {
        try {
            return clickHouseJdbcTemplate.queryForMap(sql, args);
        } catch (Exception e) {
            warnAndFallback("queryForMap", sql, e);
            return mysqlFallback.queryForMap(sql, args);
        }
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        try {
            return clickHouseJdbcTemplate.query(sql, rowMapper, args);
        } catch (Exception e) {
            warnAndFallback("query", sql, e);
            return mysqlFallback.query(sql, rowMapper, args);
        }
    }

    @Override
    public boolean tableExists(String tableName) {
        try {
            // ClickHouse 语法：EXISTS TABLE db.table 返回 1/0
            Integer exists = clickHouseJdbcTemplate.queryForObject(
                    "EXISTS TABLE " + tableName, Integer.class);
            return exists != null && exists == 1;
        } catch (Exception e) {
            log.warn("ClickHouse tableExists 失败，降级 MySQL: table={}, err={}", tableName, e.getMessage());
            return mysqlFallback.tableExists(tableName);
        }
    }

    private void warnAndFallback(String op, String sql, Exception e) {
        // 按方案：降级必打 WARN，便于运维盯盘；SQL 截断 200 字符避免日志爆炸
        String sqlPreview = sql == null ? "" : (sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);
        log.warn("ClickHouse {} 失败，静默降级到 MySQL: sql={}, err={}", op, sqlPreview, e.getMessage());
    }
}
