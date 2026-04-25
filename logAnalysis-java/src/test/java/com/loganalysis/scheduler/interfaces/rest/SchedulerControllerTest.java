package com.loganalysis.scheduler.interfaces.rest;

import com.loganalysis.scheduler.application.ScheduledQueryRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SchedulerController.class)
class SchedulerControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ScheduledQueryRunner runner;
    @MockBean JdbcTemplate jdbc;

    @Test
    void queryStatus_returns200() throws Exception {
        when(runner.isRunning()).thenReturn(true);
        when(runner.getLastExecution()).thenReturn(new ConcurrentHashMap<>());
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
        when(jdbc.queryForList(anyString())).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/scheduler/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.enabled_count").value(2));
    }

    @Test
    void pushStatus_returns200() throws Exception {
        when(runner.isRunning()).thenReturn(true);
        when(runner.getLastPushExecution()).thenReturn(new ConcurrentHashMap<>());
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString())).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/scheduler/push-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true));
    }

    @Test
    void trigger_notFound404() throws Exception {
        when(jdbc.queryForList(anyString(), eq(999L))).thenReturn(Collections.emptyList());
        mvc.perform(post("/api/scheduler/trigger/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("查询配置不存在"));
    }

    @Test
    void trigger_happyPath() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("name", "daily-cfg");
        when(jdbc.queryForList(anyString(), eq(5L))).thenReturn(Collections.singletonList(row));

        Map<Long, Long> lastExec = new ConcurrentHashMap<>();
        when(runner.getLastExecution()).thenReturn(lastExec);

        mvc.perform(post("/api/scheduler/trigger/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("查询 daily-cfg 已安排在下次 tick 触发"));

        // 验证 lastExecution[5] 被重置为 0
        assert lastExec.get(5L) != null && lastExec.get(5L) == 0L;
    }
}
