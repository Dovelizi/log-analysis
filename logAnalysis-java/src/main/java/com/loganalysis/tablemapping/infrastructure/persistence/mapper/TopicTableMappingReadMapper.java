package com.loganalysis.tablemapping.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * topic_table_mappings 表的只读 Mapper（供 Hitch 领域 5 个 Processor 读取 field_config）。
 *
 * 设计说明：
 *   - 命名加 "Read" 后缀以区分 TableMappingService 的完整 CRUD（那部分保留 JdbcTemplate，因动态 DDL 场景 MP 无法覆盖）
 *   - 仅暴露读取 field_config 的单一方法，Controller / TableMappingService 不使用此 Mapper
 */
@Mapper
public interface TopicTableMappingReadMapper {

    /**
     * 读取指定业务表的字段配置 JSON（field_config 列），若不存在返回 null。
     *
     * 对齐原 SQL：
     *   SELECT field_config FROM topic_table_mappings WHERE table_name = ?
     */
    @Select("SELECT field_config FROM topic_table_mappings WHERE table_name = #{tableName} LIMIT 1")
    String findFieldConfigJson(@Param("tableName") String tableName);
}
