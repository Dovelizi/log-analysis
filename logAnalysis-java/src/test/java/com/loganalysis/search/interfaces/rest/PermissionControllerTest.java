package com.loganalysis.search.interfaces.rest;

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

@WebMvcTest(PermissionController.class)
class PermissionControllerTest {

    @Autowired MockMvc mvc;
    @MockBean CredentialService credentialService;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void analyze_missingMessage_returns400() throws Exception {
        mvc.perform(post("/api/permission/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请提供错误信息"));
    }

    @Test
    void analyze_happyPath() throws Exception {
        Map<String, Object> body = Collections.singletonMap("error_message",
                "operation (cls:SearchLog) no permission on resource (qcs::cls:ap-nanjing:uin/123:topic/t1)");

        mvc.perform(post("/api/permission/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parsed_error.operation").value("cls:SearchLog"))
                .andExpect(jsonPath("$.parsed_error.topic_id").value("t1"))
                .andExpect(jsonPath("$.iam_policy.version").value("2.0"))
                .andExpect(jsonPath("$.fix_steps.length()").isNumber());
    }

    @Test
    void verify_missingCredentialId_returns400() throws Exception {
        mvc.perform(post("/api/permission/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请选择凭证"));
    }

    @Test
    void verify_credentialNotFound_returns404() throws Exception {
        when(credentialService.loadDecrypted(999L)).thenReturn(null);

        Map<String, Object> body = Collections.singletonMap("credential_id", 999);

        mvc.perform(post("/api/permission/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("凭证不存在"));
    }
}
