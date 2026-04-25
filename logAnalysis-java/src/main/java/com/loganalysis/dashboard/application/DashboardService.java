package com.loganalysis.dashboard.application;

import org.springframework.beans.factory.annotation.Autowired;
import com.loganalysis.dashboard.infrastructure.DashboardQueryExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dashboard 统计服务，对齐 routes/dashboard_routes.py。
 *
 * 所有 5 张业务表 (control_hitch_error_mothod / gw_hitch_error_mothod /
 * hitch_control_cost_time / hitch_supplier_error_sp / hitch_supplier_error_total)
 * 共享相同的"按 create_time 日期范围过滤 + COUNT/SUM/GROUP BY"模板。
 *
 * 日期范围策略（parse_date_range）：
 *   未传 → 当天 00:00:00 ~ 23:59:59
 *   区间 > 7 天或开始晚于结束 → 抛 IllegalArgumentException
 */
@Service
public class DashboardService {

    public static final int MAX_DATE_RANGE_DAYS = 7;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 允许查询的业务表白名单 */
    public static final Set<String> ALLOWED_TABLES = Set.of(
            "control_hitch_error_mothod",
            "gw_hitch_error_mothod",
            "hitch_control_cost_time",
            "hitch_supplier_error_sp",
            "hitch_supplier_error_total"
    );

    /** statistics/aggregation 允许排序的列（白名单，对齐原 Python） */
    public static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "count", "total_count", "update_time", "create_time", "method_name", "error_code"
    );

    @Autowired
    private DashboardQueryExecutor jdbc;

    /**
     * 解析日期范围参数，返回 {startDate, endDate, startTime, endTime}。
     *
     * 支持两种输入格式：
     *   1. YYYY-MM-DD                 —— 默认补齐 00:00:00 / 23:59:59，受 7 天限制
     *   2. YYYY-MM-DD HH:mm:ss        —— 直接作为 startTime/endTime；
     *       startDate/endDate 字段取日期部分；受 7 天限制基于 DATE(start) 到 DATE(end)
     *
     * 引入时间格式支持是为了前端「时间范围快捷下拉」功能（二期）。
     */
    public DateRange parseDateRange(String startDate, String endDate) {
        if (isEmpty(startDate) || isEmpty(endDate)) {
            String today = LocalDate.now().format(D);
            return new DateRange(today, today, today + " 00:00:00", today + " 23:59:59");
        }
        // 优先尝试带时间格式
        if (startDate.length() > 10 || endDate.length() > 10) {
            return parseDateTimeRange(startDate, endDate);
        }
        // 纯日期格式（一期行为）
        LocalDate s, e;
        try {
            s = LocalDate.parse(startDate, D);
            e = LocalDate.parse(endDate, D);
        } catch (Exception ex) {
            throw new IllegalArgumentException("日期格式错误，请使用YYYY-MM-DD格式");
        }
        long delta = java.time.temporal.ChronoUnit.DAYS.between(s, e);
        if (delta < 0) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        if (delta >= MAX_DATE_RANGE_DAYS) {
            throw new IllegalArgumentException("日期范围不能超过" + MAX_DATE_RANGE_DAYS + "天");
        }
        return new DateRange(startDate, endDate, startDate + " 00:00:00", endDate + " 23:59:59");
    }

    /** 带时间格式：YYYY-MM-DD HH:mm:ss */
    private DateRange parseDateTimeRange(String startDt, String endDt) {
        java.time.format.DateTimeFormatter dtf =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        java.time.LocalDateTime s, e;
        try {
            s = java.time.LocalDateTime.parse(startDt, dtf);
            e = java.time.LocalDateTime.parse(endDt, dtf);
        } catch (Exception ex) {
            throw new IllegalArgumentException("日期时间格式错误，请使用 YYYY-MM-DD HH:mm:ss");
        }
        if (s.isAfter(e)) throw new IllegalArgumentException("开始时间不能晚于结束时间");
        long days = java.time.temporal.ChronoUnit.DAYS.between(s.toLocalDate(), e.toLocalDate());
        if (days >= MAX_DATE_RANGE_DAYS) {
            throw new IllegalArgumentException("日期范围不能超过" + MAX_DATE_RANGE_DAYS + "天");
        }
        return new DateRange(
                s.toLocalDate().format(D),
                e.toLocalDate().format(D),
                startDt,
                endDt);
    }

    /** 检查表是否存在（由 QueryExecutor 按数据源方言实现） */
    public boolean tableExists(String tableName) {
        if (!ALLOWED_TABLES.contains(tableName)) return false;
        return jdbc.tableExists(tableName);
    }

    /* ========== available-dates ========== */
    public Map<String, Object> availableDates() {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        String startTime = start.format(D) + " 00:00:00";
        String endTime = today.format(D) + " 23:59:59";

        Set<String> dates = new TreeSet<>(Comparator.reverseOrder());
        for (String t : ALLOWED_TABLES) {
            if (!tableExists(t)) continue;
            try {
                List<String> rows = jdbc.query(
                        "SELECT DISTINCT DATE(create_time) as date_val FROM " + t +
                        " WHERE create_time BETWEEN ? AND ?",
                        (rs, i) -> {
                            java.sql.Date d = rs.getDate(1);
                            return d == null ? null : d.toLocalDate().format(D);
                        },
                        startTime, endTime);
                for (String d : rows) if (d != null) dates.add(d);
            } catch (Exception ignored) { /* 缺表或列不兼容，跳过 */ }
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("dates", new ArrayList<>(dates));
        ret.put("today", today.format(D));
        return ret;
    }

    /* ========== overview ========== */
    public Map<String, Object> overview(DateRange dr) {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("start_date", dr.startDate);
        ret.put("end_date", dr.endDate);
        for (String t : ALLOWED_TABLES) {
            Map<String, Object> sub = new LinkedHashMap<>();
            if (!tableExists(t)) {
                sub.put("exists", false);
                sub.put("total", 0);
            } else {
                try {
                    Long cnt = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + t + " WHERE create_time BETWEEN ? AND ?",
                            Long.class, dr.startTime, dr.endTime);
                    sub.put("exists", true);
                    sub.put("total", cnt == null ? 0L : cnt);
                } catch (Exception e) {
                    sub.put("exists", false);
                    sub.put("total", 0);
                    sub.put("error", e.getMessage());
                }
            }
            ret.put(t, sub);
        }
        return ret;
    }

    /* ========== 通用 statistics（control_hitch / gw_hitch） ========== */
    public Map<String, Object> hitchStatistics(String table, int logFrom, DateRange dr,
                                               int page, int pageSize,
                                               String sortField, String sortOrder) {
        return hitchStatistics(table, logFrom, dr, page, pageSize, sortField, sortOrder, "1h");
    }

    public Map<String, Object> hitchStatistics(String table, int logFrom, DateRange dr,
                                               int page, int pageSize,
                                               String sortField, String sortOrder,
                                               String granularity) {
        if (!tableExists(table)) throw new TableMissingException(table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("start_date", dr.startDate);
        result.put("end_date", dr.endDate);

        Long totalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        result.put("total_count", totalCount == null ? 0L : totalCount);

        Long totalError = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_count), 0) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        result.put("total_error_count", totalError == null ? 0L : totalError);

        if ("control_hitch_error_mothod".equals(table)) {
            Long cnt = jdbc.queryForObject(
                    "SELECT COUNT(error_code) FROM " + table +
                    " WHERE error_code IS NOT NULL AND create_time BETWEEN ? AND ?",
                    Long.class, dr.startTime, dr.endTime);
            result.put("unique_error_code_count", cnt == null ? 0L : cnt);
            Long mCnt = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT method_name) FROM " + table +
                    " WHERE method_name IS NOT NULL AND method_name != '' AND create_time BETWEEN ? AND ?",
                    Long.class, dr.startTime, dr.endTime);
            result.put("unique_method_count", mCnt == null ? 0L : mCnt);
        } else {
            // gw_hitch: unique_method_count = SUM(total_count)
            Long cnt = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(total_count), 0) FROM " + table +
                    " WHERE total_count IS NOT NULL AND create_time BETWEEN ? AND ?",
                    Long.class, dr.startTime, dr.endTime);
            result.put("unique_method_count", cnt == null ? 0L : cnt);
        }

        result.put("error_code_distribution", jdbc.queryForList(
                "SELECT error_code, SUM(total_count) as count FROM " + table +
                " WHERE error_code IS NOT NULL AND create_time BETWEEN ? AND ? " +
                " GROUP BY error_code ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));

        result.put("method_distribution", jdbc.queryForList(
                "SELECT method_name, SUM(total_count) as count FROM " + table +
                " WHERE method_name IS NOT NULL AND method_name != '' AND create_time BETWEEN ? AND ? " +
                " GROUP BY method_name ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));

        // 时间趋势按 log_from 筛选，粒度由 granularity 决定（10m / 1h / 1d）
        result.put("trend_hourly", jdbc.queryForList(
                "SELECT " + buildTimeBucketSql(granularity) + " as time_bucket, SUM(count) as count " +
                "FROM hitch_error_log_insert_record WHERE log_from = ? AND create_time BETWEEN ? AND ? " +
                "GROUP BY time_bucket ORDER BY time_bucket",
                logFrom, dr.startTime, dr.endTime));

        int offset = Math.max(0, (page - 1) * pageSize);
        String orderClause = buildOrderClause(sortField, sortOrder,
                "ORDER BY update_time DESC, total_count DESC, count DESC");
        result.put("recent_errors", jdbc.queryForList(
                "SELECT id, method_name, error_code, error_message, count, total_count, update_time " +
                "FROM " + table + " WHERE create_time BETWEEN ? AND ? " + orderClause +
                " LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, pageSize, offset));
        result.put("page", page);
        result.put("page_size", pageSize);
        result.put("total_pages", totalPages(totalCount, pageSize));
        return result;
    }

    /* ========== 通用 aggregation（control_hitch / gw_hitch） ========== */
    public Map<String, Object> hitchAggregation(String table, int page, int pageSize) {
        if (!tableExists(table)) throw new TableMissingException(table);
        Long totalCount = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        int offset = Math.max(0, (page - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, method_name, error_code, error_message, count, total_count, update_time " +
                "FROM " + table +
                " ORDER BY update_time DESC, total_count DESC, count DESC " +
                " LIMIT ? OFFSET ?", pageSize, offset);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("exists", true);
        ret.put("aggregation", rows);
        ret.put("page", page);
        ret.put("page_size", pageSize);
        ret.put("total_count", totalCount == null ? 0L : totalCount);
        ret.put("total_pages", totalPages(totalCount, pageSize));
        return ret;
    }

    /* ========== Supplier SP / Total 聚合（按 sp_id 分组） ========== */
    public Map<String, Object> supplierAggregation(String table, DateRange dr, Integer spId,
                                                   int page, int pageSize,
                                                   String sortField, String sortOrder) {
        if (!tableExists(table)) throw new TableMissingException(table);

        String orderClause = buildOrderClause(sortField, sortOrder,
                "ORDER BY update_time DESC, total_count DESC, count DESC");
        // sp_list
        List<Map<String, Object>> spList;
        if ("hitch_supplier_error_sp".equals(table)) {
            spList = jdbc.queryForList(
                    "SELECT DISTINCT sp_id, sp_name FROM " + table +
                    " WHERE sp_id IS NOT NULL AND create_time BETWEEN ? AND ? ORDER BY sp_id",
                    dr.startTime, dr.endTime);
        } else {
            List<Map<String, Object>> tmp = jdbc.queryForList(
                    "SELECT DISTINCT sp_id FROM " + table +
                    " WHERE sp_id IS NOT NULL AND create_time BETWEEN ? AND ? ORDER BY sp_id",
                    dr.startTime, dr.endTime);
            spList = new ArrayList<>(tmp.size());
            for (Map<String, Object> r : tmp) spList.add(Collections.singletonMap("sp_id", r.get("sp_id")));
        }

        if (spId == null && !spList.isEmpty()) {
            Object v = spList.get(0).get("sp_id");
            if (v instanceof Number) spId = ((Number) v).intValue();
        }

        long totalCount = 0L;
        List<Map<String, Object>> aggregation = Collections.emptyList();
        if (spId != null) {
            Long tc = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table +
                    " WHERE sp_id = ? AND create_time BETWEEN ? AND ?",
                    Long.class, spId, dr.startTime, dr.endTime);
            totalCount = tc == null ? 0L : tc;
            int offset = Math.max(0, (page - 1) * pageSize);
            aggregation = jdbc.queryForList(
                    "SELECT id, method_name, error_code, error_message, count, total_count, update_time " +
                    "FROM " + table + " WHERE sp_id = ? AND create_time BETWEEN ? AND ? " +
                    orderClause + " LIMIT ? OFFSET ?",
                    spId, dr.startTime, dr.endTime, pageSize, offset);
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("exists", true);
        ret.put("start_date", dr.startDate);
        ret.put("end_date", dr.endDate);
        ret.put("sp_list", spList);
        ret.put("current_sp", spId);
        ret.put("aggregation", aggregation);
        ret.put("page", page);
        ret.put("page_size", pageSize);
        ret.put("total_count", totalCount);
        ret.put("total_pages", totalPages(totalCount, pageSize));
        return ret;
    }

    /* ========== Supplier SP statistics ========== */
    public Map<String, Object> supplierSpStatistics(DateRange dr, int page, int pageSize) {
        return supplierSpStatistics(dr, page, pageSize, "1h");
    }

    public Map<String, Object> supplierSpStatistics(DateRange dr, int page, int pageSize, String granularity) {
        String table = "hitch_supplier_error_sp";
        if (!tableExists(table)) throw new TableMissingException(table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("start_date", dr.startDate);
        result.put("end_date", dr.endDate);

        Long tc = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        result.put("total_count", tc == null ? 0L : tc);

        Long te = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_count), 0) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        result.put("total_error_count", te == null ? 0L : te);

        result.put("supplier_distribution", jdbc.queryForList(
                "SELECT sp_id, sp_name, SUM(total_count) as count FROM " + table +
                " WHERE sp_id IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "GROUP BY sp_id, sp_name ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));

        result.put("error_code_distribution", jdbc.queryForList(
                "SELECT error_code, SUM(total_count) as count FROM " + table +
                " WHERE error_code IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "GROUP BY error_code ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));

        result.put("trend_hourly", jdbc.queryForList(
                "SELECT sp_id, " + buildTimeBucketSql(granularity) + " as time_bucket, SUM(count) as count " +
                "FROM hitch_error_log_insert_record WHERE log_from = 3 AND create_time BETWEEN ? AND ? " +
                "GROUP BY sp_id, time_bucket ORDER BY time_bucket",
                dr.startTime, dr.endTime));

        int offset = Math.max(0, (page - 1) * pageSize);
        result.put("recent_records", jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE create_time BETWEEN ? AND ? " +
                "ORDER BY update_time DESC, total_count DESC, count DESC LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, pageSize, offset));
        result.put("page", page);
        result.put("page_size", pageSize);
        result.put("total_pages", totalPages(tc, pageSize));
        return result;
    }

    /* ========== Supplier Total statistics ========== */
    public Map<String, Object> supplierTotalStatistics(DateRange dr, int page, int pageSize) {
        return supplierTotalStatistics(dr, page, pageSize, "1h");
    }

    public Map<String, Object> supplierTotalStatistics(DateRange dr, int page, int pageSize, String granularity) {
        String table = "hitch_supplier_error_total";
        if (!tableExists(table)) throw new TableMissingException(table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("start_date", dr.startDate);
        result.put("end_date", dr.endDate);

        Long tc = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        result.put("total_count", tc == null ? 0L : tc);

        Long te = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_count), 0) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        result.put("total_error_count", te == null ? 0L : te);

        result.put("supplier_error_summary", jdbc.queryForList(
                "SELECT sp_id, SUM(total_count) as total_errors FROM " + table +
                " WHERE sp_id IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "GROUP BY sp_id ORDER BY total_errors DESC LIMIT 10",
                dr.startTime, dr.endTime));

        result.put("error_code_distribution", jdbc.queryForList(
                "SELECT error_code, SUM(total_count) as count FROM " + table +
                " WHERE error_code IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "GROUP BY error_code ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));

        result.put("hourly_trend", jdbc.queryForList(
                "SELECT sp_id, " + buildTimeBucketSql(granularity) + " as time_bucket, SUM(count) as count " +
                "FROM hitch_error_log_insert_record WHERE log_from = 4 AND create_time BETWEEN ? AND ? " +
                "GROUP BY sp_id, time_bucket ORDER BY time_bucket",
                dr.startTime, dr.endTime));

        result.put("daily_trend", jdbc.queryForList(
                "SELECT DATE(create_time) as date, SUM(count) as count FROM " + table +
                " WHERE create_time BETWEEN ? AND ? GROUP BY DATE(create_time) ORDER BY date",
                dr.startTime, dr.endTime));

        int offset = Math.max(0, (page - 1) * pageSize);
        result.put("recent_records", jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE create_time BETWEEN ? AND ? " +
                "ORDER BY update_time DESC, total_count DESC, count DESC LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, pageSize, offset));
        result.put("page", page);
        result.put("page_size", pageSize);
        result.put("total_pages", totalPages(tc, pageSize));
        return result;
    }

    /* ========== Cost Time statistics ========== */
    public Map<String, Object> costTimeStatistics(DateRange dr, int page, int pageSize,
                                                  int highCostPage, int highCostPageSize) {
        String table = "hitch_control_cost_time";
        if (!tableExists(table)) throw new TableMissingException(table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("start_date", dr.startDate);
        result.put("end_date", dr.endDate);
        result.put("columns", Arrays.asList("id", "trace_id", "method_name", "content",
                "time_cost", "log_time", "create_time"));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("id", "主键ID");
        labels.put("trace_id", "链路追踪ID");
        labels.put("method_name", "方法或接口名称");
        labels.put("content", "响应内容");
        labels.put("time_cost", "方法执行耗时（毫秒）");
        labels.put("log_time", "日志记录时间");
        labels.put("create_time", "记录入库时间");
        result.put("column_labels", labels);

        Long tc = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        result.put("total_count", tc == null ? 0L : tc);

        Long um = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT method_name) FROM " + table +
                " WHERE method_name IS NOT NULL AND method_name != '' AND create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        result.put("unique_method_count", um == null ? 0L : um);

        Map<String, Object> stats = jdbc.queryForMap(
                "SELECT AVG(time_cost) as avg_cost, MAX(time_cost) as max_cost, MIN(time_cost) as min_cost " +
                "FROM " + table + " WHERE time_cost IS NOT NULL AND time_cost > 0 AND create_time BETWEEN ? AND ?",
                dr.startTime, dr.endTime);
        Double avg = toDouble(stats.get("avg_cost"));
        result.put("avg_cost_time", avg == null ? 0 : Math.round(avg * 100.0) / 100.0);
        Long max = toLong(stats.get("max_cost"));
        Long min = toLong(stats.get("min_cost"));
        result.put("max_cost_time", max == null ? 0L : max);
        result.put("min_cost_time", min == null ? 0L : min);

        int hcOffset = Math.max(0, (highCostPage - 1) * highCostPageSize);
        result.put("high_cost_list", jdbc.queryForList(
                "SELECT * FROM " + table +
                " WHERE time_cost IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "ORDER BY time_cost DESC LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, highCostPageSize, hcOffset));
        result.put("high_cost_page", highCostPage);
        result.put("high_cost_page_size", highCostPageSize);
        result.put("high_cost_total_pages", totalPages(tc, highCostPageSize));

        result.put("method_avg_cost", jdbc.query(
                "SELECT method_name, COUNT(*) as count, AVG(time_cost) as avg_cost, MAX(time_cost) as max_cost " +
                "FROM " + table +
                " WHERE method_name IS NOT NULL AND method_name != '' AND create_time BETWEEN ? AND ? " +
                "GROUP BY method_name ORDER BY avg_cost DESC LIMIT 15",
                (rs, i) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("method_name", rs.getString("method_name"));
                    r.put("count", rs.getLong("count"));
                    double av = rs.getDouble("avg_cost");
                    r.put("avg_cost", rs.wasNull() ? 0d : Math.round(av * 100.0) / 100.0);
                    r.put("max_cost", rs.getLong("max_cost"));
                    return r;
                },
                dr.startTime, dr.endTime));

        // 耗时分布
        Long maxCost = jdbc.queryForObject(
                "SELECT COALESCE(MAX(time_cost), 0) FROM " + table +
                " WHERE time_cost IS NOT NULL AND create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime);
        int maxSeconds = maxCost == null ? 0 : (int) (maxCost / 1000) + 1;
        if (maxSeconds > 0 && maxSeconds < 3600) {
            StringBuilder caseParts = new StringBuilder();
            StringBuilder orderParts = new StringBuilder();
            for (int i = 0; i < maxSeconds; i++) {
                caseParts.append(" WHEN time_cost >= ").append(i * 1000)
                        .append(" AND time_cost < ").append((i + 1) * 1000)
                        .append(" THEN '").append(i).append("-").append(i + 1).append("s'");
                orderParts.append(" WHEN time_range = '").append(i).append("-").append(i + 1).append("s' THEN ")
                        .append(i);
            }
            String sql = "SELECT time_range, COUNT(*) as count FROM (" +
                    " SELECT CASE" + caseParts + " END as time_range FROM " + table +
                    " WHERE time_cost IS NOT NULL AND create_time BETWEEN ? AND ?" +
                    ") t WHERE time_range IS NOT NULL GROUP BY time_range " +
                    "ORDER BY CASE" + orderParts + " ELSE 999 END";
            result.put("cost_time_distribution",
                    jdbc.queryForList(sql, dr.startTime, dr.endTime));
        } else {
            result.put("cost_time_distribution", Collections.emptyList());
        }

        int offset = Math.max(0, (page - 1) * pageSize);
        result.put("recent_records", jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE create_time BETWEEN ? AND ? " +
                "ORDER BY create_time DESC LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, pageSize, offset));
        result.put("page", page);
        result.put("page_size", pageSize);
        result.put("total_pages", totalPages(tc, pageSize));
        return result;
    }

    /* ========== /table/:name/data ========== */
    public Map<String, Object> tableData(String table, int limit, int offset) {
        if (!ALLOWED_TABLES.contains(table)) throw new IllegalAccessError("不允许访问该表");
        if (!tableExists(table)) throw new TableMissingException(table);
        int lim = Math.min(Math.max(limit, 1), 500);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT * FROM " + table + " ORDER BY id DESC LIMIT ? OFFSET ?", lim, offset);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("total", total == null ? 0L : total);
        ret.put("limit", lim);
        ret.put("offset", offset);
        ret.put("records", records);
        return ret;
    }

    /* ========== helpers ========== */

    private String buildOrderClause(String sortField, String sortOrder, String defaultClause) {
        if (sortField == null || sortField.isEmpty() || !ALLOWED_SORT_FIELDS.contains(sortField)) {
            return defaultClause;
        }
        String dir = ("asc".equalsIgnoreCase(sortOrder)) ? "ASC" : "DESC";
        return "ORDER BY " + sortField + " " + dir;
    }

    private long totalPages(Long total, int pageSize) {
        if (total == null || total <= 0) return 1L;
        return (total + pageSize - 1) / pageSize;
    }

    /**
     * 根据 granularity 生成时间桶 SQL 表达式（二期图表粒度切换）。
     *
     * 安全性：SQL 拼接，granularity 必须严格白名单化，不可接受任意用户输入。
     *
     * 支持的粒度：
     *   "10m" → 10 分钟桶（yyyy-MM-dd HH:mm:00）
     *   "30m" → 30 分钟桶
     *   "1h"  → 小时桶（默认，向后兼容；格式 yyyy-MM-dd HH:00:00）
     *   "2h"  → 2 小时桶
     *   "3h"  → 3 小时桶
     *
     * 分钟桶用 DATE_SUB(create_time, INTERVAL MINUTE(create_time) % N MINUTE) 对齐；
     * 小时桶用 FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(create_time) / (N*3600)) * N * 3600)
     * 做 N 小时对齐，避免 DATE_FORMAT 只能到小时精度的限制。
     */
    private static String buildTimeBucketSql(String granularity) {
        String g = granularity == null ? "1h" : granularity.trim().toLowerCase();
        switch (g) {
            case "10m":
                return "DATE_FORMAT(" +
                       "DATE_SUB(create_time, INTERVAL MINUTE(create_time) % 10 MINUTE), " +
                       "'%Y-%m-%d %H:%i:00')";
            case "30m":
                return "DATE_FORMAT(" +
                       "DATE_SUB(create_time, INTERVAL MINUTE(create_time) % 30 MINUTE), " +
                       "'%Y-%m-%d %H:%i:00')";
            case "2h":
                return "FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(create_time) / 7200) * 7200, " +
                       "'%Y-%m-%d %H:00:00')";
            case "3h":
                return "FROM_UNIXTIME(FLOOR(UNIX_TIMESTAMP(create_time) / 10800) * 10800, " +
                       "'%Y-%m-%d %H:00:00')";
            case "1h":
            default:
                // 1h 是默认，未知粒度也退化到 1h（安全默认）
                return "DATE_FORMAT(create_time, '%Y-%m-%d %H:00:00')";
        }
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    private static Long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        return null;
    }
    private static Double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        return null;
    }

    public static class DateRange {
        public final String startDate, endDate, startTime, endTime;
        public DateRange(String s, String e, String st, String et) {
            this.startDate = s; this.endDate = e; this.startTime = st; this.endTime = et;
        }
    }

    public static class TableMissingException extends RuntimeException {
        public final String tableName;
        public TableMissingException(String t) { super("表不存在: " + t); this.tableName = t; }
    }
}
