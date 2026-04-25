package com.loganalysis.credential.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.credential.application.CredentialService;
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

/**
 * CredentialController 冒烟测试：只启动 Web 层，Service 全部 Mock。
 */
@WebMvcTest(CredentialController.class)
class CredentialControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CredentialService credentialService;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void list_returns200WithArray() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", 1);
        item.put("name", "cred-a");
        item.put("secret_id_masked", "abcd****wxyz");
        when(credentialService.listMasked()).thenReturn(Collections.singletonList(item));

        mvc.perform(get("/api/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("cred-a"));
    }

    @Test
    void detail_notFound404() throws Exception {
        when(credentialService.detail(999L)).thenReturn(null);
        mvc.perform(get("/api/credentials/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("凭证不存在"));
    }

    @Test
    void create_returns201WithId() throws Exception {
        when(credentialService.create(anyString(), anyString(), anyString())).thenReturn(42L);

        Map<String, Object> body = new HashMap<>();
        body.put("name", "new-cred");
        body.put("secret_id", "AKID_xxx");
        body.put("secret_key", "secret_xxx");

        mvc.perform(post("/api/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.message").value("创建成功"));

        verify(credentialService).create("new-cred", "AKID_xxx", "secret_xxx");
    }

    @Test
    void create_missingParam_returns400ViaGlobalHandler() throws Exception {
        when(credentialService.create(isNull(), any(), any()))
                .thenThrow(new IllegalArgumentException("缺少必要参数"));

        Map<String, Object> body = new HashMap<>();
        body.put("secret_id", "x");
        body.put("secret_key", "y");

        mvc.perform(post("/api/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("缺少必要参数"));
    }

    @Test
    void update_returns200() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "n");

        mvc.perform(put("/api/credentials/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("更新成功"));

        verify(credentialService).update(eq(7L), eq("n"), isNull(), isNull());
    }

    @Test
    void delete_returns200() throws Exception {
        when(credentialService.delete(3L)).thenReturn(1);
        mvc.perform(delete("/api/credentials/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));
    }
}
