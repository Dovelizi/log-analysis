package com.loganalysis.hitch.infrastructure.writeback;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ClickHouse 双写补偿队列 PO（ch_writeback_queue）。
 *
 * 当 Processor 异步写 CH 失败时，把失败详情记到 MySQL 补偿表；
 * {@link ChWritebackRunner}（@Scheduled）周期性重放，成功则删除行。
 */
@Data
@TableName("ch_writeback_queue")
public class ChWritebackQueuePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** CH 目标表名，如 gw_hitch_error_mothod / hitch_control_cost_time */
    private String targetTable;

    /** insert / update */
    private String operation;

    /** MySQL 主键 id（CH 行与 MySQL 行 id 保持一致） */
    private Long targetId;

    /** 操作载荷 JSON（按表字段序列化） */
    private String payloadJson;

    private Integer retryCount;

    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime nextRetryAt;
}
