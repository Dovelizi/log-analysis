package com.loganalysis.controller;

import com.loganalysis.service.DashboardService;
import com.loganalysis.service.DashboardService.DateRange;
import com.loganalysis.service.DashboardService.TableMissingException;
import com.loganalysis.service.InsertRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据概览 Dashboard
 * 对齐 routes/dashboard_routes.py 全部 15 个接口，前缀 /api/dashboard。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DashboardService dashboard;

    // ==================== 通用 ====================

    @GetMapping("/available-dates")
    public ResponseEntity<Map<String, Object>> availableDates() {
        return ResponseEntity.ok(dashboard.availableDates());
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(@RequestParam(value = "start_date", required = false) String s,
                                      @RequestParam(value = "end_date", required = false) String e) {
        DateRange dr = dashboard.parseDateRange(s, e);
        return ResponseEntity.ok(dashboard.overview(dr));
    }

    // ==================== Control Hitch ====================

    @GetMapping("/control-hitch/statistics")
    public ResponseEntity<?> controlHitchStats(
            @RequestParam(value = "start_date", required = false) String s,
            @RequestParam(value = "end_date", required = false) String e,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
            @RequestParam(value = "sort_field", required = false) String sortField,
            @RequestParam(value = "sort_order", defaultValue = "desc") String sortOrder) {
        DateRange dr = dashboard.parseDateRange(s, e);
        return runOrNotFound(() -> dashboard.hitchStatistics(
                "control_hitch_error_mothod", InsertRecordService.LOG_FROM_CONTROL_HITCH,
                dr, page, pageSize, sortField, sortOrder));
    }

    @GetMapping("/control-hitch/aggregation")
    public ResponseEntity<?> controlHitchAgg(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return runOrNotFound(() -> dashboard.hitchAggregation("control_hitch_error_mothod", page, pageSize));
    }

    // ==================== GW Hitch ====================

    @GetMapping("/gw-hitch/statistics")
    public ResponseEntity<?> gwHitchStats(
            @RequestParam(value = "start_date", required = false) String s,
            @RequestParam(value = "end_date", required = false) String e,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
            @RequestParam(value = "sort_field", required = false) String sortField,
            @RequestParam(value = "sort_order", defaultValue = "desc") String sortOrder) {
        DateRange dr = dashboard.parseDateRange(s, e);
        return runOrNotFound(() -> dashboard.hitchStatistics(
                "gw_hitch_error_mothod", InsertRecordService.LOG_FROM_GW_HITCH,
                dr, page, pageSize, sortField, sortOrder));
    }

    @GetMapping("/gw-hitch/aggregation")
    public ResponseEntity<?> gwHitchAgg(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return runOrNotFound(() -> dashboard.hitchAggregation("gw_hitch_error_mothod", page, pageSize));
    }

    // ==================== Cost Time ====================

    @GetMapping("/hitch-control-cost-time/statistics")
    public ResponseEntity<?> costTimeStats(
            @RequestParam(value = "start_date", required = false) String s,
            @RequestParam(value = "end_date", required = false) String e,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
            @RequestParam(value = "high_cost_page", defaultValue = "1") int hcPage,
            @RequestParam(value = "high_cost_page_size", defaultValue = "10") int hcPageSize) {
        DateRange dr = dashboard.parseDateRange(s, e);
        return runOrNotFound(() -> dashboard.costTimeStatistics(dr, page, pageSize, hcPage, hcPageSize));
    }

    // ==================== Supplier SP ====================

    @GetMapping("/hitch-supplier-error-sp/statistics")
    public ResponseEntity<?> spStats(
            @RequestParam(value = "start_date", required = false) String s,
            @RequestParam(value = "end_date", required = false) String e,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        DateRange dr = dashboard.parseDateRange(s, e);
        return runOrNotFound(() -> dashboard.supplierSpStatistics(dr, page, pageSize));
    }

    @GetMapping("/hitch-supplier-error-sp/aggregation")
    public ResponseEntity<?> spAgg(
            @RequestParam(value = "start_date", required = false) String s,
            @RequestParam(value = "end_date", required = false) String e,
            @RequestParam(value = "sp_id", required = false) Integer spId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
            @RequestParam(value = "sort_field", required = false) String sortField,
            @RequestParam(value = "sort_order", defaultValue = "desc") String sortOrder) {
        DateRange dr = dashboard.parseDateRange(s, e);
        return runOrNotFound(() -> dashboard.supplierAggregation(
                "hitch_supplier_error_sp", dr, spId, page, pageSize, sortField, sortOrder));
    }

    // ==================== Supplier Total ====================

    @GetMapping("/hitch-supplier-error-total/statistics")
    public ResponseEntity<?> totalStats(
            @RequestParam(value = "start_date", required = false) String s,
            @RequestParam(value = "end_date", required = false) String e,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        DateRange dr = dashboard.parseDateRange(s, e);
        return runOrNotFound(() -> dashboard.supplierTotalStatistics(dr, page, pageSize));
    }

    @GetMapping("/hitch-supplier-error-total/aggregation")
    public ResponseEntity<?> totalAgg(
            @RequestParam(value = "start_date", required = false) String s,
            @RequestParam(value = "end_date", required = false) String e,
            @RequestParam(value = "sp_id", required = false) Integer spId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
            @RequestParam(value = "sort_field", required = false) String sortField,
            @RequestParam(value = "sort_order", defaultValue = "desc") String sortOrder) {
        DateRange dr = dashboard.parseDateRange(s, e);
        return runOrNotFound(() -> dashboard.supplierAggregation(
                "hitch_supplier_error_total", dr, spId, page, pageSize, sortField, sortOrder));
    }

    // ==================== 通用表数据 ====================

    @GetMapping("/table/{tableName}/data")
    public ResponseEntity<?> tableData(@PathVariable("tableName") String name,
                                       @RequestParam(value = "limit", defaultValue = "100") int limit,
                                       @RequestParam(value = "offset", defaultValue = "0") int offset) {
        if (!DashboardService.ALLOWED_TABLES.contains(name)) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "不允许访问该表");
            return ResponseEntity.status(403).body(err);
        }
        return runOrNotFound(() -> dashboard.tableData(name, limit, offset));
    }

    // ==================== helpers ====================

    private ResponseEntity<?> runOrNotFound(SupplierX task) {
        try {
            return ResponseEntity.ok(task.get());
        } catch (TableMissingException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "表不存在");
            err.put("exists", false);
            return ResponseEntity.status(404).body(err);
        }
    }

    @FunctionalInterface
    private interface SupplierX {
        Object get();
    }
}
