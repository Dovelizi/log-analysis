package com.loganalysis.hitch.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 顺风车控制层方法耗时监控日志表 PO。
 *
 * 对应表：hitch_control_cost_time。
 *
 * 特殊性：该表每条日志独立 INSERT，不做聚合，因此无 count / total_count / update_time 字段。
 */
@Data
@TableName("hitch_control_cost_time")
public class HitchControlCostTimePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String methodName;

    private String content;

    /** 方法执行耗时（毫秒） */
    private Integer timeCost;

    /** 上游保持字符串类型（Python 版字段类型如此） */
    private String logTime;

    private LocalDateTime createTime;
}
