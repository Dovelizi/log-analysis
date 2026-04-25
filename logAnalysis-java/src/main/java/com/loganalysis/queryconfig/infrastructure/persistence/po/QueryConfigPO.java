package com.loganalysis.queryconfig.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 查询模板表 PO（query_configs）。
 *
 * transform_config / filter_config 在 DB 是 JSON 字符串，Java 侧用 String 存；
 * Service 层在返回给上游前把字符串解析为 Map。
 */
@Data
@TableName("query_configs")
public class QueryConfigPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long topicId;

    private String queryStatement;

    private Integer timeRange;

    private Integer limitCount;

    private String sortOrder;

    private Integer syntaxRule;

    private String processorType;

    private String targetTable;

    /** 字段转换规则 JSON，由 Service 层解析为 Map */
    private String transformConfig;

    /** 入库条件配置 JSON，由 Service 层解析为 Map */
    private String filterConfig;

    private Integer scheduleEnabled;

    private Integer scheduleInterval;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
