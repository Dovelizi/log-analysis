package com.loganalysis.hitch.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.search.infrastructure.ClsQueryService;
import com.loganalysis.hitch.application.GwHitchProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GwHitchController.class)
class GwHitchControllerTest {

    @Autowired MockMvc mvc;
    @MockBean GwHitchProcessor processor;
    @MockBean ClsQueryService clsQueryService;
    @MockBean JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void data_returns200() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", Arrays.asList("id", "method_name"));
        body.put("data", Collections.emptyList());
        body.put("total", 0L);
        when(processor.getTableData(anyInt(), anyInt(), anyString(), anyString())).thenReturn(body);

        mvc.perform(get("/api/gw-hitch/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void statistics_returns200() throws Exception {
        Map<String, Object> body = Collections.singletonMap("statistics", Collections.emptyList());
        when(processor.getErrorStatistics()).thenReturn(body);
        mvc.perform(get("/api/gw-hitch/statistics")).andExpect(status().isOk());
    }

    @Test
    void process_missingLogData_returns400() throws Exception {
        mvc.perform(post("/api/gw-hitch/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少日志数据"));
    }

    @Test
    void process_happyPath() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", 2); result.put("success", 2);
        when(processor.processLogs(anyList(), isNull(), isNull())).thenReturn(result);

        List<Map<String, Object>> logData = Arrays.asList(
                Collections.singletonMap("path", "POST /a"),
                Collections.singletonMap("path", "POST /b"));
        Map<String, Object> body = Collections.singletonMap("log_data", logData);

        mvc.perform(post("/api/gw-hitch/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("处理完成"))
                .andExpect(jsonPath("$.result.success").value(2));

        verify(processor).clearConfigCache();
    }

    @Test
    void collect_missingParams_returns400() throws Exception {
        mvc.perform(post("/api/gw-hitch/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少credential_id或topic_id参数"));
    }

    @Test
    void schema_returns200() throws Exception {
        mvc.perform(get("/api/gw-hitch/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table_name").value("gw_hitch_error_mothod"))
                .andExpect(jsonPath("$.columns.length()").value(9))
                // 对齐 Python 的 field_mapping 字段说明
                .andExpect(jsonPath("$.field_mapping.method_name").value("path (去掉前缀，从第一个/开始)"))
                .andExpect(jsonPath("$.field_mapping.error_code").exists());
    }

    @Test
    void processorTypes_returns200() throws Exception {
        mvc.perform(get("/api/gw-hitch/processor-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types[0].value").value("gw_hitch_error"));
    }

    @Test
    void clearCache_returns200() throws Exception {
        mvc.perform(post("/api/gw-hitch/clear-cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("缓存已清除"));
        verify(processor).clearConfigCache();
    }

    @Test
    void transformTest_happyPath() throws Exception {
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("method_name", "/api/x");
        tr.put("error_code", 500);
        when(processor.transformLog(anyMap(), isNull())).thenReturn(tr);

        Map<String, Object> log = new HashMap<>();
        log.put("path", "POST /api/x");
        log.put("response_body", "{\"errCode\":500}");
        Map<String, Object> body = Collections.singletonMap("log_data", log);

        mvc.perform(post("/api/gw-hitch/transform-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.method_name").value("/api/x"));
    }
}
