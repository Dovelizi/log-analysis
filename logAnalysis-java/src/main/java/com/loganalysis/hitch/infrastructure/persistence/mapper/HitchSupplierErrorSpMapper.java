package com.loganalysis.hitch.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.hitch.infrastructure.persistence.po.HitchSupplierErrorSpPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 顺风车供应商维度错误（含 sp_name）聚合表 Mapper。
 *
 * 与其他 Hitch Mapper 的差异：
 *   - 查询键含 sp_id（4 元组）
 *   - UPDATE 语句同步刷 sp_name
 *   - errorStatistics 的 GROUP BY 包含 sp_id / sp_name
 *
 * @see com.loganalysis.hitch.application.HitchSupplierErrorSpProcessor
 */
public interface HitchSupplierErrorSpMapper extends BaseMapper<HitchSupplierErrorSpPO> {

    @Select("SELECT id, count, total_count, create_time, update_time " +
            "FROM `hitch_supplier_error_sp` " +
            "WHERE sp_id <=> #{spId} " +
            "  AND method_name <=> #{methodName} " +
            "  AND error_code <=> #{errorCode} " +
            "  AND error_message <=> #{errorMessage} " +
            "  AND create_time >= #{todayStart} " +
            "  AND create_time <= #{todayEnd} " +
            "ORDER BY create_time DESC LIMIT 1")
    Map<String, Object> findTodaySameGroup(@Param("spId") Object spId,
                                           @Param("methodName") Object methodName,
                                           @Param("errorCode") Object errorCode,
                                           @Param("errorMessage") Object errorMessage,
                                           @Param("todayStart") String todayStart,
                                           @Param("todayEnd") String todayEnd);

    /** 与其他 Hitch Mapper 的差异：同步刷 sp_name 字段 */
    @Update("UPDATE `hitch_supplier_error_sp` " +
            "SET `count` = #{count}, `total_count` = #{totalCount}, " +
            "    `content` = #{content}, `sp_name` = #{spName} " +
            "WHERE id = #{id}")
    int updateCountById(@Param("id") long id,
                        @Param("count") int count,
                        @Param("totalCount") long totalCount,
                        @Param("content") Object content,
                        @Param("spName") Object spName);

    @Select("SELECT * FROM `hitch_supplier_error_sp` " +
            "ORDER BY `${orderBy}` ${orderDir} " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> pageList(@Param("orderBy") String orderBy,
                                       @Param("orderDir") String orderDir,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM `hitch_supplier_error_sp`")
    long countAll();

    @Select("SELECT sp_id, sp_name, error_code, error_message, method_name, " +
            "       SUM(total_count) AS count, MAX(update_time) AS last_time " +
            "FROM hitch_supplier_error_sp " +
            "GROUP BY sp_id, sp_name, error_code, error_message, method_name " +
            "ORDER BY count DESC LIMIT 50")
    List<Map<String, Object>> errorStatistics();
}
