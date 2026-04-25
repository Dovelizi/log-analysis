#!/bin/bash
#
# 日志分析系统 - 生产环境简易启动脚本
# 使用本机MySQL，Gunicorn多进程运行
#

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_DIR"

# 颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# 配置
PORT=${PORT:-8080}
WORKERS=${WORKERS:-4}
PID_FILE="$APP_DIR/.gunicorn.pid"
LOG_FILE="$APP_DIR/gunicorn.log"

case "$1" in
    start)
        if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
            echo -e "${YELLOW}服务已在运行中${NC}"
            exit 0
        fi
        
        echo -e "${GREEN}启动生产环境服务...${NC}"
        
        # 初始化数据库表
        python3 -c "from app import init_db, init_mysql_database; init_mysql_database(); init_db()"
        
        # 启动Gunicorn
        gunicorn \
            --bind 0.0.0.0:$PORT \
            --workers $WORKERS \
            --timeout 120 \
            --access-logfile "$APP_DIR/access.log" \
            --error-logfile "$LOG_FILE" \
            --pid "$PID_FILE" \
            --daemon \
            app:app
        
        sleep 2
        if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
            echo -e "${GREEN}服务启动成功！${NC}"
            echo -e "访问地址: http://localhost:$PORT"
            echo -e "进程PID: $(cat $PID_FILE)"
        else
            echo -e "${RED}启动失败，请查看日志: $LOG_FILE${NC}"
        fi
        ;;
        
    stop)
        if [ -f "$PID_FILE" ]; then
            echo -e "${YELLOW}停止服务...${NC}"
            kill $(cat "$PID_FILE") 2>/dev/null
            rm -f "$PID_FILE"
            echo -e "${GREEN}服务已停止${NC}"
        else
            echo -e "${YELLOW}服务未运行${NC}"
        fi
        ;;
        
    restart)
        $0 stop
        sleep 1
        $0 start
        ;;
        
    status)
        if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
            echo -e "${GREEN}服务运行中 (PID: $(cat $PID_FILE))${NC}"
            echo "访问地址: http://localhost:$PORT"
        else
            echo -e "${RED}服务未运行${NC}"
        fi
        ;;
        
    logs)
        tail -f "$LOG_FILE"
        ;;
        
    *)
        echo "用法: $0 {start|stop|restart|status|logs}"
        echo ""
        echo "环境变量:"
        echo "  PORT     - 监听端口 (默认: 8080)"
        echo "  WORKERS  - 工作进程数 (默认: 4)"
        echo ""
        echo "MySQL配置环境变量:"
        echo "  MYSQL_HOST, MYSQL_PORT, MYSQL_USER, MYSQL_PASSWORD, MYSQL_DATABASE"
        ;;
esac
