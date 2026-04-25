package com.loganalysis.dashboard.application;

import com.loganalysis.common.util.JsonUtil;
import com.loganalysis.dashboard.infrastructure.persistence.mapper.ChartPreferenceMapper;
import com.loganalysis.dashboard.infrastructure.persistence.po.ChartPreferencePO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图表偏好配置服务。
 *
 * 设计：
 *   - 当前系统无登录，所有用户共享 global 作用域；user 作用域预留字段
 *   - 前端每次加载页面拉一次 {@link #listAllGlobalAsMap()}，之后自行合并 localStorage
 *   - 前端用户在"顶部偏好下拉/图表齿轮"修改时，调 {@link #upsertGlobal}
 *     （这意味着当前任何一个用户的修改都会影响所有人，符合"全局配置"语义）
 *
 * 字段约定见 schema/chart_preferences.sql 表头注释。
 */
@Service
public class ChartPreferenceService {

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_ID_ALL = "*";

    @Autowired
    private ChartPreferenceMapper mapper;

    /**
     * 返回所有 global 配置，key 为 chart_id，value 为解析后的 settings map。
     * 找不到时返回空 map（前端自己用硬编码默认兜底）。
     */
    public Map<String, Map<String, Object>> listAllGlobalAsMap() {
        List<ChartPreferencePO> rows = mapper.listAllGlobal();
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (ChartPreferencePO po : rows) {
            Map<String, Object> parsed = JsonUtil.toMap(po.getSettingsJson());
            result.put(po.getChartId(), parsed != null ? parsed : new LinkedHashMap<>());
        }
        return result;
    }

    /**
     * 更新（UPSERT）指定 chart_id 的 global 配置。
     * @param chartId   图表标识，枚举见 schema 注释
     * @param settings  前端传的配置 Map，会被序列化成 JSON 存储
     * @return 受影响行数（insert=1，update=2，MySQL ON DUPLICATE KEY 行为）
     */
    public int upsertGlobal(String chartId, Map<String, Object> settings) {
        if (chartId == null || chartId.isEmpty()) {
            throw new IllegalArgumentException("chart_id 不能为空");
        }
        if (settings == null) {
            settings = new LinkedHashMap<>();
        }
        String json = JsonUtil.toJson(settings);
        return mapper.upsert(SCOPE_GLOBAL, SCOPE_ID_ALL, chartId, json);
    }
}
