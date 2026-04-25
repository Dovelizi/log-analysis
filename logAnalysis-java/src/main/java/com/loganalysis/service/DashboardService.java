package com.loganalysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Dashboard 统计服务，对齐 routes/dashboard_routes.py。
 *
 * 所有 5 张业务表 (control_hitch_error_mothod / gw_hitch_error_mothod /
 * hitch_control_cost_time / hitch_supplier_error_sp / hitch_supplier_error_total)
 * 共享相同的"按 create_time 日期+时间范围过滤 + COUNT/SUM/GROUP BY"模板。
 *
 * 日期+时间范围策略（parseDateRange）：
 *   未传日期 → 当天
 *   未传时间 → 00:00:00 ~ 23:59:59
 *   区间 > 7 天或开始晚于结束 → 抛 IllegalArgumentException
 *
 * 性能原则：
 *   1. WHERE 必须命中 create_time 或 (create_time, xxx) 组合索引，禁止 DATE(create_time) 这类函数
 *   2. 同一接口内多个独立 SQL 并行发（IO_POOL），串行 8 次 200ms → 并行 1 次 200ms
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    public static final int MAX_DATE_RANGE_DAYS = 7;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss");

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

    /**
     * 接口内并行 SQL 的线程池。
     * 大小 = 8 足以覆盖单接口内最多 7 个并行查询；HikariCP 上限 20，不会撑爆连接池。
     */
    private final ExecutorService ioPool = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "dashboard-io");
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    public void shutdown() {
        ioPool.shutdown();
        try {
            if (!ioPool.awaitTermination(5, TimeUnit.SECONDS)) ioPool.shutdownNow();
        } catch (InterruptedException e) {
            ioPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 解析日期+时间范围参数（四参版本，主入口）。
     *   - startDate/endDate: yyyy-MM-dd；若为空则默认当天
     *   - startTime/endTime: HH:mm 或 HH:mm:ss；若为空则默认 00:00:00 / 23:59:59
     */
    public DateRange parseDateRange(String startDate, String endDate,
                                    String startTime, String endTime) {
        String sd, ed;
        if (isEmpty(startDate) || isEmpty(endDate)) {
            String today = LocalDate.now().format(D);
            sd = today;
            ed = today;
        } else {
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
            sd = startDate;
            ed = endDate;
        }
        String st = normalizeTime(startTime, "00:00:00");
        String et = normalizeTime(endTime,   "23:59:59");
        // 同日期时校验 startTime < endTime
        if (sd.equals(ed) && st.compareTo(et) > 0) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        return new DateRange(sd, ed, sd + " " + st, ed + " " + et);
    }

    /** 旧签名兼容：仅日期，时间默认 00:00:00 ~ 23:59:59 */
    public DateRange parseDateRange(String startDate, String endDate) {
        return parseDateRange(startDate, endDate, null, null);
    }

    /** 校验并规范化 HH:mm 或 HH:mm:ss 格式为 HH:mm:ss；空则返回 fallback */
    private static String normalizeTime(String raw, String fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        String s = raw.trim();
        try {
            // HH:mm -> 补全秒；HH:mm:ss -> 原样返回
            LocalTime t;
            if (s.length() == 5) t = LocalTime.parse(s, DateTimeFormatter.ofPattern("HH:mm"));
            else t = LocalTime.parse(s, T);
            return t.format(T);
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式错误，请使用HH:mm或HH:mm:ss格式: " + raw);
        }
    }

    /** 检查表是否存在（MySQL） */
    public boolean tableExists(String tableName) {
        if (!ALLOWED_TABLES.contains(tableName)) return false;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SHOW TABLES LIKE ?", tableName);
            return !rows.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /* ========== available-dates ==========
     * 原实现: SELECT DISTINCT DATE(create_time) 每表跑一次，函数包装理论上不走索引
     *         但实测在当前数据量下走范围扫索引 + Using temporary/DISTINCT 仍 < 100ms
     * 旧改版: SELECT create_time 返回几万行再 Java 端去重，反而网络 + 序列化拖累到 120ms × 5 = 600ms
     * 新实现: 回归 DISTINCT DATE(create_time)，返回最多 7 行，网络忽略不计；5 张表并行
     */
    public Map<String, Object> availableDates() {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        String startTime = start.format(D) + " 00:00:00";
        String endTime = today.format(D) + " 23:59:59";

        List<CompletableFuture<Set<String>>> futures = new ArrayList<>(ALLOWED_TABLES.size());
        for (String t : ALLOWED_TABLES) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (!tableExists(t)) return Collections.<String>emptySet();
                try {
                    List<String> rows = jdbc.query(
                            "SELECT DISTINCT DATE(create_time) as date_val FROM " + t +
                            " WHERE create_time BETWEEN ? AND ?",
                            (rs, i) -> {
                                java.sql.Date d = rs.getDate(1);
                                return d == null ? null : d.toLocalDate().format(D);
                            },
                            startTime, endTime);
                    Set<String> s = new HashSet<>();
                    for (String v : rows) if (v != null) s.add(v);
                    return s;
                } catch (Exception ignored) {
                    return Collections.<String>emptySet();
                }
            }, ioPool));
        }

        Set<String> merged = new TreeSet<>(Comparator.reverseOrder());
        for (CompletableFuture<Set<String>> f : futures) {
            try {
                merged.addAll(f.get(10, TimeUnit.SECONDS));
            } catch (Exception ignored) {}
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("dates", new ArrayList<>(merged));
        ret.put("today", today.format(D));
        return ret;
    }

    /* ========== overview ==========
     * 5 张表并行 COUNT(*)，串行 5×RTT → 1×RTT。
     */
    public Map<String, Object> overview(DateRange dr) {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("start_date", dr.startDate);
        ret.put("end_date", dr.endDate);

        Map<String, CompletableFuture<Map<String, Object>>> tasks = new LinkedHashMap<>();
        for (String t : ALLOWED_TABLES) {
            tasks.put(t, CompletableFuture.supplyAsync(() -> {
                Map<String, Object> sub = new LinkedHashMap<>();
                if (!tableExists(t)) {
                    sub.put("exists", false);
                    sub.put("total", 0);
                    return sub;
                }
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
                return sub;
            }, ioPool));
        }

        for (Map.Entry<String, CompletableFuture<Map<String, Object>>> e : tasks.entrySet()) {
            try {
                ret.put(e.getKey(), e.getValue().get(15, TimeUnit.SECONDS));
            } catch (Exception ex) {
                Map<String, Object> sub = new LinkedHashMap<>();
                sub.put("exists", false);
                sub.put("total", 0);
                sub.put("error", ex.getMessage());
                ret.put(e.getKey(), sub);
            }
        }
        return ret;
    }

    /* ========== 通用 statistics（control_hitch / gw_hitch） ==========
     * 所有独立聚合并行执行，8 次串行 → 1 次 RTT。
     */
    public Map<String, Object> hitchStatistics(String table, int logFrom, DateRange dr,
                                               int page, int pageSize,
                                               String sortField, String sortOrder) {
        if (!tableExists(table)) throw new TableMissingException(table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("start_date", dr.startDate);
        result.put("end_date", dr.endDate);

        final boolean isControl = "control_hitch_error_mothod".equals(table);

        // 并行提交所有独立查询
        CompletableFuture<Long> fTotal = async(() -> orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime)));

        CompletableFuture<Long> fTotalErr = async(() -> orZero(jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_count), 0) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime)));

        CompletableFuture<Long> fErrCodeCnt = async(() -> {
            if (isControl) {
                return orZero(jdbc.queryForObject(
                        "SELECT COUNT(error_code) FROM " + table +
                        " WHERE error_code IS NOT NULL AND create_time BETWEEN ? AND ?",
                        Long.class, dr.startTime, dr.endTime));
            }
            return null;
        });

        CompletableFuture<Long> fUniqueMethod = async(() -> {
            if (isControl) {
                return orZero(jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT method_name) FROM " + table +
                        " WHERE method_name IS NOT NULL AND method_name != '' AND create_time BETWEEN ? AND ?",
                        Long.class, dr.startTime, dr.endTime));
            }
            return orZero(jdbc.queryForObject(
                    "SELECT COALESCE(SUM(total_count), 0) FROM " + table +
                    " WHERE total_count IS NOT NULL AND create_time BETWEEN ? AND ?",
                    Long.class, dr.startTime, dr.endTime));
        });

        CompletableFuture<List<Map<String, Object>>> fErrCodeDist = async(() -> jdbc.queryForList(
                "SELECT error_code, SUM(total_count) as count FROM " + table +
                " WHERE error_code IS NOT NULL AND create_time BETWEEN ? AND ? " +
                " GROUP BY error_code ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));

        CompletableFuture<List<Map<String, Object>>> fMethodDist = async(() -> jdbc.queryForList(
                "SELECT method_name, SUM(total_count) as count FROM " + table +
                " WHERE method_name IS NOT NULL AND method_name != '' AND create_time BETWEEN ? AND ? " +
                " GROUP BY method_name ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));

        CompletableFuture<List<Map<String, Object>>> fTrend = async(() -> jdbc.queryForList(
                "SELECT DATE_FORMAT(create_time, '%Y-%m-%d %H:00:00') as time_bucket, SUM(count) as count " +
                "FROM hitch_error_log_insert_record WHERE log_from = ? AND create_time BETWEEN ? AND ? " +
                "GROUP BY time_bucket ORDER BY time_bucket",
                logFrom, dr.startTime, dr.endTime));

        final int offset = Math.max(0, (page - 1) * pageSize);
        final String orderClause = buildOrderClause(sortField, sortOrder,
                "ORDER BY update_time DESC, total_count DESC, count DESC");
        CompletableFuture<List<Map<String, Object>>> fRecent = async(() -> jdbc.queryForList(
                "SELECT id, method_name, error_code, error_message, count, total_count, update_time " +
                "FROM " + table + " WHERE create_time BETWEEN ? AND ? " + orderClause +
                " LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, pageSize, offset));

        // 汇总
        long totalCount = join(fTotal, 0L);
        result.put("total_count", totalCount);
        result.put("total_error_count", join(fTotalErr, 0L));
        if (isControl) {
            result.put("unique_error_code_count", join(fErrCodeCnt, 0L));
            result.put("unique_method_count", join(fUniqueMethod, 0L));
        } else {
            result.put("unique_method_count", join(fUniqueMethod, 0L));
        }
        result.put("error_code_distribution", join(fErrCodeDist, Collections.emptyList()));
        result.put("method_distribution", join(fMethodDist, Collections.emptyList()));
        result.put("trend_hourly", join(fTrend, Collections.emptyList()));
        result.put("recent_errors", join(fRecent, Collections.emptyList()));
        result.put("page", page);
        result.put("page_size", pageSize);
        result.put("total_pages", totalPages(totalCount, pageSize));
        return result;
    }

    /* ========== 通用 aggregation（control_hitch / gw_hitch） ========== */
    public Map<String, Object> hitchAggregation(String table, int page, int pageSize) {
        if (!tableExists(table)) throw new TableMissingException(table);
        final int offset = Math.max(0, (page - 1) * pageSize);

        CompletableFuture<Long> fTotal = async(() -> orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Long.class)));
        CompletableFuture<List<Map<String, Object>>> fRows = async(() -> jdbc.queryForList(
                "SELECT id, method_name, error_code, error_message, count, total_count, update_time " +
                "FROM " + table +
                " ORDER BY update_time DESC, total_count DESC, count DESC " +
                " LIMIT ? OFFSET ?", pageSize, offset));

        long totalCount = join(fTotal, 0L);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("exists", true);
        ret.put("aggregation", join(fRows, Collections.emptyList()));
        ret.put("page", page);
        ret.put("page_size", pageSize);
        ret.put("total_count", totalCount);
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
            final Integer spIdFinal = spId;
            final int offset = Math.max(0, (page - 1) * pageSize);
            CompletableFuture<Long> fTotal = async(() -> orZero(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table +
                    " WHERE sp_id = ? AND create_time BETWEEN ? AND ?",
                    Long.class, spIdFinal, dr.startTime, dr.endTime)));
            CompletableFuture<List<Map<String, Object>>> fRows = async(() -> jdbc.queryForList(
                    "SELECT id, method_name, error_code, error_message, count, total_count, update_time " +
                    "FROM " + table + " WHERE sp_id = ? AND create_time BETWEEN ? AND ? " +
                    orderClause + " LIMIT ? OFFSET ?",
                    spIdFinal, dr.startTime, dr.endTime, pageSize, offset));
            totalCount = join(fTotal, 0L);
            aggregation = join(fRows, Collections.emptyList());
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
        String table = "hitch_supplier_error_sp";
        if (!tableExists(table)) throw new TableMissingException(table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("start_date", dr.startDate);
        result.put("end_date", dr.endDate);

        CompletableFuture<Long> fTotal = async(() -> orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime)));
        CompletableFuture<Long> fTotalErr = async(() -> orZero(jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_count), 0) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime)));
        CompletableFuture<List<Map<String, Object>>> fSupplierDist = async(() -> jdbc.queryForList(
                "SELECT sp_id, sp_name, SUM(total_count) as count FROM " + table +
                " WHERE sp_id IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "GROUP BY sp_id, sp_name ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));
        CompletableFuture<List<Map<String, Object>>> fErrCodeDist = async(() -> jdbc.queryForList(
                "SELECT error_code, SUM(total_count) as count FROM " + table +
                " WHERE error_code IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "GROUP BY error_code ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));
        CompletableFuture<List<Map<String, Object>>> fTrend = async(() -> jdbc.queryForList(
                "SELECT sp_id, DATE_FORMAT(create_time, '%Y-%m-%d %H:00:00') as time_bucket, SUM(count) as count " +
                "FROM hitch_error_log_insert_record WHERE log_from = 3 AND create_time BETWEEN ? AND ? " +
                "GROUP BY sp_id, time_bucket ORDER BY time_bucket",
                dr.startTime, dr.endTime));
        final int offset = Math.max(0, (page - 1) * pageSize);
        CompletableFuture<List<Map<String, Object>>> fRecent = async(() -> jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE create_time BETWEEN ? AND ? " +
                "ORDER BY update_time DESC, total_count DESC, count DESC LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, pageSize, offset));

        long tc = join(fTotal, 0L);
        result.put("total_count", tc);
        result.put("total_error_count", join(fTotalErr, 0L));
        result.put("supplier_distribution", join(fSupplierDist, Collections.emptyList()));
        result.put("error_code_distribution", join(fErrCodeDist, Collections.emptyList()));
        result.put("trend_hourly", join(fTrend, Collections.emptyList()));
        result.put("recent_records", join(fRecent, Collections.emptyList()));
        result.put("page", page);
        result.put("page_size", pageSize);
        result.put("total_pages", totalPages(tc, pageSize));
        return result;
    }

    /* ========== Supplier Total statistics ========== */
    public Map<String, Object> supplierTotalStatistics(DateRange dr, int page, int pageSize) {
        String table = "hitch_supplier_error_total";
        if (!tableExists(table)) throw new TableMissingException(table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("start_date", dr.startDate);
        result.put("end_date", dr.endDate);

        CompletableFuture<Long> fTotal = async(() -> orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime)));
        CompletableFuture<Long> fTotalErr = async(() -> orZero(jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_count), 0) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime)));
        CompletableFuture<List<Map<String, Object>>> fSummary = async(() -> jdbc.queryForList(
                "SELECT sp_id, SUM(total_count) as total_errors FROM " + table +
                " WHERE sp_id IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "GROUP BY sp_id ORDER BY total_errors DESC LIMIT 10",
                dr.startTime, dr.endTime));
        CompletableFuture<List<Map<String, Object>>> fErrCode = async(() -> jdbc.queryForList(
                "SELECT error_code, SUM(total_count) as count FROM " + table +
                " WHERE error_code IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "GROUP BY error_code ORDER BY count DESC LIMIT 10",
                dr.startTime, dr.endTime));
        CompletableFuture<List<Map<String, Object>>> fHourly = async(() -> jdbc.queryForList(
                "SELECT sp_id, DATE_FORMAT(create_time, '%Y-%m-%d %H:00:00') as time_bucket, SUM(count) as count " +
                "FROM hitch_error_log_insert_record WHERE log_from = 4 AND create_time BETWEEN ? AND ? " +
                "GROUP BY sp_id, time_bucket ORDER BY time_bucket",
                dr.startTime, dr.endTime));
        // daily 趋势：用 create_time 索引扫描后 Java 端按日期分组，避免 DATE(create_time) 导致索引失效
        CompletableFuture<List<Map<String, Object>>> fDaily = async(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT create_time, count FROM " + table +
                    " WHERE create_time BETWEEN ? AND ?",
                    dr.startTime, dr.endTime);
            Map<String, Long> agg = new TreeMap<>();
            for (Map<String, Object> r : rows) {
                Object ct = r.get("create_time");
                String d = null;
                if (ct instanceof java.sql.Timestamp) {
                    d = ((java.sql.Timestamp) ct).toLocalDateTime().toLocalDate().format(D);
                } else if (ct instanceof java.time.LocalDateTime) {
                    d = ((java.time.LocalDateTime) ct).toLocalDate().format(D);
                }
                if (d == null) continue;
                long c = 0;
                Object cv = r.get("count");
                if (cv instanceof Number) c = ((Number) cv).longValue();
                agg.merge(d, c, Long::sum);
            }
            List<Map<String, Object>> out = new ArrayList<>(agg.size());
            for (Map.Entry<String, Long> e : agg.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", e.getKey());
                m.put("count", e.getValue());
                out.add(m);
            }
            return out;
        });
        final int offset = Math.max(0, (page - 1) * pageSize);
        CompletableFuture<List<Map<String, Object>>> fRecent = async(() -> jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE create_time BETWEEN ? AND ? " +
                "ORDER BY update_time DESC, total_count DESC, count DESC LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, pageSize, offset));

        long tc = join(fTotal, 0L);
        result.put("total_count", tc);
        result.put("total_error_count", join(fTotalErr, 0L));
        result.put("supplier_error_summary", join(fSummary, Collections.emptyList()));
        result.put("error_code_distribution", join(fErrCode, Collections.emptyList()));
        result.put("hourly_trend", join(fHourly, Collections.emptyList()));
        result.put("daily_trend", join(fDaily, Collections.emptyList()));
        result.put("recent_records", join(fRecent, Collections.emptyList()));
        result.put("page", page);
        result.put("page_size", pageSize);
        result.put("total_pages", totalPages(tc, pageSize));
        return result;
    }

    /* ========== Cost Time statistics ==========
     * 性能关键改造：
     *   - 原来 cost_time_distribution 动态拼 N 个 WHEN 分支，N = max(time_cost)/1000，可能上百上千个
     *   - 新实现: FLOOR(time_cost/1000) AS sec_bucket GROUP BY sec_bucket，一条 SQL 搞定
     *   - 所有独立聚合并行
     */
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

        CompletableFuture<Long> fTotal = async(() -> orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime)));
        CompletableFuture<Long> fUnique = async(() -> orZero(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT method_name) FROM " + table +
                " WHERE method_name IS NOT NULL AND method_name != '' AND create_time BETWEEN ? AND ?",
                Long.class, dr.startTime, dr.endTime)));
        CompletableFuture<Map<String, Object>> fStats = async(() -> jdbc.queryForMap(
                "SELECT AVG(time_cost) as avg_cost, MAX(time_cost) as max_cost, MIN(time_cost) as min_cost " +
                "FROM " + table + " WHERE time_cost IS NOT NULL AND time_cost > 0 AND create_time BETWEEN ? AND ?",
                dr.startTime, dr.endTime));
        final int hcOffset = Math.max(0, (highCostPage - 1) * highCostPageSize);
        CompletableFuture<List<Map<String, Object>>> fHigh = async(() -> jdbc.queryForList(
                "SELECT * FROM " + table +
                " WHERE time_cost IS NOT NULL AND create_time BETWEEN ? AND ? " +
                "ORDER BY time_cost DESC LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, highCostPageSize, hcOffset));
        CompletableFuture<List<Map<String, Object>>> fMethodAvg = async(() -> {
            // 性能关键：原 SQL ORDER BY AVG(time_cost) DESC LIMIT 15 要 filesort，单独 3.78s
            // 改为 SQL 仅 GROUP BY（0.11s），Java 端排序 + 取前 15。总行数 = DISTINCT method_name 数，通常 < 200
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT method_name, COUNT(*) as count, AVG(time_cost) as avg_cost, MAX(time_cost) as max_cost " +
                    "FROM " + table +
                    " WHERE method_name IS NOT NULL AND method_name != '' AND create_time BETWEEN ? AND ? " +
                    "GROUP BY method_name",
                    (rs, i) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("method_name", rs.getString("method_name"));
                        r.put("count", rs.getLong("count"));
                        double av = rs.getDouble("avg_cost");
                        r.put("avg_cost", rs.wasNull() ? 0d : Math.round(av * 100.0) / 100.0);
                        r.put("max_cost", rs.getLong("max_cost"));
                        return r;
                    },
                    dr.startTime, dr.endTime);
            rows.sort((a, b) -> Double.compare(
                    ((Number) b.get("avg_cost")).doubleValue(),
                    ((Number) a.get("avg_cost")).doubleValue()));
            return rows.size() <= 15 ? rows : new ArrayList<>(rows.subList(0, 15));
        });
        // 一条 SQL 解决耗时分布，不再动态拼 N 个 WHEN
        CompletableFuture<List<Map<String, Object>>> fDist = async(() -> {
            List<Map<String, Object>> raw = jdbc.queryForList(
                    "SELECT FLOOR(time_cost/1000) AS sec_bucket, COUNT(*) as count FROM " + table +
                    " WHERE time_cost IS NOT NULL AND create_time BETWEEN ? AND ? " +
                    " GROUP BY sec_bucket ORDER BY sec_bucket",
                    dr.startTime, dr.endTime);
            List<Map<String, Object>> out = new ArrayList<>(raw.size());
            for (Map<String, Object> r : raw) {
                Object b = r.get("sec_bucket");
                long bucket = (b instanceof Number) ? ((Number) b).longValue() : 0L;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time_range", bucket + "-" + (bucket + 1) + "s");
                m.put("count", r.get("count"));
                out.add(m);
            }
            return out;
        });
        final int offset = Math.max(0, (page - 1) * pageSize);
        CompletableFuture<List<Map<String, Object>>> fRecent = async(() -> jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE create_time BETWEEN ? AND ? " +
                "ORDER BY create_time DESC LIMIT ? OFFSET ?",
                dr.startTime, dr.endTime, pageSize, offset));

        long tc = join(fTotal, 0L);
        result.put("total_count", tc);
        result.put("unique_method_count", join(fUnique, 0L));

        Map<String, Object> stats = join(fStats, Collections.emptyMap());
        Double avg = toDouble(stats.get("avg_cost"));
        result.put("avg_cost_time", avg == null ? 0 : Math.round(avg * 100.0) / 100.0);
        Long max = toLong(stats.get("max_cost"));
        Long min = toLong(stats.get("min_cost"));
        result.put("max_cost_time", max == null ? 0L : max);
        result.put("min_cost_time", min == null ? 0L : min);

        result.put("high_cost_list", join(fHigh, Collections.emptyList()));
        result.put("high_cost_page", highCostPage);
        result.put("high_cost_page_size", highCostPageSize);
        result.put("high_cost_total_pages", totalPages(tc, highCostPageSize));
        result.put("method_avg_cost", join(fMethodAvg, Collections.emptyList()));
        result.put("cost_time_distribution", join(fDist, Collections.emptyList()));
        result.put("recent_records", join(fRecent, Collections.emptyList()));
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

    private <T> CompletableFuture<T> async(java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, ioPool);
    }

    private static <T> T join(CompletableFuture<T> f, T fallback) {
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long orZero(Long v) { return v == null ? 0L : v; }

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

    private long totalPages(long total, int pageSize) {
        if (total <= 0) return 1L;
        return (total + pageSize - 1) / pageSize;
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
