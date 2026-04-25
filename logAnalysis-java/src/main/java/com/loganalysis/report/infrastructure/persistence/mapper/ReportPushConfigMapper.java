package com.loganalysis.report.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.loganalysis.report.infrastructure.persistence.po.ReportPushConfigPO;

/**
 * report_push_config Mapper。
 *
 * BaseMapper 提供的 insert/selectById/updateById/deleteById/selectList 已够用，
 * listAll 用 selectList + 排序通过 Wrapper 表达。
 */
public interface ReportPushConfigMapper extends BaseMapper<ReportPushConfigPO> {
}
