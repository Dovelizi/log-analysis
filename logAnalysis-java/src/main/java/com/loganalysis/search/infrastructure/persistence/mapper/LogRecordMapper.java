package com.loganalysis.search.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.search.infrastructure.persistence.po.LogRecordPO;

/**
 * hitch_error_log_insert_record Mapper。
 *
 * 由 {@link com.loganalysis.search.infrastructure.InsertRecordService} 使用，
 * 仅 INSERT，无其他 CRUD。
 */
public interface LogRecordMapper extends BaseMapper<LogRecordPO> {
}
