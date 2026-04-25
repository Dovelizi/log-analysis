# 多阶段构建：
# 1. builder 阶段用 Maven 编译（走阿里云镜像加速）
# 2. runtime 阶段用 JRE-slim + 预装 Playwright 浏览器依赖
#
# 使用:
#   docker build -t loganalysis-java:latest .
#   docker run -d --name loganalysis-java \
#       -p 8080:8080 \
#       -e MYSQL_HOST=host.docker.internal \
#       -e MYSQL_PASSWORD=xxx \
#       -e ENCRYPTION_FORBID_AUTO_GEN=true \
#       -v $(pwd)/.encryption_key:/app/.encryption_key:ro \
#       -v $(pwd)/logs:/app/logs \
#       loganalysis-java:latest

# ==================== Stage 1: Build ====================
FROM maven:3.9-eclipse-temurin-11 AS builder

WORKDIR /build

# 使用阿里云镜像加速（拷贝配置文件）
COPY docker/maven-settings.xml /root/.m2/settings.xml

# 先拷 pom，利用 Docker layer cache（改代码不必重下依赖）
COPY pom.xml .
RUN mvn -q dependency:go-offline -DskipTests || true

# 再拷源码
COPY src ./src
RUN mvn -q -DskipTests package

# ==================== Stage 2: Runtime ====================
FROM eclipse-temurin:11-jre-jammy AS runtime

# 安装 Playwright Chromium 运行依赖（headless 浏览器必需）
# 注意：Chromium 二进制本身会在 Java 首次调用 Playwright 时下载
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    wget \
    ca-certificates \
    fonts-liberation \
    libasound2 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libatspi2.0-0 \
    libcairo2 \
    libcups2 \
    libdbus-1-3 \
    libdrm2 \
    libgbm1 \
    libglib2.0-0 \
    libgtk-3-0 \
    libnspr4 \
    libnss3 \
    libpango-1.0-0 \
    libx11-6 \
    libxcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxkbcommon0 \
    libxrandr2 \
    xdg-utils \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 创建非 root 用户（安全最佳实践）
RUN groupadd -r app && useradd -r -g app -d /app app \
    && mkdir -p /app/logs /app/.cache \
    && chown -R app:app /app

# 拷 jar 和 schema
COPY --from=builder --chown=app:app /build/target/loganalysis.jar /app/loganalysis.jar
COPY --chown=app:app src/main/resources/schema /app/schema

# JVM 选项（可被运行时 -e JAVA_OPTS 覆盖）
ENV JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 加密密钥默认路径，推荐以 volume 挂载覆盖
ENV ENCRYPTION_KEY_FILE=/app/.encryption_key
ENV ENCRYPTION_FORBID_AUTO_GEN=true

# Playwright 缓存目录（Chromium 二进制）
ENV PLAYWRIGHT_BROWSERS_PATH=/app/.cache/playwright

# 应用端口 & 健康检查
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://127.0.0.1:${SERVER_PORT}/api/health || exit 1

USER app

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar loganalysis.jar --server.port=$SERVER_PORT"]
