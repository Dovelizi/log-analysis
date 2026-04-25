#!/bin/bash
#
# 日志分析系统 - Docker快速启动脚本
# 用法: ./quick-start.sh [start|stop|restart|logs|clean]
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 检查Docker
check_docker() {
    if ! command -v docker &> /dev/null; then
        log_error "Docker未安装，请先安装Docker"
        exit 1
    fi
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        log_error "Docker Compose未安装"
        exit 1
    fi
}

# Docker Compose命令
docker_compose() {
    if docker compose version &> /dev/null; then
        docker compose "$@"
    else
        docker-compose "$@"
    fi
}

# 初始化环境
init_env() {
    if [[ ! -f .env ]]; then
        log_info "创建环境配置文件..."
        cp .env.example .env
        log_warn "请编辑 .env 文件配置数据库密码等信息"
    fi
}

# 启动服务
start() {
    log_info "启动日志分析系统..."
    check_docker
    init_env
    
    docker_compose up -d
    
    log_info "等待服务启动..."
    sleep 10
    
    # 检查服务状态
    if docker_compose ps | grep -q "Up"; then
        log_info "服务启动成功！"
        echo ""
        echo "访问地址: http://localhost:8080"
        echo ""
        echo "查看日志: $0 logs"
        echo "停止服务: $0 stop"
    else
        log_error "服务启动失败，请查看日志"
        docker_compose logs
    fi
}

# 启动生产环境（包含Nginx）
start_prod() {
    log_info "启动生产环境..."
    check_docker
    init_env
    
    docker_compose --profile production up -d
    
    log_info "等待服务启动..."
    sleep 15
    
    if docker_compose ps | grep -q "Up"; then
        log_info "生产环境启动成功！"
        echo ""
        echo "访问地址: http://localhost"
    else
        log_error "服务启动失败"
        docker_compose logs
    fi
}

# 停止服务
stop() {
    log_info "停止服务..."
    docker_compose down
    log_info "服务已停止"
}

# 重启服务
restart() {
    log_info "重启服务..."
    stop
    sleep 2
    start
}

# 查看日志
logs() {
    docker_compose logs -f --tail=100
}

# 查看状态
status() {
    docker_compose ps
}

# 清理数据
clean() {
    log_warn "此操作将删除所有数据，包括数据库！"
    read -p "确定要继续吗？(y/N) " confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        log_info "取消操作"
        exit 0
    fi
    
    docker_compose down -v
    log_info "所有数据已清理"
}

# 重建镜像
rebuild() {
    log_info "重建Docker镜像..."
    docker_compose build --no-cache
    log_info "镜像重建完成"
}

# 显示帮助
usage() {
    echo "日志分析系统 Docker管理脚本"
    echo ""
    echo "用法: $0 {command}"
    echo ""
    echo "命令:"
    echo "  start     - 启动服务（开发环境）"
    echo "  start-prod- 启动服务（生产环境，含Nginx）"
    echo "  stop      - 停止服务"
    echo "  restart   - 重启服务"
    echo "  status    - 查看服务状态"
    echo "  logs      - 查看实时日志"
    echo "  rebuild   - 重建Docker镜像"
    echo "  clean     - 清理所有数据（危险！）"
    echo ""
}

# 主入口
case "${1:-}" in
    start)
        start
        ;;
    start-prod)
        start_prod
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
    rebuild)
        rebuild
        ;;
    clean)
        clean
        ;;
    *)
        usage
        ;;
esac
