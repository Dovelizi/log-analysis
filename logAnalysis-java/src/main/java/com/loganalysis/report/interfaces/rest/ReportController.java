package com.loganalysis.report.interfaces.rest;

import com.loganalysis.report.application.ReportPushConfigService;
import com.loganalysis.report.application.ReportPushService;
import com.loganalysis.report.application.ReportPushService.TriggerResult;
import com.loganalysis.report.application.ReportSummaryService;
import com.loganalysis.report.infrastructure.ScreenshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 报表 Controller，对齐 routes/report_routes.py 全部公开接口。
 *
 * 已实现:
 *   - GET    /api/report/summary
 *   - GET    /api/report/weekly-new-errors
 *   - GET    /api/report/push-configs
 *   - POST   /api/report/push-configs
 *   - PUT    /api/report/push-configs/{id}
 *   - DELETE /api/report/push-configs/{id}
 *   - POST   /api/report/push
 *   - GET    /api/report/push-logs
 *   - GET    /api/report/push-logs/{id}
 *   - GET    /api/report/screenshot
 *
 * 未实现（后续补齐）:
 *   - POST /api/report/export   HTML 导出
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    @Autowired
    private ReportPushConfigService pushConfigService;

    @Autowired
    private ScreenshotService screenshotService;

    @Autowired
    private ReportSummaryService summaryService;

    @Autowired
    private ReportPushService pushService;

    // ============ 报表汇总 ============

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(value = "date", required = false) String date) {
        return ResponseEntity.ok(summaryService.summary(date));
    }

    @GetMapping("/weekly-new-errors")
    public ResponseEntity<?> weeklyNewErrors(@RequestParam(value = "end_date", required = false) String endDate) {
        return ResponseEntity.ok(summaryService.weeklyNewErrors(endDate));
    }

    // ============ push-configs CRUD ============

    @GetMapping("/push-configs")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(pushConfigService.listAll());
    }

    @PostMapping("/push-configs")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        long id = pushConfigService.create(body);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("message", "创建成功");
        ok.put("id", id);
        return ResponseEntity.status(201).body(ok);
    }

    @PutMapping("/push-configs/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (pushConfigService.findById(id) == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "推送配置不存在");
            return ResponseEntity.status(404).body(err);
        }
        pushConfigService.update(id, body);
        return ResponseEntity.ok(Collections.singletonMap("message", "更新成功"));
    }

    @DeleteMapping("/push-configs/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (pushConfigService.findById(id) == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "推送配置不存在");
            return ResponseEntity.status(404).body(err);
        }
        pushConfigService.delete(id);
        return ResponseEntity.ok(Collections.singletonMap("message", "删除成功"));
    }

    // ============ 推送触发 ============

    @PostMapping("/push")
    public ResponseEntity<?> push(@RequestBody Map<String, Object> body) {
        Object configIdObj = body.get("config_id");
        if (configIdObj == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "请指定推送配置");
            return ResponseEntity.badRequest().body(err);
        }
        long configId;
        try {
            configId = configIdObj instanceof Number
                    ? ((Number) configIdObj).longValue()
                    : Long.parseLong(String.valueOf(configIdObj));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "config_id 格式错误"));
        }
        String date = (String) body.get("date");
        String imageBase64 = (String) body.get("image_base64");

        TriggerResult result = pushService.trigger(configId, date, imageBase64);

        Map<String, Object> ret = new LinkedHashMap<>();
        if (result.success) {
            ret.put("message", result.message);
            ret.put("result", result.responseText);
            ret.put("log_id", result.logId);
            return ResponseEntity.ok(ret);
        } else {
            ret.put("error", result.message);
            if (result.logId > 0) ret.put("log_id", result.logId);
            return ResponseEntity.status(result.httpStatus).body(ret);
        }
    }

    // ============ push-logs ============

    @GetMapping("/push-logs")
    public ResponseEntity<?> pushLogs(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(pushService.listLogs(page, pageSize));
    }

    @GetMapping("/push-logs/{id}")
    public ResponseEntity<?> pushLogDetail(@PathVariable long id) {
        Map<String, Object> detail = pushService.getLogDetail(id);
        if (detail == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "推送记录不存在");
            return ResponseEntity.status(404).body(err);
        }
        return ResponseEntity.ok(detail);
    }

    // ============ 截图 ============

    @GetMapping("/screenshot")
    public ResponseEntity<?> screenshot(@RequestParam(value = "date", required = false) String date) {
        String d = (date == null || date.isEmpty()) ? LocalDate.now().toString() : date;
        try {
            String base64 = screenshotService.screenshotReportByDate(d);
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("success", true);
            ret.put("date", d);
            ret.put("image_base64", base64);
            return ResponseEntity.ok(ret);
        } catch (Exception e) {
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("success", false);
            ret.put("date", d);
            ret.put("error", e.getMessage());
            return ResponseEntity.status(500).body(ret);
        }
    }

    // ============ 导出 ============

    @PostMapping("/export")
    public ResponseEntity<?> export(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> data = body == null ? new HashMap<>() : body;
        String format = strDefault(data.get("format"), "html");
        String date = (String) data.get("date");
        String targetDate = (date == null || date.isEmpty()) ? LocalDate.now().toString() : date;

        Map<String, Object> reportData = summaryService.summary(targetDate);

        if ("html".equals(format)) {
            String html = summaryService.generateReportHtml(reportData);
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("format", "html");
            ret.put("content", html);
            ret.put("filename", "report_" + targetDate + ".html");
            return ResponseEntity.ok(ret);
        } else if ("json".equals(format)) {
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("format", "json");
            ret.put("content", reportData);
            ret.put("filename", "report_" + targetDate + ".json");
            return ResponseEntity.ok(ret);
        } else {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "不支持的导出格式: " + format);
            return ResponseEntity.badRequest().body(err);
        }
    }

    // ============ helpers ============

    private static String strDefault(Object v, String def) {
        if (v == null) return def;
        String s = v.toString();
        return s.isEmpty() ? def : s;
    }
}
