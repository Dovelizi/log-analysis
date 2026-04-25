package com.loganalysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.service.QueryConfigService;
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

@WebMvcTest(QueryConfigController.class)
class QueryConfigControllerTest {

    @Autowired MockMvc mvc;

    @MockBean QueryConfigService queryConfigService;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void list_returns200() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 1);
        item.put("name", "cfg-1");
        when(queryConfigService.listAll()).thenReturn(Collections.singletonList(item));

        mvc.perform(get("/api/query-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("cfg-1"));
    }

    @Test
    void create_returns201() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "new-cfg");
        body.put("topic_id", 1);
        body.put("query_statement", "* | select count(*) as c");

        mvc.perform(post("/api/query-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("创建成功"));
    }

    @Test
    void create_missingParamReturns400() throws Exception {
        doThrow(new IllegalArgumentException("缺少必要参数"))
                .when(queryConfigService).create(anyMap());

        Map<String, Object> body = new HashMap<>();
        body.put("name", "x"); // 缺 topic_id

        mvc.perform(post("/api/query-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少必要参数"));
    }

    @Test
    void update_returns200() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "updated");

        mvc.perform(put("/api/query-configs/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());

        verify(queryConfigService).update(eq(3L), anyMap());
    }

    @Test
    void delete_returns200() throws Exception {
        mvc.perform(delete("/api/query-configs/4"))
                .andExpect(status().isOk());
        verify(queryConfigService).delete(4L);
    }
}
