# 推送配置功能升级说明

## 修复内容

### 1. 修复定时推送中折线图数据错误问题 ✅

**问题描述：** 定时推送时折线图数据为空或不正确

**解决方案：** 
- 修改 `dashboard_routes.py` 中的趋势数据查询逻辑
- 从依赖 `hitch_error_log_insert_record` 表改为直接从原始数据表查询
- 确保推送时能获取到正确的历史趋势数据

**修改文件：**
- `routes/dashboard_routes.py` - 更新趋势数据查询SQL

### 2. 添加推送配置支持指定日期推送功能 ✅

**新功能：** 支持在指定日期推送报表

**实现方式：**
- 添加 `push_mode` 字段，支持 `date` 模式
- 添加 `push_date` 字段，存储指定的推送日期
- 调度器检查当前日期是否匹配指定日期

### 3. 添加推送配置支持T-N天配置推送功能 ✅

**新功能：** 支持推送T-N天前的数据（如T-1昨天，T-7一周前）

**实现方式：**
- 添加 `push_mode` 字段，支持 `relative` 模式  
- 添加 `relative_days` 字段，存储相对天数
- 调度器根据相对天数计算目标日期

### 4. 更新前端界面支持新的推送配置选项 ✅

**界面改进：**
- 推送配置模态框增加推送模式选择
- 支持三种模式：每日定时、指定日期、相对日期(T-N)
- 推送配置列表显示推送模式和详细时间信息
- 优化推送时间显示逻辑

## 数据库结构变更

### 新增字段

```sql
-- report_push_config 表新增字段
push_mode VARCHAR(32) DEFAULT 'daily'  -- 推送模式
push_date DATE NULL                    -- 指定推送日期  
relative_days INT DEFAULT 0            -- 相对天数
```

### 推送模式说明

1. **daily（每日定时）**
   - 每天在指定时间推送当天的报表数据
   - 默认模式，兼容现有配置

2. **date（指定日期）**
   - 只在指定日期推送一次
   - 适用于特殊日期的报表推送

3. **relative（相对日期T-N）**
   - 每天推送T-N天前的数据
   - 如T-1推送昨天数据，T-7推送一周前数据

## 升级步骤

### 1. 数据库迁移

```bash
# 执行SQL迁移脚本
mysql -u username -p database_name < migrations/add_push_mode_fields.sql
```

### 2. 重启服务

```bash
# 重启Flask应用
python app.py
```

### 3. 验证功能

1. 访问"数据推送"页面
2. 创建新的推送配置，测试三种推送模式
3. 检查定时推送是否正常工作
4. 验证折线图数据是否正确显示

## 配置示例

### 每日定时推送
```json
{
  "name": "每日报表推送",
  "push_type": "wecom", 
  "push_mode": "daily",
  "schedule_time": "09:00",
  "schedule_enabled": 1
}
```

### 指定日期推送
```json
{
  "name": "月度报表推送",
  "push_type": "wecom",
  "push_mode": "date", 
  "push_date": "2025-01-31",
  "schedule_time": "10:00",
  "schedule_enabled": 1
}
```

### 相对日期推送
```json
{
  "name": "昨日数据推送", 
  "push_type": "wecom",
  "push_mode": "relative",
  "relative_days": 1,
  "schedule_time": "08:00", 
  "schedule_enabled": 1
}
```

## 注意事项

1. **兼容性：** 现有推送配置会自动设置为 `daily` 模式
2. **时区：** 所有时间均使用服务器本地时区
3. **数据可用性：** 确保目标日期的数据已采集完成
4. **推送频率：** 每个配置每天最多推送一次，避免重复推送

## 故障排查

### 折线图数据为空
- 检查目标日期是否有数据
- 确认数据表中 `create_time` 字段格式正确
- 查看调度器日志确认截图API调用成功

### 定时推送不工作
- 检查 `schedule_enabled` 是否为1
- 确认 `schedule_time` 格式为 HH:MM
- 查看调度器日志确认配置已加载

### 推送模式不生效
- 确认数据库字段已正确添加
- 检查前端是否正确传递新字段
- 验证后端API是否处理新字段