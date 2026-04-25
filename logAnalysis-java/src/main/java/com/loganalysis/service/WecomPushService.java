package com.loganalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 企业微信机器人推送服务。
 * 对齐 Python report_routes.py 中的 generate_wecom_markdown + push_to_wecom。
 */
@Service
public class WecomPushService {

    private static final Logger log = LoggerFactory.getLogger(WecomPushService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 推送结果 */
    public static class PushResult {
        public final boolean success;
        public final String responseText;
        public final String errorMessage;
        public PushResult(boolean success, String responseText, String errorMessage) {
            this.success = success;
            this.responseText = responseText;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * 推送到企业微信 webhook。image_base64 有值时走图片模式，否则降级为 Markdown。
     */
    public PushResult pushToWecom(String webhookUrl, Map<String, Object> reportData, String imageBase64) {
        try {
            Map<String, Object> payload;
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
                String md5 = md5Hex(imageBytes);
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("base64", imageBase64);
                image.put("md5", md5);
                payload = new LinkedHashMap<>();
                payload.put("msgtype", "image");
                payload.put("image", image);
            } else {
                String content = generateMarkdown(reportData);
                Map<String, Object> markdown = Collections.singletonMap("content", content);
                payload = new LinkedHashMap<>();
                payload.put("msgtype", "markdown");
                payload.put("markdown", markdown);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(payload), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(webhookUrl), HttpMethod.POST, entity, String.class);

            boolean ok = response.getStatusCode() == HttpStatus.OK;
            return new PushResult(ok, response.getBody(), ok ? null : "status=" + response.getStatusCode());
        } catch (Exception e) {
            log.warn("推送企微失败: {}", e.getMessage());
            return new PushResult(false, null, e.getMessage());
        }
    }

    /** 生成企微 Markdown（对齐 Python generate_wecom_markdown） */
    String generateMarkdown(Map<String, Object> reportData) {
        String dateStr = str(reportData.get("date"), LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        StringBuilder sb = new StringBuilder();
        sb.append("## 📊 日报汇总 - ").append(dateStr).append("\n\n");
        sb.append("### Control 错误统计\n");
        Object totalErrors = reportData.get("control_total_errors");
        if (totalErrors == null) totalErrors = 0;
        sb.append("> 累计错误数: **").append(totalErrors).append("**\n\n");

        sb.append("### 错误码 TOP5\n");
        List<?> codes = listOrEmpty(reportData.get("control_error_code_top10"));
        int i = 1;
        for (Object o : limit(codes, 5)) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                Object code = m.get("error_code");
                Object count = m.get("count");
                sb.append(i++).append(". `").append(code == null ? "-" : code)
                        .append("` - ").append(count == null ? 0 : count).append("次\n");
            }
        }

        sb.append("\n### GW 方法报错 TOP5\n");
        List<?> gwMethods = listOrEmpty(reportData.get("gw_method_top10"));
        i = 1;
        for (Object o : limit(gwMethods, 5)) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                String name = str(m.get("method_name"), "-");
                if (name.length() > 30) name = name.substring(0, 30) + "...";
                Object count = m.get("count");
                sb.append(i++).append(". `").append(name).append("` - ")
                        .append(count == null ? 0 : count).append("次\n");
            }
        }

        sb.append("\n### 顺风车高耗时接口 TOP5\n");
        List<?> hc = listOrEmpty(reportData.get("high_cost_top15"));
        i = 1;
        for (Object o : limit(hc, 5)) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                String name = str(m.get("method_name"), "-");
                if (name.length() > 30) name = name.substring(0, 30) + "...";
                Object max = m.get("max_cost");
                if (max == null) max = m.get("max_cost_time");
                if (max == null) max = 0;
                sb.append(i++).append(". `").append(name).append("` - ").append(max).append("ms\n");
            }
        }

        sb.append("\n---\n*生成时间: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("*\n");
        return sb.toString();
    }

    // ============================== helpers ==============================

    private static String md5Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("md5 error", e);
        }
    }

    private static String str(Object v, String def) {
        if (v == null) return def;
        String s = v.toString();
        return s.isEmpty() ? def : s;
    }

    private static List<?> listOrEmpty(Object v) {
        return v instanceof List ? (List<?>) v : Collections.emptyList();
    }

    private static List<?> limit(List<?> list, int n) {
        return list.size() <= n ? list : list.subList(0, n);
    }
}
