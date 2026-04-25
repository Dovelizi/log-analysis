package com.loganalysis.hitch.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 顺风车控制层错误方法聚合监控表 PO。
 *
 * 对应表：control_hitch_error_mothod。
 *
 * 与 {@link GwHitchErrorPO} 的差异：
 *   - error_code 是 VARCHAR(255)，Java 侧用 String
 *   - 字段来源于 content 正则提取（上游 Processor 负责转换）
 */
@Data
@TableName("control_hitch_error_mothod")
public class ControlHitchErrorPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String methodName;

    /** VARCHAR(255)：不同于 GwHitch 用 Integer，这里是 String */
    private String errorCode;

    private String errorMessage;

    private String content;

    private Integer count;

    private Long totalCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
