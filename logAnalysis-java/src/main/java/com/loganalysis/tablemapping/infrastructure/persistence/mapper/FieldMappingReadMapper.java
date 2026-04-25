package com.loganalysis.tablemapping.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * field_mappings 表的只读 Mapper（供 Hitch 领域 5 个 Processor 读取字段映射规则）。
 *
 * 设计说明：
 *   - JOIN topic_table_mappings 以按表名过滤
 *   - 上层 Processor 把 List 转成 {target_column -&gt; row} 的 Map 使用
 */
@Mapper
public interface FieldMappingReadMapper {

    /**
     * 按业务表名查 field_mappings 全量行（含 source_field / target_column / transform_rule 等）。
     *
     * 对齐原 SQL：
     *   SELECT fm.* FROM field_mappings fm
     *   JOIN topic_table_mappings m ON fm.mapping_id = m.id
     *   WHERE m.table_name = ?
     */
    @Select("SELECT fm.* FROM field_mappings fm " +
            "JOIN topic_table_mappings m ON fm.mapping_id = m.id " +
            "WHERE m.table_name = #{tableName}")
    List<Map<String, Object>> findByTableName(@Param("tableName") String tableName);
}
