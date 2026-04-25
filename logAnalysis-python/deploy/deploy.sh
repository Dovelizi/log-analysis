#!/bin/bash
#
# 日志分析系统 - 生产环境部署脚本
# 用法: ./deploy.sh [install|update|uninstall]
#

set -e

# ==================== 配置区域 ====================
APP_NAME="logAnalysis"
APP_USER="${APP_USER:-www-data}"
APP_GROUP="${APP_GROUP:-www-data}"
INSTALL_DIR="${INSTALL_DIR:-/opt/logAnalysis}"
VENV_DIR="$INSTALL_DIR/venv"
LOG_DIR="/var/log/$APP_NAME"
CONFIG_DIR="/etc/$APP_NAME"
SYSTEMD_SERVICE="/etc/systemd/system/${APP_NAME}.service"

# 默认配置
DEFAULT_PORT=8080
DEFAULT_WORKERS=4

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ==================== 工具函数 ====================
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "此脚本需要root权限运行"
        log_info "请使用: sudo $0 $*"
        exit 1
    fi
}

check_os() {
    if [[ -f /etc/os-release ]]; then
        . /etc/os-release
        OS=$ID
        VERSION=$VERSION_ID
        log_info "检测到操作系统: $PRETTY_NAME"
    else
        log_error "无法检测操作系统类型"
        exit 1
    fi
}

# ==================== 安装依赖 ====================
install_dependencies() {
    log_step "安装系统依赖..."
    
    case $OS in
        ubuntu|debian)
            apt-get update
            apt-get install -y python3 python3-pip python3-venv \
                nginx supervisor \
                libmysqlclient-dev python3-dev build-essential
            ;;
        centos|rhel|rocky|almalinux)
            if [[ "$VERSION" == "7" ]]; then
                yum install -y epel-release
                yum install -y python3 python3-pip python3-devel \
                    nginx supervisor \
                    mysql-devel gcc
            else
                dnf install -y python3 python3-pip python3-devel \
                    nginx supervisor \
                    mysql-devel gcc
            fi
            ;;
        *)
            log_error "不支持的操作系统: $OS"
            exit 1
            ;;
    esac
    
    log_info "系统依赖安装完成"
}

# ==================== 创建用户和目录 ====================
setup_directories() {
    log_step "创建目录结构..."
    
    # 创建应用用户（如果不存在）
    if ! id "$APP_USER" &>/dev/null; then
        useradd -r -s /bin/false "$APP_USER" || true
        log_info "创建用户: $APP_USER"
    fi
    
    # 创建目录
    mkdir -p "$INSTALL_DIR"
    mkdir -p "$LOG_DIR"
    mkdir -p "$CONFIG_DIR"
    mkdir -p "$INSTALL_DIR/static"
    
    log_info "目录结构创建完成"
}

# ==================== 部署应用代码 ====================
deploy_code() {
    log_step "部署应用代码..."
    
    # 获取脚本所在目录（项目根目录）
    SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
    
    # 复制应用文件
    cp -r "$SCRIPT_DIR"/*.py "$INSTALL_DIR/" 2>/dev/null || true
    cp -r "$SCRIPT_DIR"/requirements.txt "$INSTALL_DIR/"
    cp -r "$SCRIPT_DIR"/config "$INSTALL_DIR/"
    cp -r "$SCRIPT_DIR"/models "$INSTALL_DIR/"
    cp -r "$SCRIPT_DIR"/routes "$INSTALL_DIR/"
    cp -r "$SCRIPT_DIR"/services "$INSTALL_DIR/"
    cp -r "$SCRIPT_DIR"/static "$INSTALL_DIR/"
    cp -r "$SCRIPT_DIR"/migrations "$INSTALL_DIR/" 2>/dev/null || true
    
    # 复制SQL初始化文件
    cp "$SCRIPT_DIR"/*.sql "$INSTALL_DIR/" 2>/dev/null || true
    
    log_info "应用代码部署完成"
}

# ==================== 创建虚拟环境 ====================
setup_virtualenv() {
    log_step "创建Python虚拟环境..."
    
    # 创建虚拟环境
    python3 -m venv "$VENV_DIR"
    
    # 升级pip
    "$VENV_DIR/bin/pip" install --upgrade pip
    
    # 安装依赖
    "$VENV_DIR/bin/pip" install -r "$INSTALL_DIR/requirements.txt"
    
    # 安装生产环境额外依赖
    "$VENV_DIR/bin/pip" install gunicorn pymysql redis
    
    log_info "Python虚拟环境创建完成"
}

# ==================== 创建环境配置文件 ====================
create_env_config() {
    log_step "创建环境配置文件..."
    
    cat > "$CONFIG_DIR/env.conf" << 'EOF'
# 日志分析系统环境配置
# 请根据实际情况修改以下配置

# 数据库配置
DB_TYPE=mysql
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=loganalysis
MYSQL_PASSWORD=your_password_here
MYSQL_DATABASE=cls_logs

# Redis配置
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DB=0
REDIS_ERROR_LIST_DB=1
REDIS_DASHBOARD_DB=2

# 应用配置
FLASK_ENV=production
FLASK_DEBUG=0

# 腾讯云CLS配置（可选）
# TENCENT_SECRET_ID=your_secret_id
# TENCENT_SECRET_KEY=your_secret_key
EOF

    chmod 600 "$CONFIG_DIR/env.conf"
    log_info "环境配置文件创建于: $CONFIG_DIR/env.conf"
    log_warn "请编辑配置文件，设置正确的数据库和Redis连接信息"
}

# ==================== 创建Gunicorn配置 ====================
create_gunicorn_config() {
    log_step "创建Gunicorn配置..."
    
    cat > "$CONFIG_DIR/gunicorn.conf.py" << EOF
# Gunicorn配置文件
import multiprocessing

# 绑定地址
bind = "127.0.0.1:$DEFAULT_PORT"

# 工作进程数
workers = $DEFAULT_WORKERS

# 工作模式
worker_class = "sync"

# 超时时间（秒）
timeout = 120

# 最大请求数（防止内存泄漏）
max_requests = 1000
max_requests_jitter = 50

# 日志配置
accesslog = "$LOG_DIR/access.log"
errorlog = "$LOG_DIR/error.log"
loglevel = "info"

# 进程名
proc_name = "$APP_NAME"

# 守护进程（由systemd管理，设为False）
daemon = False

# 优雅重启
graceful_timeout = 30

# 预加载应用
preload_app = True
EOF

    log_info "Gunicorn配置创建完成"
}

# ==================== 创建Systemd服务 ====================
create_systemd_service() {
    log_step "创建Systemd服务..."
    
    cat > "$SYSTEMD_SERVICE" << EOF
[Unit]
Description=Log Analysis System
Documentation=https://github.com/your-repo/logAnalysis
After=network.target mysql.service redis.service
Wants=mysql.service redis.service

[Service]
Type=notify
User=$APP_USER
Group=$APP_GROUP
WorkingDirectory=$INSTALL_DIR
EnvironmentFile=$CONFIG_DIR/env.conf
ExecStart=$VENV_DIR/bin/gunicorn -c $CONFIG_DIR/gunicorn.conf.py app:app
ExecReload=/bin/kill -s HUP \$MAINPID
ExecStop=/bin/kill -s TERM \$MAINPID
Restart=always
RestartSec=5
StandardOutput=append:$LOG_DIR/stdout.log
StandardError=append:$LOG_DIR/stderr.log

# 安全加固
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=$INSTALL_DIR $LOG_DIR

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    log_info "Systemd服务创建完成"
}

# ==================== 创建Nginx配置 ====================
create_nginx_config() {
    log_step "创建Nginx配置..."
    
    # 获取服务器IP
    SERVER_IP=$(hostname -I | awk '{print $1}')
    
    cat > "/etc/nginx/sites-available/$APP_NAME" << EOF
# 日志分析系统 Nginx配置
upstream loganalysis_backend {
    server 127.0.0.1:$DEFAULT_PORT;
    keepalive 32;
}

server {
    listen 80;
    server_name $SERVER_IP localhost;
    
    # 访问日志
    access_log /var/log/nginx/${APP_NAME}_access.log;
    error_log /var/log/nginx/${APP_NAME}_error.log;
    
    # 客户端请求体大小限制（用于文件上传）
    client_max_body_size 100M;
    
    # 静态文件
    location /static/ {
        alias $INSTALL_DIR/static/;
        expires 7d;
        add_header Cache-Control "public, immutable";
    }
    
    # API请求
    location / {
        proxy_pass http://loganalysis_backend;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Connection "";
        
        # 超时设置
        proxy_connect_timeout 60s;
        proxy_send_timeout 120s;
        proxy_read_timeout 120s;
        
        # 缓冲设置
        proxy_buffering on;
        proxy_buffer_size 4k;
        proxy_buffers 8 32k;
    }
    
    # 健康检查
    location /health {
        proxy_pass http://loganalysis_backend/api/health;
        access_log off;
    }
}
EOF

    # 创建符号链接
    if [[ -d /etc/nginx/sites-enabled ]]; then
        ln -sf "/etc/nginx/sites-available/$APP_NAME" "/etc/nginx/sites-enabled/$APP_NAME"
        # 删除默认配置
        rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true
    elif [[ -d /etc/nginx/conf.d ]]; then
        cp "/etc/nginx/sites-available/$APP_NAME" "/etc/nginx/conf.d/${APP_NAME}.conf"
    fi
    
    # 测试Nginx配置
    nginx -t
    
    log_info "Nginx配置创建完成"
}

# ==================== 设置权限 ====================
set_permissions() {
    log_step "设置文件权限..."
    
    chown -R "$APP_USER:$APP_GROUP" "$INSTALL_DIR"
    chown -R "$APP_USER:$APP_GROUP" "$LOG_DIR"
    chown -R root:root "$CONFIG_DIR"
    chmod 755 "$INSTALL_DIR"
    chmod 755 "$LOG_DIR"
    chmod 700 "$CONFIG_DIR"
    
    log_info "文件权限设置完成"
}

# ==================== 初始化数据库 ====================
init_database() {
    log_step "初始化数据库..."
    
    log_info "请确保MySQL服务已启动并创建了数据库"
    log_info "可以使用以下命令初始化数据库:"
    echo ""
    echo "  mysql -u root -p < $INSTALL_DIR/init_mysql.sql"
    echo ""
    log_warn "数据库初始化需要手动执行"
}

# ==================== 启动服务 ====================
start_services() {
    log_step "启动服务..."
    
    # 启动应用服务
    systemctl enable "$APP_NAME"
    systemctl start "$APP_NAME"
    
    # 重启Nginx
    systemctl enable nginx
    systemctl restart nginx
    
    # 检查服务状态
    sleep 2
    if systemctl is-active --quiet "$APP_NAME"; then
        log_info "应用服务启动成功"
    else
        log_error "应用服务启动失败，请检查日志: $LOG_DIR/error.log"
    fi
    
    if systemctl is-active --quiet nginx; then
        log_info "Nginx服务启动成功"
    else
        log_error "Nginx服务启动失败"
    fi
}

# ==================== 显示安装信息 ====================
show_install_info() {
    SERVER_IP=$(hostname -I | awk '{print $1}')
    
    echo ""
    echo "=============================================="
    echo -e "${GREEN}日志分析系统安装完成！${NC}"
    echo "=============================================="
    echo ""
    echo "访问地址: http://$SERVER_IP"
    echo ""
    echo "重要文件位置:"
    echo "  应用目录: $INSTALL_DIR"
    echo "  配置目录: $CONFIG_DIR"
    echo "  日志目录: $LOG_DIR"
    echo ""
    echo "服务管理命令:"
    echo "  启动服务: systemctl start $APP_NAME"
    echo "  停止服务: systemctl stop $APP_NAME"
    echo "  重启服务: systemctl restart $APP_NAME"
    echo "  查看状态: systemctl status $APP_NAME"
    echo "  查看日志: journalctl -u $APP_NAME -f"
    echo ""
    echo "下一步操作:"
    echo "  1. 编辑配置文件: vim $CONFIG_DIR/env.conf"
    echo "  2. 初始化数据库: mysql -u root -p < $INSTALL_DIR/init_mysql.sql"
    echo "  3. 重启服务: systemctl restart $APP_NAME"
    echo ""
}

# ==================== 更新应用 ====================
update_app() {
    log_step "更新应用..."
    
    # 停止服务
    systemctl stop "$APP_NAME" || true
    
    # 备份当前版本
    BACKUP_DIR="$INSTALL_DIR.backup.$(date +%Y%m%d%H%M%S)"
    cp -r "$INSTALL_DIR" "$BACKUP_DIR"
    log_info "已备份到: $BACKUP_DIR"
    
    # 部署新代码
    deploy_code
    
    # 更新依赖
    "$VENV_DIR/bin/pip" install -r "$INSTALL_DIR/requirements.txt"
    
    # 设置权限
    set_permissions
    
    # 启动服务
    systemctl start "$APP_NAME"
    
    log_info "应用更新完成"
}

# ==================== 卸载应用 ====================
uninstall_app() {
    log_step "卸载应用..."
    
    read -p "确定要卸载日志分析系统吗？(y/N) " confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        log_info "取消卸载"
        exit 0
    fi
    
    # 停止服务
    systemctl stop "$APP_NAME" 2>/dev/null || true
    systemctl disable "$APP_NAME" 2>/dev/null || true
    
    # 删除Systemd服务
    rm -f "$SYSTEMD_SERVICE"
    systemctl daemon-reload
    
    # 删除Nginx配置
    rm -f "/etc/nginx/sites-available/$APP_NAME"
    rm -f "/etc/nginx/sites-enabled/$APP_NAME"
    rm -f "/etc/nginx/conf.d/${APP_NAME}.conf"
    systemctl reload nginx 2>/dev/null || true
    
    # 备份数据
    BACKUP_DIR="/tmp/${APP_NAME}_backup_$(date +%Y%m%d%H%M%S)"
    mkdir -p "$BACKUP_DIR"
    cp -r "$CONFIG_DIR" "$BACKUP_DIR/" 2>/dev/null || true
    cp -r "$LOG_DIR" "$BACKUP_DIR/" 2>/dev/null || true
    log_info "配置和日志已备份到: $BACKUP_DIR"
    
    # 删除目录
    rm -rf "$INSTALL_DIR"
    rm -rf "$CONFIG_DIR"
    rm -rf "$LOG_DIR"
    
    log_info "卸载完成"
    log_warn "数据库数据未删除，请手动清理"
}

# ==================== 主入口 ====================
main() {
    case "${1:-install}" in
        install)
            check_root
            check_os
            install_dependencies
            setup_directories
            deploy_code
            setup_virtualenv
            create_env_config
            create_gunicorn_config
            create_systemd_service
            create_nginx_config
            set_permissions
            init_database
            start_services
            show_install_info
            ;;
        update)
            check_root
            update_app
            ;;
        uninstall)
            check_root
            uninstall_app
            ;;
        *)
            echo "用法: $0 {install|update|uninstall}"
            echo ""
            echo "命令说明:"
            echo "  install   - 安装应用（默认）"
            echo "  update    - 更新应用代码"
            echo "  uninstall - 卸载应用"
            exit 1
            ;;
    esac
}

main "$@"
