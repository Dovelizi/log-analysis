package com.loganalysis.dashboard.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图表偏好配置表 PO（chart_preferences）。
 *
 * 业务唯一键：(scope_type, scope_id, chart_id)
 *
 * 数据模型（详见 schema/chart_preferences.sql）：
 *   scope_type = 'global'：全站默认配置，scope_id 固定 '*'
 *   scope_type = 'user'：用户级 override，scope_id 为用户 ID（预留字段，当前系统无登录）
 *
 * chart_id 枚举：
 *   '__global__'       全局外观（theme / legend_position）
 *   'time_chart'       错误趋势折线图
 *   'topic_chart'      主题分布图
 *   'dashboard_chart'  Dashboard 动态图表
 *
 * settings_json 是 JSON 字符串，Service 层按需解析，保持 schema 演进灵活性。
 */
@Data
@TableName("chart_preferences")
public class ChartPreferencePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String scopeType;

    private String scopeId;

    private String chartId;

    /** 配置 JSON 字符串，前端按 key 取值，未知 key 前端用硬编码默认 */
    private String settingsJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
