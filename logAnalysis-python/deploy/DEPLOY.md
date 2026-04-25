# 日志分析系统部署指南

## 目录

- [系统要求](#系统要求)
- [快速开始](#快速开始)
- [Docker部署](#docker部署)
- [传统部署](#传统部署)
- [配置说明](#配置说明)
- [运维管理](#运维管理)
- [故障排查](#故障排查)

---

## 系统要求

### 硬件要求

| 配置项 | 最低要求 | 推荐配置 |
|--------|----------|----------|
| CPU | 2核 | 4核+ |
| 内存 | 4GB | 8GB+ |
| 磁盘 | 20GB | 50GB+ SSD |

### 软件要求

| 组件 | 版本要求 |
|------|----------|
| Python | 3.9+ |
| MySQL | 5.7+ / 8.0 |
| Redis | 6.0+ |
| Nginx | 1.18+ |

### 端口要求

| 端口 | 用途 |
|------|------|
| 80 | HTTP访问（Nginx） |
| 443 | HTTPS访问（可选） |
| 8080 | 应用服务 |
| 3306 | MySQL数据库 |
| 6379 | Redis缓存 |

---

## 快速开始

### 方式一：Docker Compose（推荐）

最快速的部署方式，自动配置所有依赖服务。

```bash
# 1. 进入部署目录
cd logAnalysis/deploy

# 2. 复制环境配置
cp .env.example .env

# 3. 编辑配置（设置数据库密码等）
vim .env

# 4. 启动服务
./quick-start.sh start

# 5. 访问系统
# http://localhost:8080
```

### 方式二：传统部署

适用于已有MySQL和Redis环境的服务器。

```bash
# 1. 进入部署目录
cd logAnalysis/deploy

# 2. 执行安装脚本（需要root权限）
sudo ./deploy.sh install

# 3. 编辑配置文件
sudo vim /etc/logAnalysis/env.conf

# 4. 初始化数据库
mysql -u root -p < /opt/logAnalysis/init_mysql.sql

# 5. 重启服务
sudo systemctl restart logAnalysis
```

---

## Docker部署

### 前置条件

- Docker 20.10+
- Docker Compose 2.0+

### 安装Docker（如未安装）

```bash
# Ubuntu/Debian
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# CentOS/RHEL
sudo yum install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable docker
sudo systemctl start docker
```

### 部署步骤

#### 1. 准备配置文件

```bash
cd logAnalysis/deploy
cp .env.example .env
```

#### 2. 编辑环境变量

```bash
vim .env
```

配置示例：
```env
# MySQL配置
MYSQL_ROOT_PASSWORD=your_secure_root_password
MYSQL_PASSWORD=your_app_password

# Redis配置（可选）
REDIS_PASSWORD=your_redis_password

# 应用配置
GUNICORN_WORKERS=4
LOG_LEVEL=info
```

#### 3. 启动服务

```bash
# 开发环境（不含Nginx）
./quick-start.sh start

# 生产环境（含Nginx反向代理）
./quick-start.sh start-prod
```

#### 4. 验证部署

```bash
# 检查服务状态
./quick-start.sh status

# 查看日志
./quick-start.sh logs

# 健康检查
curl http://localhost:8080/api/health
```

### Docker命令参考

```bash
# 启动服务
./quick-start.sh start

# 停止服务
./quick-start.sh stop

# 重启服务
./quick-start.sh restart

# 查看日志
./quick-start.sh logs

# 重建镜像
./quick-start.sh rebuild

# 清理数据（危险！）
./quick-start.sh clean
```

---

## 传统部署

### 支持的操作系统

- Ubuntu 20.04/22.04
- Debian 10/11
- CentOS 7/8
- Rocky Linux 8/9
- AlmaLinux 8/9

### 部署步骤

#### 1. 安装依赖

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install -y python3 python3-pip python3-venv \
    mysql-server redis-server nginx

# CentOS/RHEL
sudo yum install -y python3 python3-pip \
    mysql-server redis nginx
```

#### 2. 配置MySQL

```bash
# 启动MySQL
sudo systemctl enable mysql
sudo systemctl start mysql

# 安全配置
sudo mysql_secure_installation

# 创建数据库和用户
mysql -u root -p
```

```sql
CREATE DATABASE cls_logs CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'loganalysis'@'localhost' IDENTIFIED BY 'your_password';
GRANT ALL PRIVILEGES ON cls_logs.* TO 'loganalysis'@'localhost';
FLUSH PRIVILEGES;
```

#### 3. 配置Redis

```bash
sudo systemctl enable redis
sudo systemctl start redis
```

#### 4. 执行部署脚本

```bash
cd logAnalysis/deploy
sudo chmod +x deploy.sh
sudo ./deploy.sh install
```

#### 5. 配置环境变量

```bash
sudo vim /etc/logAnalysis/env.conf
```

```conf
# 数据库配置
DB_TYPE=mysql
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USER=loganalysis
MYSQL_PASSWORD=your_password
MYSQL_DATABASE=cls_logs

# Redis配置
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
```

#### 6. 初始化数据库

```bash
mysql -u loganalysis -p cls_logs < /opt/logAnalysis/init_mysql.sql
```

#### 7. 启动服务

```bash
sudo systemctl restart logAnalysis
sudo systemctl restart nginx
```

---

## 配置说明

### 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| DB_TYPE | 数据库类型 | mysql |
| MYSQL_HOST | MySQL主机 | localhost |
| MYSQL_PORT | MySQL端口 | 3306 |
| MYSQL_USER | MySQL用户 | root |
| MYSQL_PASSWORD | MySQL密码 | - |
| MYSQL_DATABASE | 数据库名 | cls_logs |
| REDIS_HOST | Redis主机 | localhost |
| REDIS_PORT | Redis端口 | 6379 |
| REDIS_PASSWORD | Redis密码 | - |
| REDIS_DB | Redis DB编号 | 0 |
| FLASK_ENV | 运行环境 | production |
| GUNICORN_WORKERS | 工作进程数 | CPU核心数*2+1 |

### Gunicorn配置

配置文件位置：`/etc/logAnalysis/gunicorn.conf.py`

```python
# 绑定地址
bind = "127.0.0.1:8080"

# 工作进程数
workers = 4

# 超时时间
timeout = 120

# 日志配置
accesslog = "/var/log/logAnalysis/access.log"
errorlog = "/var/log/logAnalysis/error.log"
loglevel = "info"
```

### Nginx配置

配置文件位置：`/etc/nginx/sites-available/logAnalysis`

主要配置项：
- 反向代理到8080端口
- 静态文件缓存7天
- 客户端请求体限制100MB
- 请求超时120秒

---

## 运维管理

### 服务管理

```bash
# Systemd命令
sudo systemctl start logAnalysis    # 启动
sudo systemctl stop logAnalysis     # 停止
sudo systemctl restart logAnalysis  # 重启
sudo systemctl status logAnalysis   # 状态
sudo systemctl enable logAnalysis   # 开机自启

# 查看日志
sudo journalctl -u logAnalysis -f           # 实时日志
sudo tail -f /var/log/logAnalysis/error.log # 错误日志
```

### 日志管理

日志文件位置：
- 应用日志：`/var/log/logAnalysis/`
- Nginx日志：`/var/log/nginx/`

日志轮转配置：
```bash
sudo vim /etc/logrotate.d/loganalysis
```

```conf
/var/log/logAnalysis/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    create 0640 www-data www-data
    postrotate
        systemctl reload logAnalysis > /dev/null 2>&1 || true
    endscript
}
```

### 备份策略

#### 数据库备份

```bash
# 手动备份
mysqldump -u loganalysis -p cls_logs > backup_$(date +%Y%m%d).sql

# 定时备份（crontab）
0 2 * * * mysqldump -u loganalysis -p'password' cls_logs | gzip > /backup/cls_logs_$(date +\%Y\%m\%d).sql.gz
```

#### 配置备份

```bash
tar -czvf config_backup.tar.gz /etc/logAnalysis/
```

### 更新应用

```bash
# 传统部署
cd logAnalysis/deploy
sudo ./deploy.sh update

# Docker部署
cd logAnalysis/deploy
git pull
./quick-start.sh rebuild
./quick-start.sh restart
```

---

## 故障排查

### 常见问题

#### 1. 服务启动失败

```bash
# 检查日志
sudo journalctl -u logAnalysis -n 50

# 检查端口占用
sudo lsof -i:8080
sudo netstat -tlnp | grep 8080

# 检查配置文件
cat /etc/logAnalysis/env.conf
```

#### 2. 数据库连接失败

```bash
# 测试MySQL连接
mysql -h localhost -u loganalysis -p cls_logs -e "SELECT 1"

# 检查MySQL状态
sudo systemctl status mysql

# 检查用户权限
mysql -u root -p -e "SHOW GRANTS FOR 'loganalysis'@'localhost'"
```

#### 3. Redis连接失败

```bash
# 测试Redis连接
redis-cli ping

# 检查Redis状态
sudo systemctl status redis
```

#### 4. Nginx 502错误

```bash
# 检查应用是否运行
sudo systemctl status logAnalysis

# 检查Nginx配置
sudo nginx -t

# 查看Nginx错误日志
sudo tail -f /var/log/nginx/logAnalysis_error.log
```

### 健康检查

```bash
# API健康检查
curl -s http://localhost:8080/api/health | jq

# 预期返回
{
  "status": "healthy",
  "timestamp": "2026-01-05T10:00:00",
  "version": "1.0.0",
  "checks": {
    "database": "ok",
    "redis": "ok"
  }
}
```

### 性能调优

#### 1. 增加工作进程

```bash
# 编辑Gunicorn配置
sudo vim /etc/logAnalysis/gunicorn.conf.py
# 修改 workers = CPU核心数 * 2 + 1

sudo systemctl restart logAnalysis
```

#### 2. MySQL优化

```sql
-- 查看慢查询
SHOW VARIABLES LIKE 'slow_query%';

-- 优化表
OPTIMIZE TABLE cls_logs.gw_hitch_error_logs;
```

#### 3. Redis内存优化

```bash
# 查看内存使用
redis-cli INFO memory

# 设置最大内存
redis-cli CONFIG SET maxmemory 1gb
redis-cli CONFIG SET maxmemory-policy allkeys-lru
```

---

## 安全建议

1. **修改默认密码**：部署后立即修改MySQL和Redis密码
2. **防火墙配置**：仅开放必要端口（80/443）
3. **HTTPS配置**：生产环境建议配置SSL证书
4. **定期更新**：保持系统和依赖包最新
5. **日志监控**：配置日志告警，及时发现异常

---

## 技术支持

如遇问题，请检查：
1. 日志文件：`/var/log/logAnalysis/`
2. 系统日志：`journalctl -u logAnalysis`
3. 健康检查：`curl http://localhost:8080/api/health`
