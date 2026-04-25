package com.loganalysis.hitch.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 网关顺风车业务错误方法聚合监控表 PO。
 *
 * 对应表：gw_hitch_error_mothod（DDL 见 src/main/resources/schema/gw_hitch_error_mothod.sql）。
 *
 * 列语义：
 *   - (method_name, error_code, error_message) 组成业务唯一键，用于当日聚合判重
 *   - count：单次聚合周期（Processor 一次调用）内的错误次数
 *   - total_count：同一键在当天累计的错误次数
 *   - create_time / update_time：MySQL 自动维护
 *
 * 注意：该表 error_code 是 INT 类型（区别于 control_hitch_error_mothod 的 VARCHAR）。
 */
@Data
@TableName("gw_hitch_error_mothod")
public class GwHitchErrorPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String methodName;

    private Integer errorCode;

    private String errorMessage;

    private String content;

    private Integer count;

    private Long totalCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
