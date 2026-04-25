package com.loganalysis.topic.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.topic.infrastructure.persistence.po.TopicPO;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * log_topics Mapper。
 *
 * 对齐原 Service 的 listAll：需要 JOIN api_credentials 带出 credential_name。
 */
public interface TopicMapper extends BaseMapper<TopicPO> {

    /**
     * 全量列表，带 credential_name（JOIN api_credentials）。
     *
     * 对齐原 SQL：
     *   SELECT t.*, c.name AS credential_name
     *   FROM log_topics t JOIN api_credentials c ON t.credential_id = c.id
     */
    @Select("SELECT t.*, c.name AS credential_name " +
            "FROM log_topics t JOIN api_credentials c ON t.credential_id = c.id")
    List<Map<String, Object>> listAllWithCredentialName();
}
