package com.loganalysis.hitch.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.hitch.infrastructure.persistence.po.ControlHitchErrorPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 顺风车控制层错误聚合表 Mapper。
 *
 * @see com.loganalysis.hitch.application.ControlHitchProcessor
 * @see GwHitchErrorMapper 结构对称参考
 */
public interface ControlHitchErrorMapper extends BaseMapper<ControlHitchErrorPO> {

    @Select("SELECT id, count, total_count, create_time, update_time " +
            "FROM `control_hitch_error_mothod` " +
            "WHERE method_name <=> #{methodName} " +
            "  AND error_code <=> #{errorCode} " +
            "  AND error_message <=> #{errorMessage} " +
            "  AND create_time >= #{todayStart} " +
            "  AND create_time <= #{todayEnd} " +
            "ORDER BY create_time DESC LIMIT 1")
    Map<String, Object> findTodaySameGroup(@Param("methodName") Object methodName,
                                           @Param("errorCode") Object errorCode,
                                           @Param("errorMessage") Object errorMessage,
                                           @Param("todayStart") String todayStart,
                                           @Param("todayEnd") String todayEnd);

    @Update("UPDATE `control_hitch_error_mothod` " +
            "SET `count` = #{count}, `total_count` = #{totalCount}, `content` = #{content} " +
            "WHERE id = #{id}")
    int updateCountById(@Param("id") long id,
                        @Param("count") int count,
                        @Param("totalCount") long totalCount,
                        @Param("content") Object content);

    @Select("SELECT * FROM `control_hitch_error_mothod` " +
            "ORDER BY `${orderBy}` ${orderDir} " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> pageList(@Param("orderBy") String orderBy,
                                       @Param("orderDir") String orderDir,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM `control_hitch_error_mothod`")
    long countAll();

    @Select("SELECT error_code, error_message, method_name, " +
            "       SUM(total_count) AS count, MAX(update_time) AS last_time " +
            "FROM control_hitch_error_mothod " +
            "GROUP BY error_code, error_message, method_name " +
            "ORDER BY count DESC LIMIT 50")
    List<Map<String, Object>> errorStatistics();
}
