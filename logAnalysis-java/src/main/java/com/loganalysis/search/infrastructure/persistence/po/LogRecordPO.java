package com.loganalysis.search.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Hitch 错误日志新增记录表 PO（hitch_error_log_insert_record）。
 *
 * 用于 {@link com.loganalysis.search.infrastructure.InsertRecordService}，把 5 张专用表
 * （control_hitch / gw_hitch / supplier_sp / supplier_total / cost_time）的入库动作
 * 记录到此统一表，方便后续审计和报表。
 *
 * 注意：该表不同于 00_core_tables.sql 中的 log_records（后者是 CLS 原始日志落盘）。
 */
@Data
@TableName("hitch_error_log_insert_record")
public class LogRecordPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 1=control 2=gw 3=supplier_sp 4=supplier_total 5=cost_time */
    private Integer logFrom;

    /** 供应商 ID，log_from=3/4 时有值 */
    private Integer spId;

    private String methodName;

    private String content;

    private Integer count;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
