# Gunicorn Docker配置文件
import os
import multiprocessing

# 绑定地址
bind = "0.0.0.0:8080"

# 工作进程数（Docker容器中建议根据CPU核心数设置）
workers = int(os.environ.get('GUNICORN_WORKERS', multiprocessing.cpu_count() * 2 + 1))

# 工作模式
worker_class = "sync"

# 超时时间（秒）
timeout = 120

# 优雅关闭超时
graceful_timeout = 30

# 最大请求数（防止内存泄漏）
max_requests = 1000
max_requests_jitter = 50

# 日志配置
accesslog = "-"  # 输出到stdout
errorlog = "-"   # 输出到stderr
loglevel = os.environ.get('LOG_LEVEL', 'info')

# 进程名
proc_name = "loganalysis"

# 守护进程（Docker中必须为False）
daemon = False

# 预加载应用
preload_app = True

# 转发头部
forwarded_allow_ips = '*'

# 保持连接
keepalive = 5
