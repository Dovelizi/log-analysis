#!/usr/bin/env bash
# MySQL → ClickHouse 历史数据一次性迁移脚本
#
# 使用场景：
#   首次启用 ClickHouse 时，把 MySQL 已有的 5 张业务表数据灌到 CH。
#   之后的增量由 Processor 双写自动处理。
#
# 前置条件：
#   1. CH 数据库和表已创建（执行过 schema/clickhouse/*.sql）
#   2. 本机可访问 MySQL 和 ClickHouse
#   3. 装有 mysqldump 和 clickhouse-client
#
# 用法：
#   bash tools/migrate_mysql_to_ch.sh [table_name]
#   table_name 可选：gw_hitch_error_mothod / control_hitch_error_mothod /
#                   hitch_supplier_error_sp / hitch_supplier_error_total /
#                   hitch_control_cost_time
#   不传则迁移全部 5 张表。
#
# 环境变量：
#   MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DATABASE
#   CLICKHOUSE_HOST / CLICKHOUSE_PORT / CLICKHOUSE_USER / CLICKHOUSE_PASSWORD / CLICKHOUSE_DATABASE

set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
MYSQL_DATABASE="${MYSQL_DATABASE:-cls_logs}"

CH_HOST="${CLICKHOUSE_HOST:-127.0.0.1}"
CH_PORT="${CLICKHOUSE_PORT:-9000}"
CH_USER="${CLICKHOUSE_USER:-default}"
CH_PASSWORD="${CLICKHOUSE_PASSWORD:-}"
CH_DATABASE="${CLICKHOUSE_DATABASE:-cls_logs_ch}"

ALL_TABLES=(
  gw_hitch_error_mothod
  control_hitch_error_mothod
  hitch_supplier_error_sp
  hitch_supplier_error_total
  hitch_control_cost_time
)

TABLES=("${@:-${ALL_TABLES[@]}}")
DUMP_DIR="/tmp/ch_migrate_$$"
mkdir -p "$DUMP_DIR"
trap "rm -rf $DUMP_DIR" EXIT

for t in "${TABLES[@]}"; do
  echo "=============================================="
  echo "迁移 $t"
  echo "=============================================="

  # 1. 从 MySQL dump 成 TSV（ClickHouse 原生支持 TabSeparated 格式）
  echo "[1/3] MySQL dump → TSV"
  mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" \
        "$MYSQL_DATABASE" -B -N -e "SELECT * FROM $t" \
        > "$DUMP_DIR/$t.tsv"
  rows=$(wc -l < "$DUMP_DIR/$t.tsv")
  echo "    导出 $rows 行"

  # 2. 查询 CH 当前行数（用于对比）
  before=$(clickhouse-client -h "$CH_HOST" --port "$CH_PORT" -u "$CH_USER" \
           ${CH_PASSWORD:+--password "$CH_PASSWORD"} \
           --query "SELECT count() FROM $CH_DATABASE.$t" 2>/dev/null || echo 0)

  # 3. 灌入 ClickHouse
  echo "[2/3] 导入 ClickHouse（当前 $before 行）"
  clickhouse-client -h "$CH_HOST" --port "$CH_PORT" -u "$CH_USER" \
    ${CH_PASSWORD:+--password "$CH_PASSWORD"} \
    --query "INSERT INTO $CH_DATABASE.$t FORMAT TabSeparated" \
    < "$DUMP_DIR/$t.tsv"

  after=$(clickhouse-client -h "$CH_HOST" --port "$CH_PORT" -u "$CH_USER" \
          ${CH_PASSWORD:+--password "$CH_PASSWORD"} \
          --query "SELECT count() FROM $CH_DATABASE.$t")
  echo "[3/3] 完成：CH 行数 $before → $after（增量 $((after - before))）"
done

echo ""
echo "迁移全部完成。建议接着：OPTIMIZE TABLE 每张 CH 表以触发 ReplacingMergeTree 去重合并。"
