package com.loganalysis.hitch.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.hitch.infrastructure.persistence.po.GwHitchErrorPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 网关顺风车错误聚合表 Mapper。
 *
 * 设计说明：
 * 1. 基础 CRUD（insert / updateById / selectById / selectList）由 {@link BaseMapper} 提供。
 * 2. 当日同组查找必须使用 MySQL 的 {@code <=>} NULL-safe 等值比较，这是与 Python 版行为严格对齐的关键；
 *    MP QueryWrapper 不原生支持 <=>，此处用 @Select 写原生 SQL。
 * 3. 分页查询表数据（{@code getTableData}）因涉及动态列名白名单校验，上层 Service 已在代码里做校验，
 *    Mapper 层用 {@code ${orderBy}}（MP 识别为不转义变量）接收已白名单过滤的列名。
 * 4. 聚合统计（{@code errorStatistics}）的 GROUP BY + SUM 用 @Select 直接表达。
 *
 * @see com.loganalysis.hitch.application.GwHitchProcessor
 */
public interface GwHitchErrorMapper extends BaseMapper<GwHitchErrorPO> {

    /**
     * 查找当日同业务键（method_name + error_code + error_message）的记录，返回最新一条。
     * NULL-safe 对齐 Python pymysql 的 {@code IS NOT DISTINCT FROM} 语义。
     */
    @Select("SELECT id, count, total_count, create_time, update_time " +
            "FROM `gw_hitch_error_mothod` " +
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

    /**
     * 按 id UPSERT 聚合计数（count / total_count / content）。
     * 对齐原 "UPDATE ... SET count = ?, total_count = ?, content = ? WHERE id = ?"。
     */
    @Update("UPDATE `gw_hitch_error_mothod` " +
            "SET `count` = #{count}, `total_count` = #{totalCount}, `content` = #{content} " +
            "WHERE id = #{id}")
    int updateCountById(@Param("id") long id,
                        @Param("count") int count,
                        @Param("totalCount") long totalCount,
                        @Param("content") Object content);

    /**
     * 分页查询：order by 的列名必须由调用方通过白名单校验后传入。
     * 使用 ${} 非参数化是为了允许列名/排序方向作为 SQL 一部分，SQL 注入由上层白名单拦截。
     */
    @Select("SELECT * FROM `gw_hitch_error_mothod` " +
            "ORDER BY `${orderBy}` ${orderDir} " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> pageList(@Param("orderBy") String orderBy,
                                       @Param("orderDir") String orderDir,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    /**
     * 表总行数（用于分页 total）。
     */
    @Select("SELECT COUNT(*) FROM `gw_hitch_error_mothod`")
    long countAll();

    /**
     * 按 (error_code, error_message, method_name) 聚合，Top 50 按 count 倒序。
     * 对齐原 SQL：
     *   SELECT error_code, error_message, method_name, SUM(total_count) AS count, MAX(update_time) AS last_time
     *   FROM gw_hitch_error_mothod GROUP BY error_code, error_message, method_name
     *   ORDER BY count DESC LIMIT 50
     */
    @Select("SELECT error_code, error_message, method_name, " +
            "       SUM(total_count) AS count, MAX(update_time) AS last_time " +
            "FROM gw_hitch_error_mothod " +
            "GROUP BY error_code, error_message, method_name " +
            "ORDER BY count DESC LIMIT 50")
    List<Map<String, Object>> errorStatistics();
}
