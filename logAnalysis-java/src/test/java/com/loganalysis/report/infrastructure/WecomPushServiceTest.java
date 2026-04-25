package com.loganalysis.report.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WecomPushService.generateMarkdown 的纯单元测试（不触发真实 HTTP）。
 */
class WecomPushServiceTest {

    private final WecomPushService svc = new WecomPushService();

    @Test
    void markdown_containsDateAndTotal() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        data.put("control_total_errors", 123);
        String md = svc.generateMarkdown(data);
        assertThat(md).contains("2026-04-25");
        assertThat(md).contains("累计错误数: **123**");
    }

    @Test
    void markdown_topCodes() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        List<Map<String, Object>> codes = new ArrayList<>();
        codes.add(codeEntry(500, 10));
        codes.add(codeEntry(404, 5));
        data.put("control_error_code_top10", codes);
        String md = svc.generateMarkdown(data);
        assertThat(md).contains("`500`").contains("10次");
        assertThat(md).contains("`404`").contains("5次");
    }

    @Test
    void markdown_truncatesLongMethodName() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        List<Map<String, Object>> methods = new ArrayList<>();
        Map<String, Object> m1 = new HashMap<>();
        m1.put("method_name", "/very/long/method/name/that/exceeds/thirty/characters/limit");
        m1.put("count", 1);
        methods.add(m1);
        data.put("gw_method_top10", methods);
        String md = svc.generateMarkdown(data);
        assertThat(md).contains("...");
    }

    @Test
    void markdown_highCostMaxFallback() {
        // Python 代码：max_cost = item.get('max_cost', item.get('max_cost_time', 0))
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        List<Map<String, Object>> hc = new ArrayList<>();
        Map<String, Object> m = new HashMap<>();
        m.put("method_name", "/api/slow");
        m.put("max_cost_time", 3500);  // 只有 max_cost_time
        hc.add(m);
        data.put("high_cost_top15", hc);
        String md = svc.generateMarkdown(data);
        assertThat(md).contains("3500ms");
    }

    @Test
    void markdown_emptySectionsStillRenderHeaders() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", "2026-04-25");
        String md = svc.generateMarkdown(data);
        assertThat(md)
                .contains("### Control 错误统计")
                .contains("### 错误码 TOP5")
                .contains("### GW 方法报错 TOP5")
                .contains("### 顺风车高耗时接口 TOP5")
                .contains("*生成时间:");
    }

    private static Map<String, Object> codeEntry(int code, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put("error_code", code);
        m.put("count", count);
        return m;
    }
}
