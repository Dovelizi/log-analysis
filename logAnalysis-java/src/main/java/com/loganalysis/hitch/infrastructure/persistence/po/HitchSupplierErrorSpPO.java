package com.loganalysis.hitch.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 顺风车供应商维度错误明细聚合表 PO。
 *
 * 对应表：hitch_supplier_error_sp。
 *
 * 业务唯一键：(sp_id, method_name, error_code, error_message) 四元组；sp_name 是描述性字段，
 * 在 UPDATE 时也会被更新（而 HitchSupplierErrorTotalPO 无 sp_name）。
 */
@Data
@TableName("hitch_supplier_error_sp")
public class HitchSupplierErrorSpPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer spId;

    private String spName;

    private String methodName;

    private String content;

    private Integer errorCode;

    private String errorMessage;

    private Integer count;

    private Long totalCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
