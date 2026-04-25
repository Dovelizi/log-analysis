package com.loganalysis.service;

import com.loganalysis.service.DashboardService.DateRange;
import com.loganalysis.service.DashboardService.TableMissingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报表汇总服务，对齐 Python report_routes.get_report_summary + weekly_new_errors。
 *
 * - summary 复用 DashboardService 的 4 套 statistics 方法
 * - weekly-new-errors 对 control_hitch_error_mothod 做 7 天环比新增
 */
@Service
public class ReportSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ReportSummaryService.class);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private DashboardService dashboard;

    @Autowired
    private JdbcTemplate jdbc;

    /** 获取日报汇总（对齐 Python get_report_summary）。原接口 summary(date) 为仅日期版本。 */
    public Map<String, Object> summary(String date) {
        return summary(date, null, null);
    }

    /**
     * 支持 (日期 + 时间区间) 的日报汇总。
     * 性能优化：4 个子模块（control/gw/costTime/supplier）并行获取。
     */
    public Map<String, Object> summary(String date, String startTime, String endTime) {
        String targetDate = (date == null || date.isEmpty()) ? LocalDate.now().format(D) : date;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", targetDate);
        result.put("generated_at", LocalDateTime.now().toString());

        // 复用 DashboardService.parseDateRange 做时间区间校验 + 默认填充
        DashboardService.DateRange dr = dashboard.parseDateRange(targetDate, targetDate, startTime, endTime);

        // 并行获取 4 个子模块统计
        java.util.concurrent.CompletableFuture<Map<String, Object>> fControl =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return dashboard.hitchStatistics(
                                "control_hitch_error_mothod", InsertRecordService.LOG_FROM_CONTROL_HITCH,
                                dr, 1, 10, null, "desc");
                    } catch (TableMissingException e) { return null; }
                });
        java.util.concurrent.CompletableFuture<Map<String, Object>> fGw =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return dashboard.hitchStatistics(
                                "gw_hitch_error_mothod", InsertRecordService.LOG_FROM_GW_HITCH,
                                dr, 1, 10, null, "desc");
                    } catch (TableMissingException e) { return null; }
                });
        java.util.concurrent.CompletableFuture<Map<String, Object>> fCost =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return dashboard.costTimeStatistics(dr, 1, 10, 1, 10); }
                    catch (TableMissingException e) { return null; }
                });
        java.util.concurrent.CompletableFuture<Map<String, Object>> fSp =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return dashboard.supplierSpStatistics(dr, 1, 10); }
                    catch (TableMissingException e) { return null; }
                });

        Map<String, Object> control = safeGet(fControl);
        if (control != null) {
            result.put("control_total_errors", control.getOrDefault("total_error_count", 0L));
            result.put("control_error_trend", control.getOrDefault("trend_hourly", Collections.emptyList()));
            result.put("control_error_code_top10", control.getOrDefault("error_code_distribution", Collections.emptyList()));
        } else {
            result.put("control_total_errors", 0L);
            result.put("control_error_trend", Collections.emptyList());
            result.put("control_error_code_top10", Collections.emptyList());
        }

        Map<String, Object> gw = safeGet(fGw);
        if (gw != null) {
            result.put("gw_method_top10", gw.getOrDefault("method_distribution", Collections.emptyList()));
            result.put("gw_total_errors", gw.getOrDefault("total_error_count", 0L));
        } else {
            result.put("gw_method_top10", Collections.emptyList());
            result.put("gw_total_errors", 0L);
        }

        Map<String, Object> ct = safeGet(fCost);
        if (ct != null) {
            result.put("high_cost_top15", ct.getOrDefault("method_avg_cost", Collections.emptyList()));
            result.put("avg_cost_time", ct.getOrDefault("avg_cost_time", 0));
            result.put("max_cost_time", ct.getOrDefault("max_cost_time", 0L));
        } else {
            result.put("high_cost_top15", Collections.emptyList());
            result.put("avg_cost_time", 0);
            result.put("max_cost_time", 0L);
        }

        Map<String, Object> sp = safeGet(fSp);
        if (sp != null) {
            result.put("supplier_trend", sp.getOrDefault("trend_hourly", Collections.emptyList()));
            result.put("supplier_total_errors", sp.getOrDefault("total_error_count", 0L));
        } else {
            result.put("supplier_trend", Collections.emptyList());
            result.put("supplier_total_errors", 0L);
        }

        return result;
    }

    private static <T> T safeGet(java.util.concurrent.CompletableFuture<T> f) {
        try {
            return f.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 最近 7 天环比新增错误（对齐 Python get_weekly_new_errors）。
     * 从 endDate 向前推 7 天，每天与前一天的 (method_name, error_code) 组合做差集。
     *
     * 性能优化：原实现每天两次 SQL 共 14 次 RTT；新实现 1 次 SQL 拉 8 天全量按日分组，
     * Java 端做前后 diff。
     */
    public Map<String, Object> weeklyNewErrors(String endDate) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> daily = new ArrayList<>();
        result.put("daily_new_errors", daily);

        if (!dashboard.tableExists("control_hitch_error_mothod")) {
            return result;
        }

        LocalDate base;
        try {
            base = (endDate == null || endDate.isEmpty())
                    ? LocalDate.now()
                    : LocalDate.parse(endDate, D);
        } catch (Exception e) {
            base = LocalDate.now();
        }

        // 一次性拉 8 天（base-7 到 base）数据；按 (date, method, code, msg) 聚合
        LocalDate rangeStart = base.minusDays(7);
        String rs = rangeStart + " 00:00:00";
        String re = base + " 23:59:59";

        // 为了避开 GROUP BY DATE(create_time) 的索引失效，直接拉明细字段在 Java 侧分组
        // 8 天聚合表数据量一般 < 几万行，单表 range 扫描足够快
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT create_time, method_name, error_code, error_message, total_count " +
                "FROM control_hitch_error_mothod " +
                "WHERE create_time BETWEEN ? AND ?",
                rs, re);

        // 按日期分桶: date -> key(method, code) -> {method_name, error_code, error_message, total_count 累计}
        Map<String, Map<String, Map<String, Object>>> byDate = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Object ct = r.get("create_time");
            String d = null;
            if (ct instanceof java.sql.Timestamp) d = ((java.sql.Timestamp) ct).toLocalDateTime().toLocalDate().format(D);
            else if (ct instanceof LocalDateTime) d = ((LocalDateTime) ct).toLocalDate().format(D);
            if (d == null) continue;
            Object m = r.get("method_name");
            Object c = r.get("error_code");
            Object msg = r.get("error_message");
            Object tc = r.get("total_count");
            String key = keyOf(m, c);
            Map<String, Map<String, Object>> dayMap = byDate.computeIfAbsent(d, k -> new LinkedHashMap<>());
            Map<String, Object> entry = dayMap.get(key);
            if (entry == null) {
                entry = new LinkedHashMap<>();
                entry.put("method_name", m);
                entry.put("error_code", c);
                entry.put("error_message", msg);
                entry.put("total_count", 0L);
                dayMap.put(key, entry);
            }
            long cur = toLong(entry.get("total_count"));
            long add = toLong(tc);
            entry.put("total_count", cur + add);
        }

        for (int i = 0; i < 7; i++) {
            LocalDate cur = base.minusDays(i);
            LocalDate prev = cur.minusDays(1);
            Map<String, Map<String, Object>> curMap = byDate.getOrDefault(cur.format(D), Collections.emptyMap());
            Set<String> prevKeys = byDate.getOrDefault(prev.format(D), Collections.emptyMap()).keySet();

            List<Map<String, Object>> newErrors = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : curMap.entrySet()) {
                if (!prevKeys.contains(e.getKey())) newErrors.add(e.getValue());
            }
            newErrors.sort((a, b) -> Long.compare(
                    toLong(b.get("total_count")), toLong(a.get("total_count"))));
            if (newErrors.size() > 20) newErrors = newErrors.subList(0, 20);

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", cur.toString());
            day.put("compared_to", prev.toString());
            day.put("new_count", newErrors.size());
            day.put("errors", newErrors);
            daily.add(day);
        }
        return result;
    }

    /**
     * 生成报表 HTML（对齐 Python generate_report_html）。
     * 返回完整的 HTML 文档字符串，包含内联 CSS 和三个 TOP 表格。
     */
    public String generateReportHtml(Map<String, Object> reportData) {
        String dateStr = strOrDefault(reportData.get("date"),
                LocalDate.now().format(D));
        Object controlTotal = reportData.getOrDefault("control_total_errors", 0);
        Object gwTotal = reportData.getOrDefault("gw_total_errors", 0);
        Object maxCost = reportData.getOrDefault("max_cost_time", 0);
        Object avgCostObj = reportData.getOrDefault("avg_cost_time", 0);
        double avgCost = (avgCostObj instanceof Number) ? ((Number) avgCostObj).doubleValue() : 0d;

        StringBuilder sb = new StringBuilder(8192);
        sb.append("\n<!DOCTYPE html>\n")
          .append("<html lang=\"zh-CN\">\n")
          .append("<head>\n")
          .append("    <meta charset=\"UTF-8\">\n")
          .append("    <title>日报汇总 - ").append(escape(dateStr)).append("</title>\n")
          .append("    <style>\n")
          .append("        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif; background: #0f1419; color: #e6edf3; padding: 20px; }\n")
          .append("        .container { max-width: 1200px; margin: 0 auto; }\n")
          .append("        h1 { color: #58a6ff; border-bottom: 1px solid #30363d; padding-bottom: 10px; }\n")
          .append("        h2 { color: #8b949e; margin-top: 30px; }\n")
          .append("        .stat-card { display: inline-block; background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 20px; margin: 10px; min-width: 200px; }\n")
          .append("        .stat-value { font-size: 32px; font-weight: bold; color: #58a6ff; }\n")
          .append("        .stat-label { color: #8b949e; margin-top: 5px; }\n")
          .append("        table { width: 100%; border-collapse: collapse; margin-top: 15px; }\n")
          .append("        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #30363d; }\n")
          .append("        th { background: #0d1117; color: #8b949e; font-weight: 500; }\n")
          .append("        td { color: #c9d1d9; }\n")
          .append("        .badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 12px; }\n")
          .append("        .badge-danger { background: rgba(248, 81, 73, 0.2); color: #f85149; }\n")
          .append("        code { background: #21262d; padding: 2px 6px; border-radius: 4px; font-size: 13px; }\n")
          .append("    </style>\n")
          .append("</head>\n")
          .append("<body>\n")
          .append("    <div class=\"container\">\n")
          .append("        <h1>📊 日报汇总 - ").append(escape(dateStr)).append("</h1>\n")
          .append("        <div class=\"stat-card\"><div class=\"stat-value\">").append(controlTotal)
          .append("</div><div class=\"stat-label\">Control 累计错误数</div></div>\n")
          .append("        <div class=\"stat-card\"><div class=\"stat-value\">").append(gwTotal)
          .append("</div><div class=\"stat-label\">GW 累计错误数</div></div>\n")
          .append("        <div class=\"stat-card\"><div class=\"stat-value\">").append(maxCost)
          .append("ms</div><div class=\"stat-label\">最大耗时</div></div>\n")
          .append("        <div class=\"stat-card\"><div class=\"stat-value\">")
          .append(round2(avgCost)).append("ms</div><div class=\"stat-label\">平均耗时</div></div>\n");

        // 错误码分布 TOP10
        sb.append("        <h2>错误码分布 TOP10</h2>\n")
          .append("        <table>\n")
          .append("            <thead><tr><th>排名</th><th>错误码</th><th>次数</th></tr></thead>\n")
          .append("            <tbody>\n");
        List<?> codes = listOrEmpty(reportData.get("control_error_code_top10"));
        int i = 1;
        for (Object o : limit(codes, 10)) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Object code = m.get("error_code");
            Object count = m.get("count");
            sb.append("                <tr><td>").append(i++).append("</td>")
              .append("<td><span class='badge badge-danger'>").append(escape(code == null ? "-" : code.toString())).append("</span></td>")
              .append("<td>").append(count == null ? 0 : count).append("</td></tr>\n");
        }
        sb.append("            </tbody>\n        </table>\n");

        // GW 方法报错 TOP10
        sb.append("        <h2>GW 方法报错 TOP10</h2>\n")
          .append("        <table>\n")
          .append("            <thead><tr><th>排名</th><th>方法名</th><th>次数</th></tr></thead>\n")
          .append("            <tbody>\n");
        List<?> gwMethods = listOrEmpty(reportData.get("gw_method_top10"));
        i = 1;
        for (Object o : limit(gwMethods, 10)) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Object name = m.get("method_name");
            Object count = m.get("count");
            sb.append("                <tr><td>").append(i++).append("</td>")
              .append("<td><code>").append(escape(name == null ? "-" : name.toString())).append("</code></td>")
              .append("<td>").append(count == null ? 0 : count).append("</td></tr>\n");
        }
        sb.append("            </tbody>\n        </table>\n");

        // 高耗时 TOP15
        sb.append("        <h2>顺风车高耗时接口 TOP15</h2>\n")
          .append("        <table>\n")
          .append("            <thead><tr><th>排名</th><th>方法名</th><th>最大耗时(ms)</th><th>平均耗时(ms)</th></tr></thead>\n")
          .append("            <tbody>\n");
        List<?> hc = listOrEmpty(reportData.get("high_cost_top15"));
        i = 1;
        for (Object o : limit(hc, 15)) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Object name = m.get("method_name");
            Object mxc = m.get("max_cost");
            if (mxc == null) mxc = m.get("max_cost_time");
            if (mxc == null) mxc = 0;
            Object avc = m.get("avg_cost");
            if (avc == null) avc = m.get("avg_cost_time");
            double avcNum = (avc instanceof Number) ? ((Number) avc).doubleValue() : 0d;
            sb.append("                <tr><td>").append(i++).append("</td>")
              .append("<td><code>").append(escape(name == null ? "-" : name.toString())).append("</code></td>")
              .append("<td>").append(mxc).append("</td>")
              .append("<td>").append(round2(avcNum)).append("</td></tr>\n");
        }
        sb.append("            </tbody>\n        </table>\n");

        sb.append("        <p style=\"margin-top: 30px; color: #8b949e; font-size: 12px;\">生成时间: ")
          .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
          .append("</p>\n")
          .append("    </div>\n")
          .append("</body>\n")
          .append("</html>\n");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String strOrDefault(Object v, String def) {
        if (v == null) return def;
        String s = v.toString();
        return s.isEmpty() ? def : s;
    }

    private static List<?> listOrEmpty(Object v) {
        return v instanceof List ? (List<?>) v : Collections.emptyList();
    }

    private static List<?> limit(List<?> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String keyOf(Object method, Object code) {
        return (method == null ? "" : method.toString()) + "\u0001" + (code == null ? "" : code.toString());
    }

    private static long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return 0L;
    }
}
