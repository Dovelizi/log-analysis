package com.loganalysis.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportSummaryService.generateReportHtml 的纯单元测试。
 * 不触发 DB / Dashboard 调用。
 */
class ReportSummaryServiceTest {

    /** 手动 new 一个实例即可（generateReportHtml 不依赖注入的 bean） */
    private final ReportSummaryService svc = new ReportSummaryService();

    @Test
    void html_containsCoreStatsAndTitle() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        data.put("control_total_errors", 123);
        data.put("gw_total_errors", 45);
        data.put("max_cost_time", 3500);
        data.put("avg_cost_time", 270.5678);

        String html = svc.generateReportHtml(data);
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("日报汇总 - 2026-04-25");
        assertThat(html).contains(">123<");
        assertThat(html).contains(">45<");
        assertThat(html).contains("3500ms");
        // round2(270.5678) == 270.57
        assertThat(html).contains("270.57ms");
    }

    @Test
    void html_rendersErrorCodeRows() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        List<Map<String, Object>> codes = new ArrayList<>();
        codes.add(codeEntry(500, 10));
        codes.add(codeEntry(404, 5));
        data.put("control_error_code_top10", codes);

        String html = svc.generateReportHtml(data);
        assertThat(html).contains("错误码分布 TOP10")
                .contains(">500<").contains(">10<")
                .contains(">404<").contains(">5<");
    }

    @Test
    void html_escapesHtmlInMethodName() {
        // 防御 XSS：方法名中如果有 <script>，应被 escape
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        List<Map<String, Object>> methods = new ArrayList<>();
        Map<String, Object> m = new HashMap<>();
        m.put("method_name", "<script>alert(1)</script>");
        m.put("count", 1);
        methods.add(m);
        data.put("gw_method_top10", methods);

        String html = svc.generateReportHtml(data);
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void html_highCostFallbackToMaxCostTime() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        List<Map<String, Object>> hc = new ArrayList<>();
        Map<String, Object> m = new HashMap<>();
        m.put("method_name", "/api/slow");
        m.put("max_cost_time", 2000);   // 只有 max_cost_time（没有 max_cost）
        m.put("avg_cost_time", 1000);
        hc.add(m);
        data.put("high_cost_top15", hc);

        String html = svc.generateReportHtml(data);
        assertThat(html).contains("/api/slow").contains(">2000<").contains(">1000.0<");
    }

    @Test
    void html_emptyDataStillRendersSkeletonWithDefaults() {
        Map<String, Object> data = new LinkedHashMap<>();
        // 不传任何字段
        String html = svc.generateReportHtml(data);
        assertThat(html)
                .contains("<!DOCTYPE html>")
                .contains("错误码分布 TOP10")
                .contains("GW 方法报错 TOP10")
                .contains("顺风车高耗时接口 TOP15")
                .contains("</html>");
        // 0 值兜底
        assertThat(html).contains(">0<");
    }

    private static Map<String, Object> codeEntry(int code, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put("error_code", code);
        m.put("count", count);
        return m;
    }
}
