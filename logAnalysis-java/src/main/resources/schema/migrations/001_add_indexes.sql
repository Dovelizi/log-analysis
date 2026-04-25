-- ================================================================
-- Migration 001: 接口性能优化索引
-- 目标：所有 Dashboard/Report 接口查询耗时 < 1s
--
-- 执行方式：
--   mysql -h$MYSQL_HOST -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE < 001_add_indexes.sql
--
-- 为什么不用 "ALTER TABLE ... ADD INDEX IF NOT EXISTS"？
--   MySQL 8.0.29 之前不支持该语法。本脚本用存储过程检查
--   information_schema.STATISTICS 实现幂等（重复执行不会报错）。
-- ================================================================

DELIMITER //

DROP PROCEDURE IF EXISTS add_index_if_absent //

CREATE PROCEDURE add_index_if_absent(
    IN p_table  VARCHAR(64),
    IN p_index  VARCHAR(64),
    IN p_cols   VARCHAR(255)
)
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_tbl_exists INT DEFAULT 0;

    SELECT COUNT(*) INTO v_tbl_exists
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table;

    IF v_tbl_exists = 0 THEN
        SELECT CONCAT('skip: table ', p_table, ' not exists') AS note;
    ELSE
        SELECT COUNT(*) INTO v_exists
          FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = p_table
           AND INDEX_NAME = p_index;

        IF v_exists = 0 THEN
            SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_cols, ')');
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
            SELECT CONCAT('created: ', p_table, '.', p_index) AS note;
        ELSE
            SELECT CONCAT('exists: ', p_table, '.', p_index) AS note;
        END IF;
    END IF;
END //

DELIMITER ;

-- ============ control_hitch_error_mothod ============
-- 用于: hitchStatistics 里 WHERE create_time BETWEEN + GROUP BY method_name / error_code
CALL add_index_if_absent('control_hitch_error_mothod', 'idx_ct_method',     '`create_time`, `method_name`');
CALL add_index_if_absent('control_hitch_error_mothod', 'idx_ct_errcode',    '`create_time`, `error_code`');

-- ============ gw_hitch_error_mothod ============
CALL add_index_if_absent('gw_hitch_error_mothod', 'idx_ct_method',  '`create_time`, `method_name`');
CALL add_index_if_absent('gw_hitch_error_mothod', 'idx_ct_errcode', '`create_time`, `error_code`');

-- ============ hitch_supplier_error_sp ============
CALL add_index_if_absent('hitch_supplier_error_sp', 'idx_ct_sp',       '`create_time`, `sp_id`');
CALL add_index_if_absent('hitch_supplier_error_sp', 'idx_ct_errcode',  '`create_time`, `error_code`');
CALL add_index_if_absent('hitch_supplier_error_sp', 'idx_sp_ct',       '`sp_id`, `create_time`');

-- ============ hitch_supplier_error_total ============
CALL add_index_if_absent('hitch_supplier_error_total', 'idx_ct',          '`create_time`');
CALL add_index_if_absent('hitch_supplier_error_total', 'idx_ct_sp',       '`create_time`, `sp_id`');
CALL add_index_if_absent('hitch_supplier_error_total', 'idx_ct_errcode',  '`create_time`, `error_code`');
CALL add_index_if_absent('hitch_supplier_error_total', 'idx_sp_ct',       '`sp_id`, `create_time`');

-- ============ hitch_control_cost_time ============
-- 用于: costTimeStatistics 按时间范围过滤 + 耗时排序/分组
CALL add_index_if_absent('hitch_control_cost_time', 'idx_ct',           '`create_time`');
-- 用于: high_cost_list (ORDER BY time_cost DESC LIMIT 10) 反向索引扫描
CALL add_index_if_absent('hitch_control_cost_time', 'idx_timecost',     '`time_cost`');
CALL add_index_if_absent('hitch_control_cost_time', 'idx_ct_method',    '`create_time`, `method_name`');
CALL add_index_if_absent('hitch_control_cost_time', 'idx_ct_timecost',  '`create_time`, `time_cost`');
-- 覆盖索引：用于 method_avg_cost (GROUP BY method_name + AVG/MAX time_cost)
-- 避免回表，EXPLAIN 显示 Using index
CALL add_index_if_absent('hitch_control_cost_time', 'idx_ct_method_tc', '`create_time`, `method_name`, `time_cost`');

-- ============ hitch_error_log_insert_record ============
-- 用于: trend_hourly / hourly_trend 查询 WHERE log_from=? AND create_time BETWEEN ?
CALL add_index_if_absent('hitch_error_log_insert_record', 'idx_logfrom_ct', '`log_from`, `create_time`');
CALL add_index_if_absent('hitch_error_log_insert_record', 'idx_ct',         '`create_time`');

-- ============ log_records ============
-- 用于: /api/log-records 按 query_config_id 过滤 + ORDER BY log_time
CALL add_index_if_absent('log_records', 'idx_qcid_logtime', '`query_config_id`, `log_time`');
CALL add_index_if_absent('log_records', 'idx_logtime',      '`log_time`');

-- ============ 清理 ============
DROP PROCEDURE IF EXISTS add_index_if_absent;
