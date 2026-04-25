package com.loganalysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.service.TopicService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TopicController.class)
class TopicControllerTest {

    @Autowired MockMvc mvc;

    @MockBean TopicService topicService;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void list_returns200() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 1);
        item.put("topic_name", "t1");
        when(topicService.listAll()).thenReturn(Collections.singletonList(item));

        mvc.perform(get("/api/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].topic_name").value("t1"));
    }

    @Test
    void create_returns201() throws Exception {
        when(topicService.create(anyMap())).thenReturn(1L);

        Map<String, Object> body = new HashMap<>();
        body.put("credential_id", 1);
        body.put("topic_id", "topic-xyz");
        body.put("region", "ap-nanjing");

        mvc.perform(post("/api/topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("创建成功"));
    }

    @Test
    void update_returns200() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("region", "ap-beijing");

        mvc.perform(put("/api/topics/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());

        verify(topicService).update(eq(5L), anyMap());
    }

    @Test
    void delete_returns200() throws Exception {
        mvc.perform(delete("/api/topics/9"))
                .andExpect(status().isOk());
        verify(topicService).delete(9L);
    }
}
