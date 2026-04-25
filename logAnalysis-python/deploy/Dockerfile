# 日志分析系统 Dockerfile
# 多阶段构建，优化镜像大小

# ==================== 构建阶段 ====================
FROM python:3.11-slim as builder

WORKDIR /build

# 安装构建依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    libffi-dev \
    libssl-dev \
    default-libmysqlclient-dev \
    pkg-config \
    && rm -rf /var/lib/apt/lists/*

# 复制依赖文件
COPY requirements.txt .

# 创建虚拟环境并安装依赖
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt && \
    pip install --no-cache-dir gunicorn pymysql redis

# ==================== 运行阶段 ====================
FROM python:3.11-slim

LABEL maintainer="LogAnalysis Team"
LABEL description="CLS日志分析系统"
LABEL version="1.0.0"

# 创建非root用户
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

# 安装运行时依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    default-libmysqlclient-dev \
    && rm -rf /var/lib/apt/lists/* \
    && apt-get clean

# 从构建阶段复制虚拟环境
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# 复制应用代码
COPY app.py .
COPY tc3_signature.py .
COPY tencent_cloud_api.py .
COPY config/ ./config/
COPY models/ ./models/
COPY routes/ ./routes/
COPY services/ ./services/
COPY static/ ./static/
COPY migrations/ ./migrations/

# 创建必要目录
RUN mkdir -p /app/logs /app/data && \
    chown -R appuser:appgroup /app

# 复制Gunicorn配置
COPY deploy/gunicorn.docker.conf.py /app/gunicorn.conf.py

# 切换到非root用户
USER appuser

# 环境变量
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    FLASK_ENV=production \
    DB_TYPE=mysql

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# 启动命令
CMD ["gunicorn", "-c", "gunicorn.conf.py", "app:app"]
