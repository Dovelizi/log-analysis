package com.loganalysis.hitch.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.hitch.infrastructure.persistence.po.HitchControlCostTimePO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 顺风车控制层方法耗时监控日志表 Mapper。
 *
 * 由于业务特点（每条独立 INSERT、无聚合），Mapper 主要依赖 BaseMapper.insert；
 * 分页查询保留用于 Dashboard 场景。
 *
 * @see com.loganalysis.hitch.application.HitchControlCostTimeProcessor
 */
public interface HitchControlCostTimeMapper extends BaseMapper<HitchControlCostTimePO> {

    @Select("SELECT * FROM `hitch_control_cost_time` " +
            "ORDER BY `${orderBy}` ${orderDir} " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<Map<String, Object>> pageList(@Param("orderBy") String orderBy,
                                       @Param("orderDir") String orderDir,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM `hitch_control_cost_time`")
    long countAll();
}
