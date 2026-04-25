package com.loganalysis.dashboard.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.dashboard.infrastructure.persistence.po.ChartPreferencePO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * chart_preferences Mapper。
 *
 * 本表数据量极小（与图表数量同级，~10 行），不需要分页/复杂查询；
 * BaseMapper + 2 个定制方法即可覆盖 ChartPreferenceService 全部需求。
 */
public interface ChartPreferenceMapper extends BaseMapper<ChartPreferencePO> {

    /** 按 scope_type='global' 拉取所有全局配置（一次性返回 all chart_id） */
    @Select("SELECT * FROM chart_preferences WHERE scope_type = 'global' AND scope_id = '*'")
    List<ChartPreferencePO> listAllGlobal();

    /** 按业务键精确查找（返回 null 表示尚未配置） */
    @Select("SELECT * FROM chart_preferences " +
            "WHERE scope_type = #{scopeType} " +
            "  AND scope_id   = #{scopeId} " +
            "  AND chart_id   = #{chartId} LIMIT 1")
    ChartPreferencePO findByKey(@Param("scopeType") String scopeType,
                                @Param("scopeId") String scopeId,
                                @Param("chartId") String chartId);

    /**
     * 按业务键 UPSERT settings_json：存在则覆盖 settings_json 并更新 update_time。
     *
     * 使用 INSERT ... ON DUPLICATE KEY UPDATE，依赖 UNIQUE KEY uk_scope_chart。
     */
    @Update("INSERT INTO chart_preferences (scope_type, scope_id, chart_id, settings_json) " +
            "VALUES (#{scopeType}, #{scopeId}, #{chartId}, #{settingsJson}) " +
            "ON DUPLICATE KEY UPDATE " +
            "  settings_json = VALUES(settings_json), " +
            "  update_time   = CURRENT_TIMESTAMP")
    int upsert(@Param("scopeType") String scopeType,
               @Param("scopeId") String scopeId,
               @Param("chartId") String chartId,
               @Param("settingsJson") String settingsJson);
}
