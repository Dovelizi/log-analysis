package com.loganalysis.topic.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 腾讯云 CLS Topic 配置表 PO（log_topics）。
 */
@Data
@TableName("log_topics")
public class TopicPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long credentialId;

    private String region;

    /** CLS 侧的 Topic ID，形如 "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" */
    private String topicId;

    private String topicName;

    private String description;

    private LocalDateTime createdAt;
}
