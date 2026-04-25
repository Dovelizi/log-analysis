package com.loganalysis.hitch.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.search.infrastructure.ClsQueryService;
import com.loganalysis.hitch.application.ControlHitchProcessor;
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

@WebMvcTest(ControlHitchController.class)
class ControlHitchControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ControlHitchProcessor processor;
    @MockBean ClsQueryService clsQueryService;
    @MockBean JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void data_returns200() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", 0L);
        body.put("data", Collections.emptyList());
        when(processor.getTableData(anyInt(), anyInt(), anyString(), anyString())).thenReturn(body);
        mvc.perform(get("/api/control-hitch/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void statistics_returns200() throws Exception {
        when(processor.getErrorStatistics())
                .thenReturn(Collections.singletonMap("statistics", Collections.emptyList()));
        mvc.perform(get("/api/control-hitch/statistics")).andExpect(status().isOk());
    }

    @Test
    void process_missingLogData_returns400() throws Exception {
        mvc.perform(post("/api/control-hitch/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少日志数据"));
    }

    @Test
    void process_happyPath() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", 1); result.put("success", 1);
        when(processor.processLogs(anyList(), isNull(), isNull())).thenReturn(result);

        Map<String, Object> body = Collections.singletonMap("log_data",
                Collections.singletonList(Collections.singletonMap("content", "x")));

        // 注意：control-hitch 的 process 响应格式与 gw 不同，直接返回 processLogs 的结果
        mvc.perform(post("/api/control-hitch/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(1));
        verify(processor).clearConfigCache();
    }

    @Test
    void collect_missingParams_returns400() throws Exception {
        mvc.perform(post("/api/control-hitch/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少credential_id或topic_id参数"));
    }

    @Test
    void schema_returns200_withErrorCodeAsVarchar() throws Exception {
        // control_hitch 的 error_code 是 VARCHAR(255)，与 gw_hitch 不同
        mvc.perform(get("/api/control-hitch/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table_name").value("control_hitch_error_mothod"))
                .andExpect(jsonPath("$.columns[2].name").value("error_code"))
                .andExpect(jsonPath("$.columns[2].type").value("VARCHAR(255)"))
                // 对齐 Python 的 field_mapping 字段说明
                .andExpect(jsonPath("$.field_mapping.method_name").value("content中method:后面的值"))
                .andExpect(jsonPath("$.field_mapping.error_code").exists());
    }

    @Test
    void processorTypes_returns200() throws Exception {
        mvc.perform(get("/api/control-hitch/processor-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types[0].value").value("control_hitch_error"));
    }

    @Test
    void clearCache_returns200() throws Exception {
        mvc.perform(post("/api/control-hitch/clear-cache"))
                .andExpect(status().isOk());
        verify(processor).clearConfigCache();
    }

    @Test
    void transformTest_happyPath() throws Exception {
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("method_name", "orderStatusUpdate");
        tr.put("error_code", "700000");
        when(processor.transformLog(anyMap(), isNull())).thenReturn(tr);

        Map<String, Object> body = Collections.singletonMap("log_data",
                Collections.singletonMap("content",
                        "method:orderStatusUpdate,reason:x BizException(code=700000, desc=请求过于频繁)"));

        mvc.perform(post("/api/control-hitch/transform-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.method_name").value("orderStatusUpdate"));
    }
}
