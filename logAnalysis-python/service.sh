#!/bin/bash

# 日志分析服务管理脚本
# 用法: ./service.sh {start|stop|restart|status}

APP_NAME="logAnalysis"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_FILE="app.py"
PID_FILE="$APP_DIR/.app.pid"
LOG_FILE="$APP_DIR/app.log"
PORT=8080

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 获取进程PID
get_pid() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            echo "$pid"
            return 0
        fi
    fi
    # 尝试通过进程名查找
    local pid=$(pgrep -f "python3.*$APP_FILE" | head -1)
    if [ -n "$pid" ]; then
        echo "$pid"
        return 0
    fi
    return 1
}

# 检查端口是否被占用
check_port() {
    if command -v lsof > /dev/null 2>&1; then
        lsof -i:$PORT > /dev/null 2>&1
        return $?
    elif command -v netstat > /dev/null 2>&1; then
        netstat -tuln | grep ":$PORT " > /dev/null 2>&1
        return $?
    elif command -v ss > /dev/null 2>&1; then
        ss -tuln | grep ":$PORT " > /dev/null 2>&1
        return $?
    fi
    return 1
}

# 启动服务
start() {
    echo -e "${YELLOW}正在启动 $APP_NAME 服务...${NC}"
    
    local pid=$(get_pid)
    if [ -n "$pid" ]; then
        echo -e "${YELLOW}服务已在运行中 (PID: $pid)${NC}"
        return 0
    fi
    
    if check_port; then
        echo -e "${RED}错误: 端口 $PORT 已被占用${NC}"
        return 1
    fi
    
    cd "$APP_DIR"
    nohup python3 "$APP_FILE" >> "$LOG_FILE" 2>&1 &
    local new_pid=$!
    echo "$new_pid" > "$PID_FILE"
    
    # 等待服务启动
    sleep 2
    
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

# 停止服务
stop() {
    echo -e "${YELLOW}正在停止 $APP_NAME 服务...${NC}"
    
    local pid=$(get_pid)
    if [ -z "$pid" ]; then
        echo -e "${YELLOW}服务未运行${NC}"
        rm -f "$PID_FILE"
        return 0
    fi
    
    kill "$pid" 2>/dev/null
    
    # 等待进程结束
    local count=0
    while ps -p "$pid" > /dev/null 2>&1; do
        sleep 1
        count=$((count + 1))
        if [ $count -ge 10 ]; then
            echo -e "${YELLOW}服务未响应，强制终止...${NC}"
            kill -9 "$pid" 2>/dev/null
            break
        fi
    done
    
    rm -f "$PID_FILE"
    echo -e "${GREEN}服务已停止${NC}"
    return 0
}

# 重启服务
restart() {
    echo -e "${YELLOW}正在重启 $APP_NAME 服务...${NC}"
    stop
    sleep 1
    start
}

# 查看服务状态
status() {
    local pid=$(get_pid)
    if [ -n "$pid" ]; then
        echo -e "${GREEN}服务运行中 (PID: $pid)${NC}"
        echo -e "访问地址: http://localhost:$PORT"
        echo -e "日志文件: $LOG_FILE"
        
        # 显示进程信息
        if command -v ps > /dev/null 2>&1; then
            echo -e "\n进程信息:"
            ps -p "$pid" -o pid,ppid,%cpu,%mem,etime,cmd --no-headers 2>/dev/null
        fi
        return 0
    else
        echo -e "${RED}服务未运行${NC}"
        return 1
    fi
}

# 查看日志
logs() {
    if [ -f "$LOG_FILE" ]; then
        echo -e "${YELLOW}显示最近100行日志 (Ctrl+C 退出):${NC}"
        tail -n 100 -f "$LOG_FILE"
    else
        echo -e "${RED}日志文件不存在: $LOG_FILE${NC}"
        return 1
    fi
}

# 清理日志
clean() {
    echo -e "${YELLOW}正在清理日志文件...${NC}"
    if [ -f "$LOG_FILE" ]; then
        > "$LOG_FILE"
        echo -e "${GREEN}日志已清理${NC}"
    else
        echo -e "${YELLOW}日志文件不存在${NC}"
    fi
}

# 显示帮助
usage() {
    echo "用法: $0 {start|stop|restart|status|logs|clean}"
    echo ""
    echo "命令说明:"
    echo "  start   - 启动服务"
    echo "  stop    - 停止服务"
    echo "  restart - 重启服务"
    echo "  status  - 查看服务状态"
    echo "  logs    - 查看实时日志"
    echo "  clean   - 清理日志文件"
    echo ""
    echo "示例:"
    echo "  $0 start    # 启动服务"
    echo "  $0 restart  # 重启服务"
    echo "  $0 logs     # 查看日志"
}

# 主入口
case "$1" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        restart
        ;;
    status)
        status
        ;;
    logs)
        logs
        ;;
    clean)
        clean
        ;;
    *)
        usage
        exit 1
        ;;
esac

exit $?
