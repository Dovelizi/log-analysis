#!/bin/bash
#
# Docker 构建 + 容器启动 + smoke test 的全流程脚本。
# 在任意装有 Docker daemon 的机器上跑：
#
#     bash docker/build_and_test.sh
#
# 可选环境变量：
#   IMAGE_TAG         默认 loganalysis-java:smoke
#   CONTAINER_NAME    默认 loganalysis-java-smoke
#   HOST_PORT         默认 18080 (避开常用 8080)
#   MYSQL_HOST        默认 host.docker.internal (Linux 下可能需要自己加 --add-host)
#   MYSQL_PORT / USER / PASSWORD / DATABASE
#   SKIP_BUILD=1      跳过 docker build（复用已有 image）
#

set -eu

cd "$(dirname "$0")/.."

: "${IMAGE_TAG:=loganalysis-java:smoke}"
: "${CONTAINER_NAME:=loganalysis-java-smoke}"
: "${HOST_PORT:=18080}"
: "${MYSQL_HOST:=host.docker.internal}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:=root}"
: "${MYSQL_PASSWORD:=123456}"
: "${MYSQL_DATABASE:=cls_logs}"
: "${SKIP_BUILD:=0}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

cleanup() {
    echo -e "${YELLOW}[cleanup] 停止并删除容器 $CONTAINER_NAME${NC}"
    docker stop "$CONTAINER_NAME" 2>/dev/null || true
    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ========== 1. 前置检查 ==========
echo -e "${CYAN}[1/4] 前置检查${NC}"
docker version > /dev/null 2>&1 || {
    echo -e "${RED}Docker daemon 不可用${NC}"
    exit 2
}
docker info > /dev/null 2>&1 || {
    echo -e "${RED}docker info 失败，daemon 未就绪${NC}"
    exit 2
}
echo -e "${GREEN}  ✓ Docker daemon 可用${NC}"

# 端口占用检查
if command -v ss >/dev/null 2>&1 && ss -tln | grep -q ":$HOST_PORT "; then
    echo -e "${RED}  ✗ HOST_PORT=$HOST_PORT 已被占用，换一个（HOST_PORT=18081 ./$0）${NC}"
    exit 2
fi
echo -e "${GREEN}  ✓ 主机端口 $HOST_PORT 空闲${NC}"

# ========== 2. 构建 ==========
if [ "$SKIP_BUILD" = "1" ] && docker image inspect "$IMAGE_TAG" >/dev/null 2>&1; then
    echo -e "${CYAN}[2/4] 跳过构建（SKIP_BUILD=1，image 已存在）${NC}"
else
    echo -e "${CYAN}[2/4] 构建镜像 $IMAGE_TAG${NC}"
    docker build -t "$IMAGE_TAG" . || {
        echo -e "${RED}  ✗ 构建失败${NC}"
        exit 1
    }
fi
docker image ls "$IMAGE_TAG" --format "  image: {{.ID}} {{.Size}}"

# ========== 3. 启动容器 ==========
echo -e "${CYAN}[3/4] 启动容器 $CONTAINER_NAME${NC}"
# Linux 需要 --add-host 让容器能访问宿主机 MySQL（macOS/Windows 有内置 host.docker.internal）
EXTRA_HOST_ARGS=""
if [ "$MYSQL_HOST" = "host.docker.internal" ] && [ "$(uname -s)" = "Linux" ]; then
    EXTRA_HOST_ARGS="--add-host=host.docker.internal:host-gateway"
fi

# 密钥文件挂载（如果存在）
VOLUME_ARGS=""
if [ -f "./.encryption_key" ]; then
    VOLUME_ARGS="-v $(pwd)/.encryption_key:/app/.encryption_key:ro"
    echo -e "${GREEN}  ✓ 已挂载 .encryption_key${NC}"
else
    echo -e "${YELLOW}  ! 未找到 .encryption_key，容器将以 ENCRYPTION_FORBID_AUTO_GEN=true 启动，凭证解密会失败${NC}"
fi

docker run -d --name "$CONTAINER_NAME" \
    -p "$HOST_PORT":8080 \
    -e MYSQL_HOST="$MYSQL_HOST" \
    -e MYSQL_PORT="$MYSQL_PORT" \
    -e MYSQL_USER="$MYSQL_USER" \
    -e MYSQL_PASSWORD="$MYSQL_PASSWORD" \
    -e MYSQL_DATABASE="$MYSQL_DATABASE" \
    -e ENCRYPTION_FORBID_AUTO_GEN=false \
    $EXTRA_HOST_ARGS \
    $VOLUME_ARGS \
    "$IMAGE_TAG" > /dev/null

# 等待健康检查 OK
echo -n "  等待容器就绪"
waited=0
while [ $waited -lt 60 ]; do
    if curl -sf --max-time 2 "http://127.0.0.1:$HOST_PORT/api/health" > /dev/null 2>&1; then
        echo
        echo -e "${GREEN}  ✓ 容器就绪（${waited}s）${NC}"
        break
    fi
    sleep 2
    waited=$((waited + 2))
    echo -n "."
done
if [ $waited -ge 60 ]; then
    echo
    echo -e "${RED}  ✗ 容器启动超时（60s）${NC}"
    echo -e "${YELLOW}  容器日志：${NC}"
    docker logs "$CONTAINER_NAME" | tail -30 | sed 's/^/    | /'
    exit 1
fi

# ========== 4. Smoke test ==========
echo -e "${CYAN}[4/4] 跑 smoke test 路由集${NC}"
CASES=(
    "GET /api/health 200"
    "GET /api/credentials 200"
    "GET /api/topics 200"
    "GET /api/dashboard/overview 200"
    "GET /api/gw-hitch/schema 200"
    "GET /api/control-hitch/schema 200"
    "GET /api/table-mappings 200"
    "GET /api/scheduler/status 200"
    "GET /api/report/push-configs 200"
    "GET /api/report/summary 200"
    "GET /api/statistics 200"
    "GET /api/credentials/99999 404"
)

pass=0
fail=0
for line in "${CASES[@]}"; do
    method=$(echo "$line" | awk '{print $1}')
    path=$(echo "$line" | awk '{print $2}')
    expect=$(echo "$line" | awk '{print $3}')
    status=$(curl -s -X "$method" -o /dev/null -w "%{http_code}" \
             --max-time 10 "http://127.0.0.1:$HOST_PORT$path")
    if [ "$status" = "$expect" ]; then
        printf "  ${GREEN}✓ %3d${NC} %-6s %s\n" "$status" "$method" "$path"
        pass=$((pass + 1))
    else
        printf "  ${RED}✗ %3d${NC} %-6s %-50s  ${RED}(期望 %s)${NC}\n" "$status" "$method" "$path" "$expect"
        fail=$((fail + 1))
    fi
done

echo
echo "============================================================"
total=$((pass + fail))
if [ $fail -eq 0 ]; then
    echo -e "${GREEN}DOCKER SMOKE TEST PASS: $pass/$total${NC}"
    echo "  image: $IMAGE_TAG"
    echo "  container: $CONTAINER_NAME (运行中，exit 时自动清理)"
    echo "  可以访问: http://127.0.0.1:$HOST_PORT/api/health"
    exit 0
else
    echo -e "${RED}DOCKER SMOKE TEST FAIL: $fail/$total${NC}"
    echo -e "${YELLOW}  查看容器日志：docker logs $CONTAINER_NAME${NC}"
    exit 1
fi
