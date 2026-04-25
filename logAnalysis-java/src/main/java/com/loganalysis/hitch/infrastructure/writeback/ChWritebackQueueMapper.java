package com.loganalysis.hitch.infrastructure.writeback;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ch_writeback_queue Mapper。
 *
 * 设计点：
 *   - pullDue：按 next_retry_at &lt;= NOW() 拉取到期任务，限批量大小
 *   - countBacklog：积压总量（用于告警阈值判定）
 */
public interface ChWritebackQueueMapper extends BaseMapper<ChWritebackQueuePO> {

    /** 拉取到期任务，按 id 升序（先进先出） */
    @Select("SELECT * FROM ch_writeback_queue " +
            "WHERE next_retry_at &lt;= NOW() " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<ChWritebackQueuePO> pullDue(@Param("limit") int limit);

    /** 积压总量（用于告警） */
    @Select("SELECT COUNT(*) FROM ch_writeback_queue")
    long countBacklog();
}
