# schema/

按前缀字母顺序统一执行，建议部署时：

```bash
mysql -h$MYSQL_HOST -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE < 00_core_tables.sql
for f in $(ls *.sql | grep -v '^00_' | sort); do
  mysql -h$MYSQL_HOST -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE < $f
done
```

或使用 `start_java.sh` 的 `--init-db` 选项一次性初始化。

## 文件清单

| 文件 | 用途 |
|---|---|
| `00_core_tables.sql` | 核心配置表：api_credentials / log_topics / query_configs / log_records / analysis_results / topic_table_mappings / field_mappings / collection_logs |
| `gw_hitch_error_mothod.sql` | 网关顺风车错误聚合表 |
| `control_hitch_error_mothod.sql` | 顺风车错误方法监控表 |
| `hitch_supplier_error_sp.sql` | 供应商维度错误明细聚合表 |
| `hitch_supplier_error_total.sql` | 供应商维度错误汇总表 |
| `hitch_control_cost_time.sql` | 控制层方法耗时表 |
| `report_push_config.sql` | 推送配置 + 推送日志 + 错误日志插入记录表（3 张表） |
