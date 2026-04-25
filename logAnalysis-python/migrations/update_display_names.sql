-- 更新 query_configs 表中的显示名称
-- Control错误 -> 顺风车错误日志
-- GW错误 -> 网关错误日志
-- 高耗时接口 -> 顺风车高耗时日志
-- 供应商错误SP -> 顺风车服务商错误日志
-- 供应商错误汇总 -> 顺风车服务商错误汇总日志

UPDATE query_configs SET name = REPLACE(name, 'Control错误', '顺风车错误日志') WHERE name LIKE '%Control错误%';
UPDATE query_configs SET name = REPLACE(name, 'GW错误', '网关错误日志') WHERE name LIKE '%GW错误%';
UPDATE query_configs SET name = REPLACE(name, '高耗时接口', '顺风车高耗时日志') WHERE name LIKE '%高耗时接口%';
UPDATE query_configs SET name = REPLACE(name, '供应商错误SP', '顺风车服务商错误日志') WHERE name LIKE '%供应商错误SP%';
UPDATE query_configs SET name = REPLACE(name, '供应商错误汇总', '顺风车服务商错误汇总日志') WHERE name LIKE '%供应商错误汇总%';

-- 查看更新结果
SELECT id, name FROM query_configs;
