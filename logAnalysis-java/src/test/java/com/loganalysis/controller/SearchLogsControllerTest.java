package com.loganalysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.service.ClsQueryService;
import com.loganalysis.service.DataProcessorRouter;
import com.loganalysis.service.QueryConfigService;
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

@WebMvcTest(SearchLogsController.class)
class SearchLogsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ClsQueryService clsQueryService;
    @MockBean DataProcessorRouter processorRouter;
    @MockBean QueryConfigService queryConfigService;
    @MockBean JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void searchLogs_withConfigId_configNotFound_returns404() throws Exception {
        when(queryConfigService.findWithTopic(999L)).thenReturn(null);

        Map<String, Object> body = Collections.singletonMap("config_id", 999);
        mvc.perform(post("/api/search-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("查询配置不存在"));
    }

    @Test
    void searchLogs_customParams_missingRequired_returns400() throws Exception {
        // 无 config_id 且缺 credential_id/topic_id/query
        mvc.perform(post("/api/search-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少必要参数"));
    }

    @Test
    void searchLogs_configId_happyPath_dispatchesProcessor() throws Exception {
        // Given: config 存在
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("credential_id", 1);
        config.put("cls_topic_id", "topic-xyz");
        config.put("region", "ap-nanjing");
        config.put("query_statement", "* | select 1");
        config.put("time_range", 3600);
        config.put("limit_count", 100);
        config.put("sort_order", "desc");
        config.put("syntax_rule", 1);
        config.put("processor_type", "gw_hitch_error");
        config.put("target_table", "gw_hitch_error_mothod");
        when(queryConfigService.findWithTopic(5L)).thenReturn(config);

        // CLS 返回 2 条 Results
        Map<String, Object> clsResp = new LinkedHashMap<>();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Results", Arrays.asList(
                Collections.singletonMap("LogJson", "{\"a\":1}"),
                Collections.singletonMap("LogJson", "{\"b\":2}")));
        clsResp.put("Response", response);
        when(clsQueryService.searchLog(anyLong(), anyString(), anyString(),
                anyLong(), anyLong(), anyInt(), anyString(), anyInt(), any()))
                .thenReturn(clsResp);

        Map<String, Object> processResult = Collections.singletonMap("success", 2);
        when(processorRouter.dispatch(anyMap(), anyString(), anyString(),
                any(), any(), anyLong(), anyString())).thenReturn(processResult);

        mvc.perform(post("/api/search-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Collections.singletonMap("config_id", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._process_result.success").value(2))
                .andExpect(jsonPath("$._debug_params.processor_type").value("gw_hitch_error"));
    }

    @Test
    void searchLogs_clsError_noDispatch() throws Exception {
        // CLS 返回带 Error 的响应
        Map<String, Object> clsResp = new LinkedHashMap<>();
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> err = new HashMap<>();
        err.put("Code", "InvalidParameter");
        err.put("Message", "x");
        response.put("Error", err);
        clsResp.put("Response", response);
        when(clsQueryService.searchLog(anyLong(), anyString(), anyString(),
                anyLong(), anyLong(), anyInt(), anyString(), anyInt(), any()))
                .thenReturn(clsResp);

        Map<String, Object> body = new HashMap<>();
        body.put("credential_id", 1);
        body.put("topic_id", "t");
        body.put("query", "*");

        mvc.perform(post("/api/search-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Response.Error.Code").value("InvalidParameter"));

        // 不应触发 dispatch
        verify(processorRouter, never()).dispatch(anyMap(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testCls_missingParams_returns400() throws Exception {
        mvc.perform(post("/api/test-cls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logRecords_byConfigId() throws Exception {
        when(jdbc.queryForList(anyString(), eq(5L), eq(100), eq(0)))
                .thenReturn(Collections.singletonList(Collections.singletonMap("id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(5L))).thenReturn(1L);

        mvc.perform(get("/api/log-records").param("config_id", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records.length()").value(1));
    }

    @Test
    void statistics_returns200() throws Exception {
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM log_records"), eq(Long.class))).thenReturn(10L);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM query_configs"), eq(Long.class))).thenReturn(3L);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM log_topics"), eq(Long.class))).thenReturn(2L);
        when(jdbc.queryForList(anyString())).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_logs").value(10))
                .andExpect(jsonPath("$.total_configs").value(3))
                .andExpect(jsonPath("$.total_topics").value(2));
    }

    @Test
    void analysisResults_returns200() throws Exception {
        when(jdbc.queryForList(anyString())).thenReturn(Collections.emptyList());
        mvc.perform(get("/api/analysis-results"))
                .andExpect(status().isOk());
    }
}
