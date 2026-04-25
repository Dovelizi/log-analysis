# schema/

按前缀字母顺序统一执行，建议部署时：

```bash
mysql -h$MYSQL_HOST -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE < 00_core_tables.sql
for f in $(ls *.sql | grep -v '^00_' | sort); do
  mysql -h$MYSQL_HOST -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE < $f
done
```

或使用 `start_java.sh` 的 `--init-db` 选项一次性初始化。

## MySQL 文件清单

| 文件 | 用途 |
|---|---|
| `00_core_tables.sql` | 核心配置表：api_credentials / log_topics / query_configs / log_records / analysis_results / topic_table_mappings / field_mappings / collection_logs |
| `gw_hitch_error_mothod.sql` | 网关顺风车错误聚合表 |
| `control_hitch_error_mothod.sql` | 顺风车错误方法监控表 |
| `hitch_supplier_error_sp.sql` | 供应商维度错误明细聚合表 |
| `hitch_supplier_error_total.sql` | 供应商维度错误汇总表 |
| `hitch_control_cost_time.sql` | 控制层方法耗时表 |
| `report_push_config.sql` | 推送配置 + 推送日志 + 错误日志插入记录表（3 张表） |
| `ch_writeback_queue.sql` | **二期新增**：ClickHouse 双写补偿队列（仅 `CLICKHOUSE_ENABLED=true` 时使用） |

## ClickHouse 文件清单（`clickhouse/` 子目录）

> ⚠️ 仅在启用 `CLICKHOUSE_ENABLED=true` 时执行；详见 [`../../../README.md` 的"启用 ClickHouse"章节](../../../README.md#启用-clickhouse可选)。

```bash
# 1. 创建 CH 数据库
clickhouse-client --multiquery < clickhouse/00_database.sql

# 2. 创建 5 张业务表（任意顺序）
for f in clickhouse/*.sql; do
    [ "$(basename $f)" != "00_database.sql" ] && clickhouse-client --multiquery < "$f"
done

# 3. 在 MySQL 主库创建补偿表
mysql -h$MYSQL_HOST -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE < ch_writeback_queue.sql
```

| 文件 | 用途 |
|---|---|
| `clickhouse/00_database.sql` | 创建 `cls_logs_ch` 数据库 |
| `clickhouse/gw_hitch_error_mothod.sql` | CH 镜像（ReplacingMergeTree + 按 event_date 分区） |
| `clickhouse/control_hitch_error_mothod.sql` | 同上（error_code 为 String） |
| `clickhouse/hitch_supplier_error_sp.sql` | 同上（含 sp_id + sp_name） |
| `clickhouse/hitch_supplier_error_total.sql` | 同上（无 sp_name） |
| `clickhouse/hitch_control_cost_time.sql` | CH 镜像（普通 MergeTree，每条独立 INSERT） |

## 历史数据迁移

启用 ClickHouse 时，把 MySQL 已有数据一次性灌到 CH：

```bash
MYSQL_HOST=... MYSQL_PASSWORD=... \
CLICKHOUSE_HOST=... CLICKHOUSE_PASSWORD=... \
bash ../../../tools/migrate_mysql_to_ch.sh

# 验证
clickhouse-client -q "SELECT count() FROM cls_logs_ch.gw_hitch_error_mothod"
mysql -e "SELECT COUNT(*) FROM gw_hitch_error_mothod" cls_logs
# 期望两边一致
```
