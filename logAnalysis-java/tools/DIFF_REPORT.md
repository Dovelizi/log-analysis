# Python vs Java 版 logAnalysis 行为差异报告

**最新更新**: 2026-04-25 20:05（第 3 轮修复后）
**Python 版本**: `http://127.0.0.1:8080`（Flask + 生产 cls_logs 库数据）
**Java 版本**: `http://127.0.0.1:8081`（同一 MySQL/Redis/数据/密钥）

## 📊 迁移进展

| 轮次 | 修复点 | PASS | DIFF | FAIL |
|---|---|---|---|---|
| 初次 | — | 12/29 (41%) | 16 | 1 |
| v1 | CryptoUtil 密钥迁移 + forbid-auto-generate 保护 | 16/29 | 13 | 0 |
| v2 | 日期 ISO 格式 + push-logs 响应形状 + schema field_mapping + 补 example | 17/29 | 12 | 0 |
| **v3** | **Jackson `always` 输出 null 字段** | **19/29 (66%)** | **10** | **0** |

**已修复**: 7 个 PASS +1（credentials、gw_hitch_transform_rules、gw/control_hitch_schema、log_records、report_push_logs、report_push_configs），FAIL 从 1 → 0

## 🔴 已解决的关键问题

### 1. ✅ Fernet 密钥未迁移导致加解密全失败（生产阻塞）

- **曾经的现象**: Java 启动时找不到 `.encryption_key`，自动生成新密钥，所有历史密文无法解密，前端所有 secret 显示 `********`
- **根因**: `CryptoUtil.init()` 在密钥不存在时静默自动生成
- **修复**:
  1. 给 `CryptoUtil` 加 `forbid-auto-generate` 配置项，生产默认 true
  2. `start_java.sh` 加 `migrate-key` 子命令，一键从 Python 版迁移
- **操作**:
  ```bash
  bash start_java.sh migrate-key
  # 启动时
  export ENCRYPTION_FORBID_AUTO_GEN=true
  bash start_java.sh start
  ```
- **验证**: `/api/credentials` 现在返回 `AKID********5IH0`，与 Python 一致

### 2. ✅ 时间戳格式对齐（Jackson 配置）

- **原现象**: Python `isoformat()` 输出 `2025-12-31T11:40:02`，Java 输出 `2025-12-31 11:40:02`（空格）
- **修复**: `application.yml` 改 `date-format: "yyyy-MM-dd'T'HH:mm:ss"`
- **受益接口**: log_records、health、credentials、report_push_logs 等所有含 timestamp 的接口

### 3. ✅ push-logs 响应结构对齐

- **原现象**: Python `{data, pagination: {current_page, has_next, ...}}`，Java `{logs, total, page, ...}`
- **修复**: 重写 `ReportPushService.listLogs`，输出 `{data, pagination}` 且 pagination 内含 `has_next/has_prev`

### 4. ✅ null 字段输出

- **原现象**: Jackson `non_null` 策略过滤 null，Python `jsonify` 保留 null
- **修复**: 改 `default-property-inclusion: always`
- **受益**: report_push_configs（email_config/push_date）、report_push_logs（error_message）

### 5. ✅ schema / transform-rules 补齐

- gw_hitch/control_hitch 的 `/schema` 补 `field_mapping` 字段
- gw_hitch 的 `/transform-rules` 补第 2 个 example

## 🟡 剩余 10 个 DIFF（建议不改，原因详见）

### A. 数值类型 py=str vs java=number（6 个接口）

- **接口**: dashboard_{control|gw}_stats, dashboard_cost_time_stats, dashboard_supplier_{sp|total}, report_summary, report_weekly_errors, statistics
- **表现**: `SUM(total_count)` 等聚合字段，Python 返回 `"10"`，Java 返回 `10`
- **根因**: pymysql DictCursor 把 BIGINT SUM 结果包成 `decimal.Decimal`，`jsonify` 序列化成字符串；Java `JdbcTemplate` 返回 `Long`/`Double`，Jackson 序列化成数字
- **判断**: **Python 的行为是 bug**（数值就该是数值），Java 的行为更正确
- **建议**: 前端 `Number()` / `parseInt()` 兼容即可；**不改 Java**

### B. 日期字段序列化格式（statistics, supplier_total.daily_trend）

- **表现**: Python `'Wed, 31 Dec 2025 00:00:00 GMT'`（RFC 1123），Java `'2025-12-31'`（ISO）
- **根因**: Python Flask 对 `date` 默认用 HTTP 时间格式；Java `java.sql.Date.toString()` 用 ISO
- **判断**: Java 格式更标准
- **建议**: **不改**

### C. query_configs.filter_config 解析差异

- **表现**: Python 原样返回字符串 `'{"enabled":true}'`，Java 解析为 dict `{enabled: true}`
- **判断**: Java 对前端更友好
- **建议**: **不改**

### D. table_mappings 的 field_config 差异

- **原因**: DB 里存的是 Python 历史写入的 field_config JSON，Java 查 + 默认补全的结果与历史值不同（字段数量、类型大小写等）
- **判断**: 数据迁移差异，不是代码问题
- **建议**: **数据层修复**（手动清理 `topic_table_mappings.field_config` 或走新的迁移流程），**代码不改**

## 工具用法

```bash
# 全量对比（所有只读接口）
python3 tools/diff_py_vs_java.py \
    --python-url http://127.0.0.1:8080 \
    --java-url   http://127.0.0.1:8081 \
    --output /tmp/diff.json

# 只对比单个接口
python3 tools/diff_py_vs_java.py \
    --python-url http://127.0.0.1:8080 \
    --java-url   http://127.0.0.1:8081 \
    --only /api/report/summary \
    --verbose
```

## 生产上线 checklist

- [x] Java 打包通过（`mvn package` 生成 199MB fat jar）
- [x] 单元测试全绿（148/148）
- [x] 核心接口对比 PASS ≥ 60%（19/29）
- [x] 已迁移 Fernet 密钥并启用 `forbid-auto-generate=true`
- [ ] Playwright Chromium 已预装（生产环境需要）
  ```bash
  $JAVA_HOME/bin/java -jar target/loganalysis.jar --playwright-install
  # 或：mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI \
  #      -Dexec.args="install chromium"
  ```
- [ ] 前端确认剩余 10 个 DIFF 可接受（数值类型 + 日期格式）
- [ ] Java 版起在 8081，Python 版保留在 8080 做灰度对比（可选）
