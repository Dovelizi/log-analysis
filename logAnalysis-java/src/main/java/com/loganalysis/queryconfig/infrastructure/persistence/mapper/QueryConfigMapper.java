package com.loganalysis.queryconfig.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.queryconfig.infrastructure.persistence.po.QueryConfigPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * query_configs Mapper。
 *
 * 对齐原 Service 的 3 个复合查询：
 *   - listAll：JOIN topic + credential，返回 Map（用于列表接口）
 *   - findWithTopic：按 id JOIN topic，返回单条 Map（用于调度器取完整配置）
 *   - delete：级联删除 log_records / analysis_results
 */
public interface QueryConfigMapper extends BaseMapper<QueryConfigPO> {

    /** 列表（JOIN topic + credential），返回 Map 以保留所有列及字段顺序 */
    @Select("SELECT q.*, t.topic_id AS cls_topic_id, t.topic_name, " +
            "       c.name AS credential_name, c.region " +
            "FROM query_configs q " +
            "JOIN log_topics t ON q.topic_id = t.id " +
            "JOIN api_credentials c ON t.credential_id = c.id")
    List<Map<String, Object>> listAllWithTopic();

    /** 按 id 单条（JOIN topic），用于调度器读取完整查询上下文 */
    @Select("SELECT q.*, t.topic_id AS cls_topic_id, t.credential_id, t.region " +
            "FROM query_configs q " +
            "JOIN log_topics t ON q.topic_id = t.id " +
            "WHERE q.id = #{id}")
    Map<String, Object> findWithTopic(@Param("id") long id);

    /** 删除关联的 log_records（级联） */
    @Delete("DELETE FROM log_records WHERE query_config_id = #{id}")
    int deleteLogRecordsByConfigId(@Param("id") long id);

    /** 删除关联的 analysis_results（级联） */
    @Delete("DELETE FROM analysis_results WHERE query_config_id = #{id}")
    int deleteAnalysisResultsByConfigId(@Param("id") long id);
}
