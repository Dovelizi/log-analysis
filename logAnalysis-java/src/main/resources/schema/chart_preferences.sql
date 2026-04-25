-- 图表偏好配置表（二期功能：图表设置全局生效 + 用户 override）
--
-- 数据模型说明：
--   scope_type='global' 的行代表全站默认配置，由管理员维护（当前无登录系统，
--   实际变更通过直接 UPDATE 此表或调 API 完成）
--   scope_type='user' 的行代表用户级 override（预留，当前 scope_id 为固定
--   字符串 'default_user'；接入 SSO 后改为真实 uid）
--
-- chart_id 枚举（对齐前端 3 个图表）：
--   time_chart      错误趋势折线图
--   topic_chart     主题分布饼图
--   dashboard_chart Dashboard 内动态图表（error_code_distribution 等）
--
-- settings_json 字段说明（前端按 key 取值，缺失字段用前端硬编码默认）：
--   granularity:   "10m" | "1h" | "1d"     —— 仅折线图
--   chart_type:    "line" | "bar" | "area" | "pie"
--   top_n:         10 | 20 | 50
--   theme:         "light" | "dark"         —— 全局外观，仅 scope_type=global + chart_id='__global__' 使用
--   legend_position: "top" | "bottom" | "left" | "right"  —— 同上

CREATE TABLE IF NOT EXISTS chart_preferences (
    id             BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    scope_type     VARCHAR(16) NOT NULL COMMENT 'global / user',
    scope_id       VARCHAR(64) NOT NULL DEFAULT '*' COMMENT 'scope_type=global 固定 *；user 为用户 ID',
    chart_id       VARCHAR(64) NOT NULL COMMENT '图表标识；__global__ 代表全局外观',
    settings_json  TEXT NOT NULL COMMENT '配置 JSON，key 参见表头注释',
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope_chart (scope_type, scope_id, chart_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图表偏好配置';

-- 默认全局配置种子数据（首次部署时插入；已存在则跳过）
INSERT IGNORE INTO chart_preferences (scope_type, scope_id, chart_id, settings_json) VALUES
  ('global', '*', '__global__',      '{"theme":"light","legend_position":"bottom"}'),
  ('global', '*', 'time_chart',      '{"granularity":"1h","chart_type":"line","top_n":10}'),
  ('global', '*', 'topic_chart',     '{"chart_type":"pie","top_n":10}'),
  ('global', '*', 'dashboard_chart', '{"chart_type":"bar","top_n":10}');
