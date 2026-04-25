package com.loganalysis.hitch.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.hitch.infrastructure.persistence.po.HitchSupplierErrorTotalPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 顺风车供应商维度错误聚合统计表 Mapper（无 sp_name）。
 *
 * @see com.loganalysis.hitch.application.HitchSupplierErrorTotalProcessor
 */
public interface HitchSupplierErrorTotalMapper extends BaseMapper<HitchSupplierErrorTotalPO> {

    @Select("SELECT id, count, total_count, create_time, update_time " +
            "FROM `hitch_supplier_error_total` " +
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

    @Update("UPDATE `hitch_supplier_error_total` " +
            "SET `count` = #{count}, `total_count` = #{totalCount}, `content` = #{content} " +
            "WHERE id = #{id}")
    int updateCountById(@Param("id") long id,
                        @Param("count") int count,
                        @Param("totalCount") long totalCount,
                        @Param("content") Object content);

    @Select("SELECT * FROM `hitch_supplier_error_total` " +
            "ORDER BY `${orderBy}` ${orderDir} " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> pageList(@Param("orderBy") String orderBy,
                                       @Param("orderDir") String orderDir,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM `hitch_supplier_error_total`")
    long countAll();
}
