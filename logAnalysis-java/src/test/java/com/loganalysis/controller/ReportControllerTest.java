package com.loganalysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.service.ReportPushConfigService;
import com.loganalysis.service.ReportPushService;
import com.loganalysis.service.ReportPushService.TriggerResult;
import com.loganalysis.service.ReportSummaryService;
import com.loganalysis.service.ScreenshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ReportPushConfigService pushConfigService;
    @MockBean ScreenshotService screenshotService;
    @MockBean ReportSummaryService summaryService;
    @MockBean ReportPushService pushService;

    private final ObjectMapper json = new ObjectMapper();

    // ============ push-configs CRUD ============

    @Test
    void list_returns200() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 1); item.put("name", "daily-notify");
        when(pushConfigService.listAll()).thenReturn(Collections.singletonList(item));
        mvc.perform(get("/api/report/push-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void create_happyPath() throws Exception {
        when(pushConfigService.create(anyMap())).thenReturn(7L);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "x"); body.put("push_type", "wecom");
        body.put("webhook_url", "https://ex/hook");
        body.put("push_mode", "daily");

        mvc.perform(post("/api/report/push-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void create_invalidPushMode_returns400() throws Exception {
        doThrow(new IllegalArgumentException("push_mode 必须是 daily/date/relative"))
                .when(pushConfigService).create(anyMap());
        Map<String, Object> body = new HashMap<>();
        body.put("name", "x"); body.put("push_type", "wecom");
        body.put("push_mode", "weird");
        mvc.perform(post("/api/report/push-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_notFound404() throws Exception {
        when(pushConfigService.findById(123L)).thenReturn(null);
        mvc.perform(put("/api/report/push-configs/123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_happyPath() throws Exception {
        when(pushConfigService.findById(3L)).thenReturn(Collections.singletonMap("id", 3));
        Map<String, Object> body = new HashMap<>();
        body.put("name", "new"); body.put("push_type", "wecom");
        body.put("push_mode", "daily");
        mvc.perform(put("/api/report/push-configs/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
        verify(pushConfigService).update(eq(3L), anyMap());
    }

    @Test
    void delete_returns200() throws Exception {
        when(pushConfigService.findById(5L)).thenReturn(Collections.singletonMap("id", 5));
        when(pushConfigService.delete(5L)).thenReturn(1);
        mvc.perform(delete("/api/report/push-configs/5"))
                .andExpect(status().isOk());
    }

    // ============ screenshot ============

    @Test
    void screenshot_happyPath() throws Exception {
        when(screenshotService.screenshotReportByDate("2026-04-25")).thenReturn("BASE64DATA");
        mvc.perform(get("/api/report/screenshot").param("date", "2026-04-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.image_base64").value("BASE64DATA"));
    }

    @Test
    void screenshot_failure_returns500() throws Exception {
        when(screenshotService.screenshotReportByDate(anyString()))
                .thenThrow(new IllegalStateException("Playwright 未安装浏览器"));
        mvc.perform(get("/api/report/screenshot").param("date", "2026-04-25"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ============ summary / weekly-new-errors ============

    @Test
    void summary_returns200() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", "2026-04-25");
        body.put("control_total_errors", 42);
        body.put("gw_method_top10", Collections.emptyList());
        when(summaryService.summary(anyString())).thenReturn(body);

        mvc.perform(get("/api/report/summary").param("date", "2026-04-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-04-25"))
                .andExpect(jsonPath("$.control_total_errors").value(42));
    }

    @Test
    void weeklyNewErrors_returns200() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("daily_new_errors", Collections.emptyList());
        when(summaryService.weeklyNewErrors(any())).thenReturn(body);

        mvc.perform(get("/api/report/weekly-new-errors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily_new_errors").isArray());
    }

    // ============ push ============

    @Test
    void push_missingConfigId_returns400() throws Exception {
        mvc.perform(post("/api/report/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请指定推送配置"));
    }

    @Test
    void push_success() throws Exception {
        when(pushService.trigger(eq(7L), any(), any()))
                .thenReturn(new TriggerResult(true, "推送成功（Markdown模式）", "ok", 88L, 200));

        mvc.perform(post("/api/report/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Collections.singletonMap("config_id", 7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.log_id").value(88))
                .andExpect(jsonPath("$.message").value("推送成功（Markdown模式）"));
    }

    @Test
    void push_configNotFound_returns404() throws Exception {
        when(pushService.trigger(eq(999L), any(), any()))
                .thenReturn(new TriggerResult(false, "推送配置不存在", null, 0L, 404));

        mvc.perform(post("/api/report/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Collections.singletonMap("config_id", 999))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("推送配置不存在"));
    }

    @Test
    void push_fail_returns500() throws Exception {
        when(pushService.trigger(eq(5L), any(), any()))
                .thenReturn(new TriggerResult(false, "推送失败: timeout", null, 10L, 500));

        mvc.perform(post("/api/report/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Collections.singletonMap("config_id", 5))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.log_id").value(10));
    }

    // ============ push-logs ============

    @Test
    void pushLogs_returns200() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", Collections.emptyList());
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("current_page", 1);
        pagination.put("total", 0L);
        pagination.put("total_pages", 1L);
        body.put("pagination", pagination);
        when(pushService.listLogs(1, 20)).thenReturn(body);

        mvc.perform(get("/api/report/push-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.pagination.total").value(0))
                .andExpect(jsonPath("$.pagination.current_page").value(1));
    }

    @Test
    void pushLogDetail_notFound404() throws Exception {
        when(pushService.getLogDetail(999L)).thenReturn(null);
        mvc.perform(get("/api/report/push-logs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("推送记录不存在"));
    }

    @Test
    void pushLogDetail_happyPath() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", 3);
        body.put("status", "success");
        when(pushService.getLogDetail(3L)).thenReturn(body);
        mvc.perform(get("/api/report/push-logs/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3));
    }

    // ============ export ============

    @Test
    void export_htmlFormat() throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", "2026-04-25");
        summary.put("control_total_errors", 10);
        when(summaryService.summary("2026-04-25")).thenReturn(summary);
        when(summaryService.generateReportHtml(anyMap()))
                .thenReturn("<!DOCTYPE html><html></html>");

        Map<String, Object> body = new HashMap<>();
        body.put("format", "html");
        body.put("date", "2026-04-25");

        mvc.perform(post("/api/report/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("html"))
                .andExpect(jsonPath("$.filename").value("report_2026-04-25.html"))
                .andExpect(jsonPath("$.content").value("<!DOCTYPE html><html></html>"));
    }

    @Test
    void export_jsonFormat() throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", "2026-04-25");
        summary.put("control_total_errors", 10);
        when(summaryService.summary("2026-04-25")).thenReturn(summary);

        Map<String, Object> body = new HashMap<>();
        body.put("format", "json");
        body.put("date", "2026-04-25");

        mvc.perform(post("/api/report/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("json"))
                .andExpect(jsonPath("$.filename").value("report_2026-04-25.json"))
                .andExpect(jsonPath("$.content.control_total_errors").value(10));
    }

    @Test
    void export_invalidFormat_returns400() throws Exception {
        when(summaryService.summary(any())).thenReturn(new LinkedHashMap<>());

        Map<String, Object> body = new HashMap<>();
        body.put("format", "pdf");

        mvc.perform(post("/api/report/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不支持的导出格式: pdf"));
    }
}
