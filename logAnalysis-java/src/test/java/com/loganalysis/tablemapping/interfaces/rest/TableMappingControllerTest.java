package com.loganalysis.tablemapping.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.tablemapping.application.TableMappingService;
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

@WebMvcTest(TableMappingController.class)
class TableMappingControllerTest {

    @Autowired MockMvc mvc;
    @MockBean TableMappingService service;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void listAll_returns200() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 1); item.put("table_name", "gw_hitch_error_mothod");
        when(service.getAllMappings()).thenReturn(Collections.singletonList(item));

        mvc.perform(get("/api/table-mappings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void detail_notFoundReturns404() throws Exception {
        when(service.getMapping(999L)).thenReturn(null);
        mvc.perform(get("/api/table-mappings/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("映射配置不存在"));
    }

    @Test
    void create_missingParams_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("topic_id", 1);
        // 缺 table_name 和 field_config

        mvc.perform(post("/api/table-mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少必要参数"));
    }

    @Test
    void create_happyPath() throws Exception {
        when(service.createMapping(anyLong(), anyString(), anyList(), any(), any(),
                anyBoolean(), any())).thenReturn(42L);

        Map<String, Object> body = new HashMap<>();
        body.put("topic_id", 1);
        body.put("table_name", "my_table");
        body.put("field_config", Collections.singletonList(Collections.singletonMap("name", "col1")));

        mvc.perform(post("/api/table-mappings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mapping_id").value(42))
                .andExpect(jsonPath("$.message").value("创建成功"));
    }

    @Test
    void update_notFound404() throws Exception {
        when(service.getMapping(1L)).thenReturn(null);
        Map<String, Object> body = Collections.singletonMap("description", "upd");

        mvc.perform(put("/api/table-mappings/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_withDropTrue() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 5); m.put("table_name", "my_table");
        when(service.getMapping(5L)).thenReturn(m);

        mvc.perform(delete("/api/table-mappings/5").param("drop_table", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table_dropped").value(true));

        verify(service).deleteMapping(5L, true);
    }

    @Test
    void tableData_happyPath() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 1);
        m.put("table_name", "my_table");
        m.put("field_config", Arrays.asList(
                Collections.singletonMap("name", "col1"),
                Collections.singletonMap("name", "col2")));
        when(service.getMapping(1L)).thenReturn(m);

        Map<String, Object> tableData = new LinkedHashMap<>();
        tableData.put("columns", Arrays.asList("col1", "col2"));
        tableData.put("data", Collections.emptyList());
        tableData.put("total", 0L);
        when(service.getTableData(eq("my_table"), anyInt(), anyInt(), anyString(), anyString(), anyList()))
                .thenReturn(tableData);

        mvc.perform(get("/api/table-mappings/1/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(2));
    }

    @Test
    void fieldTypes_returns200() throws Exception {
        mvc.perform(get("/api/table-mappings/field-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types.length()").value(6));
    }

    @Test
    void filterOperators_returns200() throws Exception {
        mvc.perform(get("/api/table-mappings/filter-operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators.length()").value(15));
    }
}
