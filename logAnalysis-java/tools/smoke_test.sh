#!/bin/bash
#
# 端到端烟雾测试：启动 Java 版 logAnalysis，探活所有只读核心路由，验收后停服务
#
# 用法:
#   bash tools/smoke_test.sh              # 默认配置跑
#   TEST_PORT=8082 bash tools/smoke_test.sh
#   VERBOSE=1 bash tools/smoke_test.sh    # 打印每个接口响应前 200 字符
#
# 退出码:
#   0 全部通过
#   1 有探活失败
#   2 启动/依赖问题
#

set -u

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_FILE="$APP_DIR/target/loganalysis.jar"
TEST_PORT="${TEST_PORT:-8082}"
VERBOSE="${VERBOSE:-0}"

# 环境变量（优先使用已有值）
: "${JAVA_HOME:=/data/home/lemolli/.local/opt/jdk-11.0.24+8}"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:=root}"
: "${MYSQL_PASSWORD:=123456}"
: "${MYSQL_DATABASE:=cls_logs}"
export JAVA_HOME MYSQL_HOST MYSQL_PORT MYSQL_USER MYSQL_PASSWORD MYSQL_DATABASE

JAVA="$JAVA_HOME/bin/java"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

LOG_FILE="$APP_DIR/logs/smoke-test.log"
mkdir -p "$APP_DIR/logs"

# ================= 前置检查 =================

check_prereq() {
    echo -e "${CYAN}[1/4] 前置检查...${NC}"
    local errors=0

    if [ ! -x "$JAVA" ]; then
        echo -e "${RED}  ✗ JDK 未找到: $JAVA${NC}"
        ((errors++))
    else
        echo -e "${GREEN}  ✓ JDK ok ($("$JAVA" -version 2>&1 | head -1))${NC}"
    fi

    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${RED}  ✗ jar 未构建: $JAR_FILE（请先 mvn -DskipTests package）${NC}"
        ((errors++))
    else
        local size=$(du -h "$JAR_FILE" | cut -f1)
        echo -e "${GREEN}  ✓ jar ok ($size)${NC}"
    fi

    # 测试端口是否空闲
    if command -v ss > /dev/null 2>&1; then
        if ss -tln | grep -q ":$TEST_PORT "; then
            echo -e "${RED}  ✗ 端口 $TEST_PORT 已被占用${NC}"
            ((errors++))
        else
            echo -e "${GREEN}  ✓ 端口 $TEST_PORT 空闲${NC}"
        fi
    fi

    # MySQL 连通
    if command -v mysql > /dev/null 2>&1; then
        if mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
                -e "SELECT 1" "$MYSQL_DATABASE" > /dev/null 2>&1; then
            echo -e "${GREEN}  ✓ MySQL ok${NC}"
        else
            echo -e "${RED}  ✗ MySQL 连接失败 ($MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE)${NC}"
            ((errors++))
        fi
    else
        echo -e "${YELLOW}  ? mysql 客户端未安装，跳过连通检查${NC}"
    fi

    if [ "$errors" -gt 0 ]; then
        echo -e "${RED}前置检查失败，退出${NC}"
        return 2
    fi
    return 0
}

# ================= 启动 =================

JAVA_PID=""

start_java() {
    echo -e "${CYAN}[2/4] 启动 Java 服务 on port $TEST_PORT ...${NC}"
    cd "$APP_DIR"
    nohup "$JAVA" -Xms256m -Xmx512m \
        -Dloganalysis.encryption.forbid-auto-generate=false \
        -jar "$JAR_FILE" \
        --server.port="$TEST_PORT" \
        --MYSQL_HOST="$MYSQL_HOST" --MYSQL_PORT="$MYSQL_PORT" \
        --MYSQL_USER="$MYSQL_USER" --MYSQL_PASSWORD="$MYSQL_PASSWORD" \
        --MYSQL_DATABASE="$MYSQL_DATABASE" \
        > "$LOG_FILE" 2>&1 &
    JAVA_PID=$!
    echo -e "${GREEN}  pid=$JAVA_PID, 日志: $LOG_FILE${NC}"

    # 等 health 返回 200（最多 45s）
    echo -n "  等待 Spring Boot 就绪"
    local waited=0
    while [ $waited -lt 45 ]; do
        if curl -sf --max-time 2 "http://127.0.0.1:$TEST_PORT/api/health" > /dev/null 2>&1; then
            echo
            echo -e "${GREEN}  ✓ 服务就绪（${waited}s）${NC}"
            return 0
        fi
        sleep 1
        ((waited++))
        echo -n "."
    done
    echo
    echo -e "${RED}  ✗ 启动超时（45s 内未就绪）${NC}"
    tail -20 "$LOG_FILE" | sed 's/^/    | /'
    return 2
}

# ================= 路由探活 =================

# 只读路由 & 期望 HTTP 状态
CASES=(
    "GET /api/health 200"
    "GET /api/credentials 200"
    "GET /api/topics 200"
    "GET /api/query-configs 200"
    "GET /api/dashboard/available-dates 200"
    "GET /api/dashboard/overview 200"
    "GET /api/dashboard/control-hitch/statistics 200"
    "GET /api/dashboard/gw-hitch/statistics 200"
    "GET /api/dashboard/hitch-control-cost-time/statistics 200"
    "GET /api/dashboard/hitch-supplier-error-sp/statistics 200"
    "GET /api/dashboard/hitch-supplier-error-total/statistics 200"
    "GET /api/gw-hitch/data 200"
    "GET /api/gw-hitch/schema 200"
    "GET /api/gw-hitch/transform-rules 200"
    "GET /api/gw-hitch/processor-types 200"
    "GET /api/control-hitch/data 200"
    "GET /api/control-hitch/schema 200"
    "GET /api/control-hitch/processor-types 200"
    "GET /api/table-mappings 200"
    "GET /api/table-mappings/field-types 200"
    "GET /api/table-mappings/filter-operators 200"
    "GET /api/table-mappings/transform-rules 200"
    "GET /api/scheduler/status 200"
    "GET /api/scheduler/push-status 200"
    "GET /api/report/push-configs 200"
    "GET /api/report/summary 200"
    "GET /api/report/weekly-new-errors 200"
    "GET /api/report/push-logs 200"
    "GET /api/statistics 200"
    "GET /api/analysis-results 200"
    "GET /api/log-records 200"
    # 一些 404 / 400 验证（确认错误处理正确）
    "GET /api/credentials/99999 404"
    "GET /api/table-mappings/99999 404"
)

PASS=0
FAIL=0
FAIL_DETAILS=()

run_smoke() {
    echo -e "${CYAN}[3/4] 探活 ${#CASES[@]} 个路由...${NC}"
    for line in "${CASES[@]}"; do
        local method=$(echo "$line" | awk '{print $1}')
        local path=$(echo "$line" | awk '{print $2}')
        local expect=$(echo "$line" | awk '{print $3}')
        local url="http://127.0.0.1:$TEST_PORT$path"

        local body_tmp=$(mktemp)
        local status=$(curl -s -X "$method" -o "$body_tmp" -w "%{http_code}" \
                       --max-time 30 "$url")

        if [ "$status" = "$expect" ]; then
            ((PASS++))
            local hint=""
            [ "$VERBOSE" = "1" ] && hint="  $(head -c 80 "$body_tmp" | tr -d '\n')"
            printf "  ${GREEN}✓ %3d${NC} %-6s %-60s$hint\n" "$status" "$method" "$path"
        else
            ((FAIL++))
            local snippet=$(head -c 160 "$body_tmp" | tr -d '\n')
            printf "  ${RED}✗ %3d${NC} %-6s %-60s  ${RED}(期望 %s)${NC}\n" "$status" "$method" "$path" "$expect"
            FAIL_DETAILS+=("$method $path: 期望 $expect, 实际 $status, body: $snippet")
        fi
        rm -f "$body_tmp"
    done
}

# ================= 停服 =================

stop_java() {
    echo -e "${CYAN}[4/4] 停止 Java 服务 (pid=$JAVA_PID) ...${NC}"
    if [ -n "$JAVA_PID" ] && kill -0 "$JAVA_PID" 2>/dev/null; then
        kill "$JAVA_PID" 2>/dev/null
        local waited=0
        while kill -0 "$JAVA_PID" 2>/dev/null && [ $waited -lt 10 ]; do
            sleep 1
            ((waited++))
        done
        if kill -0 "$JAVA_PID" 2>/dev/null; then
            kill -9 "$JAVA_PID" 2>/dev/null
            echo -e "${YELLOW}  强制停止${NC}"
        else
            echo -e "${GREEN}  已停止${NC}"
        fi
    else
        echo -e "${YELLOW}  进程不存在或已退出${NC}"
    fi
}

# ================= 总结 =================

print_summary() {
    local total=$((PASS + FAIL))
    echo
    echo "============================================================"
    if [ $FAIL -eq 0 ]; then
        echo -e "${GREEN}SMOKE TEST PASS: $PASS/$total 全部通过${NC}"
    else
        echo -e "${RED}SMOKE TEST FAIL: $FAIL/$total 失败, $PASS 通过${NC}"
        echo
        echo "失败明细："
        for d in "${FAIL_DETAILS[@]}"; do
            echo -e "  ${RED}$d${NC}"
        done
    fi
    echo "============================================================"
}

# ================= 主流程 =================

trap 'stop_java' EXIT INT TERM

check_prereq || exit $?
start_java || exit $?
run_smoke
print_summary

[ $FAIL -eq 0 ] && exit 0 || exit 1
