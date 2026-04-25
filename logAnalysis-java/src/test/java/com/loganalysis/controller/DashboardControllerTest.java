package com.loganalysis.controller;

import com.loganalysis.service.DashboardService;
import com.loganalysis.service.DashboardService.DateRange;
import com.loganalysis.service.DashboardService.TableMissingException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired MockMvc mvc;

    @MockBean DashboardService dashboard;

    @Test
    void availableDates_returns200() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("today", "2026-04-25");
        body.put("dates", Arrays.asList("2026-04-25", "2026-04-24"));
        when(dashboard.availableDates()).thenReturn(body);

        mvc.perform(get("/api/dashboard/available-dates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value("2026-04-25"))
                .andExpect(jsonPath("$.dates.length()").value(2));
    }

    @Test
    void overview_happyPath() throws Exception {
        DateRange dr = new DateRange("2026-04-20", "2026-04-25",
                "2026-04-20 00:00:00", "2026-04-25 23:59:59");
        when(dashboard.parseDateRange("2026-04-20", "2026-04-25")).thenReturn(dr);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start_date", "2026-04-20");
        body.put("end_date", "2026-04-25");
        when(dashboard.overview(dr)).thenReturn(body);

        mvc.perform(get("/api/dashboard/overview")
                        .param("start_date", "2026-04-20")
                        .param("end_date", "2026-04-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start_date").value("2026-04-20"));
    }

    @Test
    void overview_invalidDateReturns400() throws Exception {
        when(dashboard.parseDateRange("bad", "bad"))
                .thenThrow(new IllegalArgumentException("日期格式错误，请使用YYYY-MM-DD格式"));

        mvc.perform(get("/api/dashboard/overview")
                        .param("start_date", "bad")
                        .param("end_date", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("日期格式错误，请使用YYYY-MM-DD格式"));
    }

    @Test
    void controlHitchStats_tableMissingReturns404() throws Exception {
        DateRange dr = new DateRange("2026-04-25", "2026-04-25",
                "2026-04-25 00:00:00", "2026-04-25 23:59:59");
        when(dashboard.parseDateRange(isNull(), isNull())).thenReturn(dr);
        when(dashboard.hitchStatistics(
                eq("control_hitch_error_mothod"), eq(1),
                any(DateRange.class), anyInt(), anyInt(), any(), anyString()))
                .thenThrow(new TableMissingException("control_hitch_error_mothod"));

        mvc.perform(get("/api/dashboard/control-hitch/statistics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.error").value("表不存在"));
    }

    @Test
    void tableData_forbiddenForUnknownTable() throws Exception {
        mvc.perform(get("/api/dashboard/table/something_evil/data"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("不允许访问该表"));
    }

    @Test
    void tableData_returnsResult() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", 3L);
        body.put("limit", 100);
        body.put("offset", 0);
        body.put("records", Collections.emptyList());
        when(dashboard.tableData("control_hitch_error_mothod", 100, 0)).thenReturn(body);

        mvc.perform(get("/api/dashboard/table/control_hitch_error_mothod/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void gwHitchAgg_returnsResult() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exists", true);
        body.put("aggregation", Collections.emptyList());
        body.put("page", 1);
        body.put("page_size", 10);
        body.put("total_count", 0L);
        body.put("total_pages", 1L);
        when(dashboard.hitchAggregation("gw_hitch_error_mothod", 1, 10)).thenReturn(body);

        mvc.perform(get("/api/dashboard/gw-hitch/aggregation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));
    }
}
