package com.loganalysis.controller;

import com.loganalysis.service.CredentialService;
import com.loganalysis.util.ClsPermissionAnalyzer;
import com.tencentcloudapi.cls.v20201016.ClsClient;
import com.tencentcloudapi.cls.v20201016.models.DescribeTopicsRequest;
import com.tencentcloudapi.cls.v20201016.models.SearchLogRequest;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 权限检查 Controller，对齐 app.py 的 /api/permission/* 接口。
 */
@RestController
@RequestMapping("/api/permission")
public class PermissionController {

    @Autowired
    private CredentialService credentialService;

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, Object> body) {
        String errorMessage = body.get("error_message") == null ? "" : String.valueOf(body.get("error_message"));
        if (errorMessage.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "请提供错误信息"));
        }
        Map<String, Object> parsed = ClsPermissionAnalyzer.parseErrorMessage(errorMessage);
        List<Map<String, Object>> suggestions = ClsPermissionAnalyzer.generateFixSuggestions(parsed);
        Map<String, Object> policy = ClsPermissionAnalyzer.generateIamPolicy(parsed);
        List<Map<String, Object>> steps = ClsPermissionAnalyzer.generateFixSteps(parsed, suggestions);

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("parsed_error", parsed);
        ret.put("suggestions", suggestions);
        ret.put("iam_policy", policy);
        ret.put("fix_steps", steps);
        return ResponseEntity.ok(ret);
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String, Object> body) {
        Object credId = body.get("credential_id");
        String topicId = body.get("topic_id") == null ? null : String.valueOf(body.get("topic_id"));
        String region = body.get("region") == null ? "ap-guangzhou" : String.valueOf(body.get("region"));
        if (credId == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "请选择凭证"));
        }

        long credentialId;
        try {
            credentialId = credId instanceof Number ? ((Number) credId).longValue() : Long.parseLong(String.valueOf(credId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "credential_id 格式错误"));
        }

        Map<String, Object> cred = credentialService.loadDecrypted(credentialId);
        if (cred == null) {
            return ResponseEntity.status(404).body(Collections.singletonMap("error", "凭证不存在"));
        }
        String secretId = String.valueOf(cred.get("secret_id_plain"));
        String secretKey = String.valueOf(cred.get("secret_key_plain"));

        List<Map<String, Object>> results = new ArrayList<>();
        ClsClient client = null;
        try {
            Credential credObj = new Credential(secretId, secretKey);
            HttpProfile hp = new HttpProfile();
            hp.setEndpoint("cls.internal.tencentcloudapi.com");
            ClientProfile cp = new ClientProfile();
            cp.setHttpProfile(hp);
            client = new ClsClient(credObj, region, cp);

            DescribeTopicsRequest req = new DescribeTopicsRequest();
            req.setOffset(0L);
            req.setLimit(1L);
            client.DescribeTopics(req);
            results.add(result("cls:DescribeTopics", "查询日志主题", "success", "权限正常", null));
        } catch (TencentCloudSDKException e) {
            results.add(result("cls:DescribeTopics", "查询日志主题", "failed", e.getMessage(), null));
        } catch (Exception e) {
            results.add(result("cls:DescribeTopics", "查询日志主题", "error", e.getMessage(), null));
        }

        if (topicId != null && !topicId.isEmpty() && client != null) {
            try {
                long now = System.currentTimeMillis();
                SearchLogRequest req = new SearchLogRequest();
                req.setTopicId(topicId);
                req.setFrom(now - 60_000L);
                req.setTo(now);
                req.setQuery("*");
                req.setLimit(1L);
                client.SearchLog(req);
                results.add(result("cls:SearchLog", "搜索日志", "success", "权限正常", topicId));
            } catch (TencentCloudSDKException e) {
                results.add(result("cls:SearchLog", "搜索日志", "failed", e.getMessage(), topicId));
            } catch (Exception e) {
                results.add(result("cls:SearchLog", "搜索日志", "error", e.getMessage(), topicId));
            }
        }

        int success = 0, failed = 0, error = 0;
        for (Map<String, Object> r : results) {
            switch (String.valueOf(r.get("status"))) {
                case "success": success++; break;
                case "failed": failed++; break;
                case "error": error++; break;
                default:
            }
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("results", results);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", results.size());
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("error", error);
        summary.put("all_passed", failed == 0 && error == 0);
        ret.put("summary", summary);
        return ResponseEntity.ok(ret);
    }

    private static Map<String, Object> result(String action, String desc, String status, String msg, String topicId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("description", desc);
        m.put("status", status);
        m.put("message", msg);
        if (topicId != null) m.put("topic_id", topicId);
        return m;
    }
}
