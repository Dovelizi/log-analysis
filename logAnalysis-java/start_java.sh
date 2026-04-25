#!/bin/bash

# logAnalysis Java 版服务管理脚本
# 用法: ./start_java.sh {start|stop|restart|status|logs|clean|init-db}
# 对齐原 service.sh 的运维习惯

APP_NAME="logAnalysis-java"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_FILE="$APP_DIR/target/loganalysis.jar"
PID_FILE="$APP_DIR/.app.pid"
LOG_FILE="$APP_DIR/logs/stdout.log"
PORT=8080

# 环境变量（可被外部覆盖）
: "${JAVA_HOME:=/data/home/lemolli/.local/opt/jdk-11.0.24+8}"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:=root}"
: "${MYSQL_PASSWORD:=123456}"
: "${MYSQL_DATABASE:=cls_logs}"
: "${JAVA_OPTS:=-Xms512m -Xmx1g -XX:+UseG1GC}"

export JAVA_HOME MYSQL_HOST MYSQL_PORT MYSQL_USER MYSQL_PASSWORD MYSQL_DATABASE

JAVA="$JAVA_HOME/bin/java"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

mkdir -p "$APP_DIR/logs"

get_pid() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            echo "$pid"
            return 0
        fi
    fi
    local pid=$(pgrep -f "loganalysis.jar" | head -1)
    if [ -n "$pid" ]; then
        echo "$pid"
        return 0
    fi
    return 1
}

check_port() {
    if command -v lsof > /dev/null 2>&1; then
        lsof -i:$PORT > /dev/null 2>&1
    elif command -v ss > /dev/null 2>&1; then
        ss -tuln | grep ":$PORT " > /dev/null 2>&1
    elif command -v netstat > /dev/null 2>&1; then
        netstat -tuln | grep ":$PORT " > /dev/null 2>&1
    else
        return 1
    fi
}

start() {
    echo -e "${YELLOW}正在启动 $APP_NAME ...${NC}"

    local pid=$(get_pid)
    if [ -n "$pid" ]; then
        echo -e "${YELLOW}服务已在运行中 (PID: $pid)${NC}"
        return 0
    fi

    if check_port; then
        echo -e "${RED}错误: 端口 $PORT 已被占用${NC}"
        return 1
    fi

    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${RED}未找到 $JAR_FILE，请先执行: mvn -DskipTests package${NC}"
        return 1
    fi

    if [ ! -x "$JAVA" ]; then
        echo -e "${RED}JDK 未找到: $JAVA${NC}"
        return 1
    fi

    cd "$APP_DIR"
    nohup "$JAVA" $JAVA_OPTS -jar "$JAR_FILE" \
        --server.port=$PORT \
        >> "$LOG_FILE" 2>&1 &
    local new_pid=$!
    echo "$new_pid" > "$PID_FILE"

    sleep 3
    if ps -p "$new_pid" > /dev/null 2>&1; then
        echo -e "${GREEN}服务启动成功 (PID: $new_pid)${NC}"
        echo -e "${GREEN}访问地址: http://localhost:$PORT${NC}"
        echo -e "${GREEN}日志文件: $LOG_FILE${NC}"
        return 0
    else
        echo -e "${RED}服务启动失败，请检查日志: $LOG_FILE${NC}"
        rm -f "$PID_FILE"
        return 1
    fi
}

stop() {
    echo -e "${YELLOW}正在停止 $APP_NAME ...${NC}"
    local pid=$(get_pid)
    if [ -z "$pid" ]; then
        echo -e "${YELLOW}服务未运行${NC}"
        rm -f "$PID_FILE"
        return 0
    fi

    kill "$pid" 2>/dev/null
    local count=0
    while ps -p "$pid" > /dev/null 2>&1; do
        sleep 1
        count=$((count + 1))
        if [ $count -ge 15 ]; then
            echo -e "${YELLOW}服务未响应，强制终止...${NC}"
            kill -9 "$pid" 2>/dev/null
            break
        fi
    done
    rm -f "$PID_FILE"
    echo -e "${GREEN}服务已停止${NC}"
}

restart() {
    stop
    sleep 1
    start
}

status() {
    local pid=$(get_pid)
    if [ -n "$pid" ]; then
        echo -e "${GREEN}服务运行中 (PID: $pid)${NC}"
        ps -p "$pid" -o pid,ppid,%cpu,%mem,etime,cmd --no-headers 2>/dev/null || true
        return 0
    else
        echo -e "${RED}服务未运行${NC}"
        return 1
    fi
}

logs() {
    if [ -f "$LOG_FILE" ]; then
        tail -n 100 -f "$LOG_FILE"
    else
        echo -e "${RED}日志文件不存在: $LOG_FILE${NC}"
        return 1
    fi
}

clean() {
    if [ -f "$LOG_FILE" ]; then
        > "$LOG_FILE"
        echo -e "${GREEN}日志已清理${NC}"
    fi
}

init_db() {
    echo -e "${YELLOW}执行数据库初始化脚本...${NC}"
    local schema_dir="$APP_DIR/src/main/resources/schema"
    if [ ! -d "$schema_dir" ]; then
        echo -e "${RED}schema 目录不存在: $schema_dir${NC}"
        return 1
    fi
    if ! command -v mysql > /dev/null 2>&1; then
        echo -e "${RED}未找到 mysql 客户端${NC}"
        return 1
    fi
    local mysql_args="-h$MYSQL_HOST -P$MYSQL_PORT -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE"
    # 00_ 开头优先执行
    for f in $(ls "$schema_dir"/*.sql 2>/dev/null | sort); do
        echo -e "${YELLOW}应用 $f${NC}"
        mysql $mysql_args < "$f" || {
            echo -e "${RED}$f 执行失败${NC}"
            return 1
        }
    done
    echo -e "${GREEN}数据库初始化完成${NC}"
}

# 从 Python 版迁移 Fernet 密钥（生产上线前必做，否则无法解密历史数据）
migrate_key() {
    local src="${1:-/data/workspace/logAnalysis/.encryption_key}"
    local dst="$APP_DIR/.encryption_key"
    echo -e "${YELLOW}迁移 Fernet 密钥: $src -> $dst${NC}"
    if [ ! -f "$src" ]; then
        echo -e "${RED}源密钥文件不存在: $src${NC}"
        echo -e "${YELLOW}用法: $0 migrate-key [source_key_path]${NC}"
        return 1
    fi
    if [ -f "$dst" ]; then
        echo -e "${YELLOW}目标文件已存在: $dst${NC}"
        if diff -q "$src" "$dst" > /dev/null 2>&1; then
            echo -e "${GREEN}内容一致，无需替换${NC}"
            return 0
        fi
        echo -e "${RED}内容不一致，请手动处理（先备份后删除目标再重试）${NC}"
        return 1
    fi
    cp "$src" "$dst"
    chmod 600 "$dst"
    echo -e "${GREEN}密钥已迁移并设置权限 600${NC}"
    echo -e "${YELLOW}建议启动时设置 ENCRYPTION_FORBID_AUTO_GEN=true 防止意外生成新密钥${NC}"
}

usage() {
    cat <<EOF
用法: $0 {start|stop|restart|status|logs|clean|init-db|migrate-key}

命令说明:
  start       启动服务
  stop        停止服务
  restart     重启服务
  status      查看服务状态
  logs        查看实时日志
  clean       清理日志
  init-db     执行 schema/*.sql 初始化数据库
  migrate-key 从 Python 版迁移 .encryption_key（用法: $0 migrate-key [source_path]）

环境变量（可覆盖）:
  JAVA_HOME                   默认: $JAVA_HOME
  MYSQL_HOST/PORT/USER/PASSWORD/DATABASE
  JAVA_OPTS                   默认: $JAVA_OPTS
  ENCRYPTION_FORBID_AUTO_GEN  设为 true 可防止自动生成新密钥（生产推荐）
EOF
}

case "$1" in
    start) start ;;
    stop) stop ;;
    restart) restart ;;
    status) status ;;
    logs) logs ;;
    clean) clean ;;
    init-db|init_db) init_db ;;
    migrate-key|migrate_key) migrate_key "$2" ;;
    *) usage; exit 1 ;;
esac

exit $?
