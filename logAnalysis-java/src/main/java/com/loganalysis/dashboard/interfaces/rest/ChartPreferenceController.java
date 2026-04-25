package com.loganalysis.dashboard.interfaces.rest;

import com.loganalysis.dashboard.application.ChartPreferenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图表偏好配置接口。
 *
 * 路由：
 *   GET /api/chart-preferences            返回所有 global 配置（前端加载页面时调）
 *   PUT /api/chart-preferences/{chartId}  更新单个图表 global 配置
 *
 * 前端使用模式：
 *   1. 页面加载：GET /api/chart-preferences → 得到默认值
 *   2. 读 localStorage 覆盖层 → 合并成生效值
 *   3. 用户修改 → 更新 localStorage + 调 PUT 同步到后端
 */
@RestController
@RequestMapping("/api/chart-preferences")
public class ChartPreferenceController {

    @Autowired
    private ChartPreferenceService service;

    /** 返回所有 global 配置（Map&lt;chartId, settings&gt;） */
    @GetMapping
    public Map<String, Object> listAll() {
        Map<String, Map<String, Object>> data = service.listAllGlobalAsMap();
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("scope", "global");
        ret.put("preferences", data);
        return ret;
    }

    /**
     * 更新指定 chartId 的 global 配置。
     * 请求体为直接的 settings JSON，例如 {"granularity":"10m","chart_type":"line","top_n":20}。
     */
    @PutMapping("/{chartId}")
    public Map<String, Object> update(@PathVariable("chartId") String chartId,
                                      @RequestBody(required = false) Map<String, Object> settings) {
        if (settings == null) settings = Collections.emptyMap();
        int affected = service.upsertGlobal(chartId, settings);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("chart_id", chartId);
        ret.put("affected", affected);
        ret.put("settings", settings);
        return ret;
    }
}
