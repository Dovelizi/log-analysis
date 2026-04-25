package com.loganalysis.hitch.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 顺风车供应商维度错误聚合统计表 PO（无 sp_name）。
 *
 * 对应表：hitch_supplier_error_total。
 *
 * 与 {@link HitchSupplierErrorSpPO} 的差异：
 *   - 无 sp_name 字段
 *   - UPDATE 语句不涉及 sp_name
 */
@Data
@TableName("hitch_supplier_error_total")
public class HitchSupplierErrorTotalPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer spId;

    private String methodName;

    private Integer errorCode;

    private String errorMessage;

    private String content;

    private Integer count;

    private Long totalCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
