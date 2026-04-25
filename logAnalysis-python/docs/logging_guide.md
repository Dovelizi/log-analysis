# 日志系统使用指南

## 概述

本系统实现了按日期维度的日志输出功能，每天的日志会自动写入到独立的日志文件中，便于日志管理和问题排查。

## 日志文件结构

```
logs/
├── 2026-01-08_loganalysis.log          # 主应用日志
├── 2026-01-08_loganalysis_error.log    # 错误日志
├── 2026-01-09_loganalysis.log          # 第二天的主应用日志
└── 2026-01-09_loganalysis_error.log    # 第二天的错误日志
```

## 日志文件命名规则

- **格式**: `YYYY-MM-DD_应用名称[_模块].log`
- **示例**: 
  - `2026-01-08_loganalysis.log` - 主应用日志
  - `2026-01-08_loganalysis_error.log` - 错误日志
  - `2026-01-08_loganalysis_api.log` - API模块日志

## 日志级别

系统支持以下日志级别（按严重程度排序）：

1. **DEBUG** - 调试信息，详细的程序执行信息
2. **INFO** - 一般信息，程序正常运行的关键节点
3. **WARNING** - 警告信息，可能的问题但不影响程序运行
4. **ERROR** - 错误信息，程序执行出现错误但可以继续运行
5. **CRITICAL** - 严重错误，程序可能无法继续运行

## 配置说明

### 环境变量配置

```bash
# 设置日志级别（可选：DEBUG, INFO, WARNING, ERROR, CRITICAL）
export LOG_LEVEL=INFO

# 启动应用
python3 app.py
```

### 代码中使用日志

#### 1. 获取日志记录器

```python
from config.logging_config import get_logger, get_api_logger, get_db_logger

# 获取主日志记录器
logger = get_logger()

# 获取API模块日志记录器
api_logger = get_api_logger()

# 获取数据库模块日志记录器
db_logger = get_db_logger()
```

#### 2. 记录日志

```python
# 记录不同级别的日志
logger.debug("调试信息")
logger.info("程序启动")
logger.warning("配置文件缺失，使用默认配置")
logger.error("数据库连接失败")

# 记录异常信息
try:
    # 一些可能出错的代码
    pass
except Exception as e:
    logger.error(f"操作失败: {e}", exc_info=True)
```

#### 3. 使用日志装饰器

```python
from config.logging_config import log_api_call, log_database_operation

# API调用日志装饰器
@log_api_call()
def my_api_function():
    return {"status": "success"}

# 数据库操作日志装饰器
@log_database_operation()
def query_database():
    # 数据库查询代码
    pass
```

## 日志格式

日志采用统一格式：

```
时间戳 - 记录器名称 - 日志级别 - 文件名:行号 - 日志消息
```

**示例**:
```
2026-01-08 15:22:21 - loganalysis.api - INFO - dashboard_routes.py:103 - API调用开始: get_available_dates
```

## 日志轮转

- **轮转方式**: 按日期自动轮转
- **轮转时间**: 每天00:00:00自动创建新的日志文件
- **文件保留**: 系统不会自动删除旧日志文件，需要手动清理

## 日志管理

### 查看实时日志

```bash
# 查看主日志
tail -f logs/$(date +%Y-%m-%d)_loganalysis.log

# 查看错误日志
tail -f logs/$(date +%Y-%m-%d)_loganalysis_error.log
```

### 日志清理

建议定期清理旧日志文件：

```bash
# 删除7天前的日志文件
find logs/ -name "*.log" -mtime +7 -delete

# 或者压缩旧日志文件
find logs/ -name "*.log" -mtime +7 -exec gzip {} \;
```

### 日志分析

```bash
# 统计错误数量
grep -c "ERROR" logs/$(date +%Y-%m-%d)_loganalysis.log

# 查找特定错误
grep "数据库连接失败" logs/*.log

# 分析API调用频率
grep "API调用开始" logs/$(date +%Y-%m-%d)_loganalysis.log | wc -l
```

## 性能考虑

1. **日志级别**: 生产环境建议使用INFO级别，避免过多DEBUG日志影响性能
2. **日志大小**: 单个日志文件过大时考虑按大小轮转
3. **磁盘空间**: 定期清理旧日志文件，避免磁盘空间不足

## 故障排查

### 常见问题

1. **日志文件未生成**
   - 检查logs目录权限
   - 确认日志配置正确加载

2. **日志内容缺失**
   - 检查日志级别设置
   - 确认代码中使用了正确的日志记录器

3. **日志文件过大**
   - 降低日志级别
   - 实施日志清理策略

### 调试模式

启用调试模式查看详细日志：

```bash
export LOG_LEVEL=DEBUG
python3 app.py
```

## 最佳实践

1. **合理使用日志级别**: 根据信息重要性选择合适的日志级别
2. **包含上下文信息**: 日志消息应包含足够的上下文信息便于排查
3. **避免敏感信息**: 不要在日志中记录密码、密钥等敏感信息
4. **结构化日志**: 对于复杂数据，考虑使用JSON格式记录
5. **定期维护**: 建立日志清理和监控机制

## 扩展功能

系统支持以下扩展功能：

- 自定义日志格式
- 多种输出目标（文件、控制台、远程服务器）
- 日志过滤和采样
- 性能监控和统计

如需更多高级功能，请参考 `config/logging_config.py` 文件中的详细实现。