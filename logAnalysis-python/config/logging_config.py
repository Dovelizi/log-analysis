"""
日志配置模块
实现按日期维度的日志文件输出
"""
import os
import logging
import logging.handlers
from datetime import datetime
from pathlib import Path


class DateRotatingFileHandler(logging.handlers.BaseRotatingHandler):
    """
    按日期轮转的日志处理器
    每天创建一个新的日志文件，文件名格式：YYYY-MM-DD.log
    """
    
    def __init__(self, filename, mode='a', encoding=None, delay=False, utc=False):
        self.utc = utc
        self.base_filename = filename
        self.current_date = None
        
        # 确保日志目录存在
        log_dir = os.path.dirname(filename)
        if log_dir and not os.path.exists(log_dir):
            os.makedirs(log_dir, exist_ok=True)
            
        # 初始化当前日期的日志文件
        self._update_filename()
        
        # 使用更新后的文件名初始化父类
        super().__init__(self.baseFilename, mode, encoding, delay)
    
    def _update_filename(self):
        """更新日志文件名为当前日期"""
        if self.utc:
            current_date = datetime.utcnow().strftime('%Y-%m-%d')
        else:
            current_date = datetime.now().strftime('%Y-%m-%d')
            
        if current_date != self.current_date:
            self.current_date = current_date
            # 构建新的文件名
            base_dir = os.path.dirname(self.base_filename)
            base_name = os.path.basename(self.base_filename)
            name, ext = os.path.splitext(base_name)
            
            # 生成带日期的文件名：YYYY-MM-DD_appname.log
            dated_filename = f"{current_date}_{name}.log"
            self.baseFilename = os.path.join(base_dir, dated_filename)
    
    def shouldRollover(self, record):
        """检查是否需要轮转日志文件（日期变化时）"""
        if self.utc:
            current_date = datetime.utcnow().strftime('%Y-%m-%d')
        else:
            current_date = datetime.now().strftime('%Y-%m-%d')
            
        return current_date != self.current_date
    
    def doRollover(self):
        """执行日志文件轮转"""
        if self.stream:
            self.stream.close()
            self.stream = None
            
        # 更新文件名
        self._update_filename()
        
        # 重新打开新的日志文件
        if not self.delay:
            self.stream = self._open()


def setup_logging(app_name="loganalysis", log_level=logging.INFO, log_dir="logs"):
    """
    设置应用程序日志配置
    
    Args:
        app_name: 应用程序名称
        log_level: 日志级别
        log_dir: 日志目录
    
    Returns:
        logger: 配置好的日志记录器
    """
    # 确保日志目录存在
    log_path = Path(log_dir)
    log_path.mkdir(exist_ok=True)
    
    # 创建日志记录器
    logger = logging.getLogger(app_name)
    logger.setLevel(log_level)
    
    # 清除现有的处理器
    logger.handlers.clear()
    
    # 创建日志格式
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(filename)s:%(lineno)d - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    
    # 1. 按日期轮转的文件处理器
    file_handler = DateRotatingFileHandler(
        filename=os.path.join(log_dir, f"{app_name}.log"),
        encoding='utf-8'
    )
    file_handler.setLevel(log_level)
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)
    
    # 2. 控制台处理器（用于开发调试）
    console_handler = logging.StreamHandler()
    console_handler.setLevel(log_level)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)
    
    # 3. 错误日志单独文件处理器
    error_handler = DateRotatingFileHandler(
        filename=os.path.join(log_dir, f"{app_name}_error.log"),
        encoding='utf-8'
    )
    error_handler.setLevel(logging.ERROR)
    error_handler.setFormatter(formatter)
    logger.addHandler(error_handler)
    
    return logger


def get_logger(name=None):
    """
    获取日志记录器
    
    Args:
        name: 日志记录器名称，如果为None则返回根记录器
    
    Returns:
        logger: 日志记录器实例
    """
    if name is None:
        return logging.getLogger("loganalysis")
    return logging.getLogger(f"loganalysis.{name}")


# 创建不同模块的专用日志记录器
def get_api_logger():
    """获取API日志记录器"""
    return get_logger("api")


def get_db_logger():
    """获取数据库日志记录器"""
    return get_logger("database")


def get_cls_logger():
    """获取CLS日志记录器"""
    return get_logger("cls")


def get_task_logger():
    """获取任务日志记录器"""
    return get_logger("task")


# 日志装饰器
def log_api_call(logger=None):
    """
    API调用日志装饰器
    """
    def decorator(func):
        from functools import wraps
        @wraps(func)  # 保持原函数的元数据
        def wrapper(*args, **kwargs):
            if logger is None:
                log = get_api_logger()
            else:
                log = logger
                
            start_time = datetime.now()
            log.info(f"API调用开始: {func.__name__} - 参数: args={args}, kwargs={kwargs}")
            
            try:
                result = func(*args, **kwargs)
                end_time = datetime.now()
                duration = (end_time - start_time).total_seconds()
                log.info(f"API调用成功: {func.__name__} - 耗时: {duration:.3f}秒")
                return result
            except Exception as e:
                end_time = datetime.now()
                duration = (end_time - start_time).total_seconds()
                log.error(f"API调用失败: {func.__name__} - 耗时: {duration:.3f}秒 - 错误: {str(e)}")
                raise
                
        return wrapper
    return decorator


def log_database_operation(logger=None):
    """
    数据库操作日志装饰器
    """
    def decorator(func):
        from functools import wraps
        @wraps(func)  # 保持原函数的元数据
        def wrapper(*args, **kwargs):
            if logger is None:
                log = get_db_logger()
            else:
                log = logger
                
            start_time = datetime.now()
            log.debug(f"数据库操作开始: {func.__name__}")
            
            try:
                result = func(*args, **kwargs)
                end_time = datetime.now()
                duration = (end_time - start_time).total_seconds()
                log.debug(f"数据库操作成功: {func.__name__} - 耗时: {duration:.3f}秒")
                return result
            except Exception as e:
                end_time = datetime.now()
                duration = (end_time - start_time).total_seconds()
                log.error(f"数据库操作失败: {func.__name__} - 耗时: {duration:.3f}秒 - 错误: {str(e)}")
                raise
                
        return wrapper
    return decorator