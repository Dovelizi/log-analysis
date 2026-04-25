package com.loganalysis.report.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报表推送配置 PO（report_push_config）。
 *
 * push_mode 取值：daily / date / relative。
 * email_config 是 JSON 字符串，Service 层解析为 Map。
 */
@Data
@TableName("report_push_config")
public class ReportPushConfigPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** wecom / email */
    private String pushType;

    private String webhookUrl;

    /** 邮箱配置 JSON 字符串 */
    private String emailConfig;

    /** 0=禁用 1=启用 */
    private Integer scheduleEnabled;

    private String scheduleCron;

    /** HH:MM 格式 */
    private String scheduleTime;

    /** daily / date / relative */
    private String pushMode;

    private LocalDate pushDate;

    private Integer relativeDays;

    private LocalDateTime lastPushTime;

    private LocalDateTime lastScheduledPushTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
