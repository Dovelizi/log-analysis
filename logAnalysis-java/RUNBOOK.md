# logAnalysis-java 运维手册

> **目标读者**：运维人员 / SRE / 上线执行人
> **前提**：你已经拿到这份代码（`/data/workspace/logAnalysis-java/`）和构建好的 jar（`target/loganalysis.jar`）
> **迁移前提**：Python 版已在 8080 端口运行，生产库 `cls_logs` 已就绪

---

## 目录

1. [快速开始（5 分钟 local 试跑）](#快速开始)
2. [正式上线流程（推荐分阶段切流）](#正式上线流程)
3. [灰度对比验证](#灰度对比验证)
4. [监控与报警](#监控与报警)
5. [回滚](#回滚)
6. [故障排查（FAQ）](#故障排查)
7. [配置清单](#配置清单)

---

## 快速开始

**适用场景**：首次接触项目，本机试跑一下确认没问题。

### 前置依赖

| 组件 | 版本 | 必需 |
|---|---|---|
| JDK | 11（Temurin 推荐） | ✅ |
| MySQL | 8.0+ | ✅ |
| Redis | 5.0+ | ✅（处理器聚合用） |
| Playwright Chromium | （首次运行自动下载）| 截图功能必需 |

### 一键启动（5 步）

```bash
# 1. 激活 Java 环境
source /data/home/lemolli/.local/opt/envrc
cd /data/workspace/logAnalysis-java

# 2. 迁移 Fernet 密钥（只做一次）
bash start_java.sh migrate-key
# 如果提示目标已存在且不一致：
#   先备份 mv .encryption_key .encryption_key.backup
#   再重跑 migrate-key

# 3. 初始化数据库表（如果是全新库）
# ⚠️ 生产环境如果已经在用 Python 版的库，跳过这步
bash start_java.sh init-db

# 4. 启动（默认 8080，注意与 Python 版错开）
export ENCRYPTION_FORBID_AUTO_GEN=true
bash start_java.sh start

# 5. 验证健康
curl http://localhost:8080/api/health
# 期望：{"status":"healthy","checks":{"database":"ok","redis":"ok"}}
```

### 跑完整烟雾测试

```bash
# 会自动启动另一个实例在 8082 跑 33 个路由再停掉
bash tools/smoke_test.sh
# 期望：SMOKE TEST PASS: 33/33 全部通过
```

---

## 正式上线流程

**关键原则**：**Python 版不停机**，Java 版先在其他端口起来，灰度比对后再切流。

### 阶段 1：部署准备（不影响线上）

```bash
# 1. 在目标服务器放好 jar
scp target/loganalysis.jar user@prod-server:/opt/loganalysis-java/
scp start_java.sh tools/smoke_test.sh tools/diff_py_vs_java.py user@prod-server:/opt/loganalysis-java/
scp -r src/main/resources/schema user@prod-server:/opt/loganalysis-java/

# 2. 登录服务器，确认 JDK
ssh user@prod-server
cd /opt/loganalysis-java
java -version  # 必须 11+

# 3. 迁移密钥（假设 Python 版在 /opt/loganalysis/）
bash start_java.sh migrate-key /opt/loganalysis/.encryption_key

# 4. 验证密钥可用（启动时不报"解密失败"即可）
export MYSQL_HOST=... MYSQL_PASSWORD=...
export ENCRYPTION_FORBID_AUTO_GEN=true
bash start_java.sh start
curl http://localhost:8080/api/credentials | jq '.[] | .secret_id_masked'
# 期望：出现 "AKID***..." 形式，不是纯 "********"
# 如果是纯 ********，说明密钥没迁成功，立即停下！
```

**🚨 停止条件**：
- `api/credentials` 返回的 masked 都是 `********` → 密钥未迁成功，回到 `migrate-key` 步骤
- `/api/health` 返回 503 → 查 `logs/stdout.log` 看连接错误

### 阶段 2：灰度启动（Java 版在 8081）

```bash
# 修改 start_java.sh 或直接命令行参数起在 8081
bash start_java.sh stop
PORT=8081 nohup java $JAVA_OPTS \
    -Dloganalysis.encryption.forbid-auto-generate=true \
    -jar target/loganalysis.jar \
    --server.port=8081 \
    > logs/stdout.log 2>&1 &

# 确认 Python 版 (8080) 和 Java 版 (8081) 并行运行
curl http://localhost:8080/api/health
curl http://localhost:8081/api/health
```

### 阶段 3：灰度对比验证

```bash
# 在同一服务器跑 diff 工具（需要 python3）
python3 tools/diff_py_vs_java.py \
    --python-url http://127.0.0.1:8080 \
    --java-url   http://127.0.0.1:8081 \
    --output /tmp/diff-$(date +%Y%m%d-%H%M).json

# 期望输出：合计: 29   ✅ PASS=19   ⚠️ DIFF=10   ❌ FAIL=0
```

**接受标准**：
- `FAIL = 0`（**强制**，有 FAIL 不准切流）
- `PASS >= 18`（不强制，但低于此数需要人工核对）
- 剩余 DIFF 全部在 `tools/DIFF_REPORT.md` 的"🟡 剩余 10 个 DIFF"范围内

### 阶段 4：切流（两种方式二选一）

#### 方式 A：反向代理切流（Nginx 推荐）

```nginx
# /etc/nginx/conf.d/loganalysis.conf
upstream loganalysis {
    server 127.0.0.1:8080 weight=9;   # Python（旧）
    server 127.0.0.1:8081 weight=1;   # Java（新）—— 10% 流量
}

server {
    listen 80;
    location / {
        proxy_pass http://loganalysis;
    }
}

# 观察 1 小时，确认 Java 版无异常后提升到 50%
# 再观察 2 小时，最终切到 100%
```

#### 方式 B：直接切端口（简单粗暴）

```bash
# 1. 停 Python
systemctl stop loganalysis-py  # 或 kill -TERM $(pgrep -f 'python.*app.py')
# 2. 改 Java 版端口为 8080
bash start_java.sh stop
bash start_java.sh start   # 默认就是 8080
# 3. 验证
curl http://localhost:8080/api/health
```

**观察时段**：
- **前 1 小时**：每 5 分钟手动验证 `/api/health`
- **前 4 小时**：持续盯日志 `tail -f logs/loganalysis.log`
- **24 小时后**：对比 Python 版 MySQL 写入量和 Java 版是否一致（比如 `SELECT DATE(create_time), COUNT(*) FROM gw_hitch_error_mothod GROUP BY 1`）

### 阶段 5：配置定时任务 systemd（可选，推荐）

```ini
# /etc/systemd/system/loganalysis-java.service
[Unit]
Description=logAnalysis Java version
After=network.target mysql.service redis.service

[Service]
Type=simple
User=loganalysis
WorkingDirectory=/opt/loganalysis-java
Environment="JAVA_HOME=/usr/lib/jvm/temurin-11"
Environment="MYSQL_HOST=127.0.0.1"
Environment="MYSQL_PASSWORD=**REPLACE**"
Environment="ENCRYPTION_FORBID_AUTO_GEN=true"
Environment="JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC"
ExecStart=/bin/bash /opt/loganalysis-java/start_java.sh start
ExecStop=/bin/bash /opt/loganalysis-java/start_java.sh stop
Restart=on-failure
RestartSec=10s

[Install]
WantedBy=multi-user.target
```

启用：
```bash
systemctl daemon-reload
systemctl enable loganalysis-java
systemctl start loganalysis-java
systemctl status loganalysis-java
```

---

## 灰度对比验证

**目的**：上线后持续确认 Java 版和 Python 版行为一致。

### 定时 diff（cron）

```bash
# /etc/cron.d/loganalysis-diff
# 每小时跑一次（只读接口，不会污染数据）
0 * * * * loganalysis /opt/loganalysis-java/tools/diff_py_vs_java.py \
    --python-url http://127.0.0.1:8080 \
    --java-url   http://127.0.0.1:8081 \
    --output /var/log/loganalysis/diff-$(date +\%H).json \
    >> /var/log/loganalysis/diff.log 2>&1
```

### 写接口数据一致性

灰度期间，每天跑一次：

```sql
-- 对比两侧的 5 张业务表的当天入库量
SELECT 'gw_hitch' as table_name, COUNT(*) FROM gw_hitch_error_mothod WHERE DATE(create_time)=CURDATE()
UNION ALL
SELECT 'control_hitch', COUNT(*) FROM control_hitch_error_mothod WHERE DATE(create_time)=CURDATE()
UNION ALL
SELECT 'supplier_sp', COUNT(*) FROM hitch_supplier_error_sp WHERE DATE(create_time)=CURDATE()
UNION ALL
SELECT 'supplier_total', COUNT(*) FROM hitch_supplier_error_total WHERE DATE(create_time)=CURDATE()
UNION ALL
SELECT 'cost_time', COUNT(*) FROM hitch_control_cost_time WHERE DATE(create_time)=CURDATE();
```

**接受标准**：切流后 24 小时内，同一张表的新增量与切流前 7 日均值偏差 < 30%。

---

## 监控与报警

### 关键指标

| 指标 | 阈值 | 告警动作 |
|---|---|---|
| `/api/health` HTTP 状态 | != 200 持续 1 min | P1 告警 |
| JVM heap 使用率 | > 85% 持续 5 min | P2 告警 |
| MySQL 连接数（HikariCP 活跃） | > 15 持续 5 min | P2 |
| Redis 连接失败 | 任意 | P2 |
| CLS 调用失败率 | > 10% / 10min | P1 |
| `@Scheduled` 延迟 | > 60s | P2 |

### Prometheus 抓取（可选）

Spring Boot 自带 actuator 端点，改 `application.yml`：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, prometheus
```

再加 pom 依赖：
```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

抓取 `http://127.0.0.1:8080/actuator/prometheus`。

### 日志

位置：`logs/loganalysis.log`（按天滚动，保留 30 天）

关键错误模式：
```
grep -E "ERROR|Exception" logs/loganalysis.log | tail -50
grep "解密失败" logs/loganalysis.log   # 密钥问题
grep "CLS API错误" logs/loganalysis.log # CLS 权限或凭证问题
```

---

## 回滚

### 场景 1：Java 版严重异常，马上切回 Python

```bash
# 如果用 Nginx 切流，改 weight 即可（不重启 Java，保留现场用于排查）
vim /etc/nginx/conf.d/loganalysis.conf
#   upstream loganalysis {
#       server 127.0.0.1:8080 weight=10;   # 全量给 Python
#       server 127.0.0.1:8081 weight=0;    # Java 下线
#   }
nginx -s reload

# 如果直接切了端口
bash start_java.sh stop
systemctl start loganalysis-py     # 恢复 Python
curl http://localhost:8080/api/health  # 验证
```

### 场景 2：数据异常，需要清理 Java 版入库的数据

灰度期内，Java 版和 Python 版**写的是同一张表**。回滚数据需要按时间范围删除：

```sql
-- 仅示例，实操前先 SELECT COUNT 确认范围
DELETE FROM gw_hitch_error_mothod WHERE create_time >= '2026-04-25 10:00:00' AND create_time < '2026-04-25 14:00:00';
-- 重复 5 张表...
```

⚠️ **更稳妥的做法**：灰度期不直接混写，而是用不同的 MySQL 账号或 schema（需前期规划）。

---

## 故障排查

### Q1: 启动时报 `密钥文件不存在且已禁用自动生成`

**原因**：`ENCRYPTION_FORBID_AUTO_GEN=true` 但没迁密钥。

**解决**：
```bash
bash start_java.sh migrate-key
# 或手动
cp /opt/loganalysis/.encryption_key /opt/loganalysis-java/.encryption_key
chmod 600 .encryption_key
```

### Q2: `/api/credentials` 返回的 masked 全是 `********`

**原因**：密钥不匹配。Java 用的密钥和加密时用的不一样。

**解决**：
```bash
# 确认 Python 版和 Java 版的密钥内容一致
diff /opt/loganalysis/.encryption_key /opt/loganalysis-java/.encryption_key
# 如果不一致：
bash start_java.sh stop
rm /opt/loganalysis-java/.encryption_key
bash start_java.sh migrate-key
bash start_java.sh start
```

### Q3: `/api/report/screenshot` 返回 `Playwright 未安装浏览器`

**原因**：Playwright Chromium 二进制没下载。

**解决**：
```bash
# 容器外
$JAVA_HOME/bin/java -classpath $(ls ~/.m2/repository/com/microsoft/playwright/playwright/*/*.jar) \
    com.microsoft.playwright.CLI install chromium

# 容器内（Dockerfile 已安装运行依赖，但二进制首次运行才下载）
docker exec -it loganalysis-java java -cp /app/loganalysis.jar \
    org.springframework.boot.loader.JarLauncher \
    --playwright-install   # 目前未实现此参数，需进容器手动跑上面那条
```

**替代方案**：如果运维环境禁止外网访问，可在构建 image 时提前拉镜像 `PLAYWRIGHT_BROWSERS_PATH` 目录下的 Chromium 并 ADD 进去。

### Q4: MySQL 连接数爆满（HikariCP 耗尽）

**原因**：`@Scheduled` 任务和主流量同时打 DB，连接数不够。

**解决**：改 `application.yml`：
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30        # 默认 20，根据业务量调大
      connection-timeout: 30000
```

### Q5: Redis 挂了，服务要不要重启？

**不用**。Redis 只用作聚合缓存，`RedisCacheService.isAvailable()` 有保护，挂了会退化到"每次查 MySQL"。但会导致**入库性能下降**（每次 UPSERT 都要查 MySQL）。

### Q6: `/api/search-logs` 超时

**原因**：
1. CLS 侧慢（Python 版也会慢，不是 Java 的问题）
2. MySQL 写入慢（看 processor 的批量聚合日志）

**排查**：
```bash
# 看最后一次 search-logs 的耗时
grep "search-logs" logs/loganalysis.log | tail -3
# 如果 Java 里有 processor 写库慢，看 INSERT 耗时
grep "INSERT INTO.*gw_hitch" logs/loganalysis.log | tail -5
```

### Q7: 定时任务不跑

**原因**：
1. `loganalysis.scheduler.enabled=false`（默认 true，一般不会关）
2. 数据库里 `query_configs.schedule_enabled=1` 的配置数量是 0

**排查**：
```bash
curl http://localhost:8080/api/scheduler/status
# 看 running 和 enabled_count
```

---

## 配置清单

### 环境变量（通过 `export` 或 systemd `Environment=` 设置）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MYSQL_HOST` | localhost | MySQL 主机 |
| `MYSQL_PORT` | 3306 | MySQL 端口 |
| `MYSQL_USER` | root | MySQL 用户 |
| `MYSQL_PASSWORD` | 123456 | MySQL 密码（**必须改**） |
| `MYSQL_DATABASE` | cls_logs | 数据库名 |
| `REDIS_HOST` | 127.0.0.1 | Redis 主机 |
| `REDIS_PORT` | 6379 | Redis 端口 |
| `REDIS_PASSWORD` | （空） | Redis 密码 |
| `REDIS_DB` | 0 | Redis DB 编号 |
| `ENCRYPTION_KEY_FILE` | .encryption_key | Fernet 密钥文件路径 |
| `ENCRYPTION_FORBID_AUTO_GEN` | false | **生产必设为 true** |
| `JAVA_OPTS` | -Xms512m -Xmx1g -XX:+UseG1GC | JVM 启动参数 |

### 端口占用

| 服务 | 端口 |
|---|---|
| logAnalysis-java（Spring Boot） | 8080（默认） |
| MySQL | 3306 |
| Redis | 6379 |

### 文件路径

| 路径 | 说明 |
|---|---|
| `.encryption_key` | Fernet 密钥（**权限 600**，与 Python 版同一把） |
| `logs/loganalysis.log` | 应用日志（按天滚动保留 30 天） |
| `logs/stdout.log` | Spring Boot stdout（start_java.sh 管理） |
| `.app.pid` | PID 文件（start_java.sh 维护） |

---

## 快速命令参考

```bash
# 服务管理
bash start_java.sh start         # 启动
bash start_java.sh stop          # 停止
bash start_java.sh restart       # 重启
bash start_java.sh status        # 状态
bash start_java.sh logs          # 实时日志
bash start_java.sh clean         # 清理日志
bash start_java.sh init-db       # 初始化数据库
bash start_java.sh migrate-key   # 迁移密钥

# 测试工具
bash tools/smoke_test.sh                                # 端到端烟雾测试
python3 tools/diff_py_vs_java.py \                      # Python vs Java 行为对比
    --python-url http://localhost:8080 \
    --java-url   http://localhost:8081

# Docker（如果用容器部署）
bash docker/build_and_test.sh    # 一键构建 + 启容器 + 测试

# 调试
curl http://localhost:8080/api/health                     # 健康检查
curl http://localhost:8080/api/scheduler/status           # 调度器状态
tail -f logs/loganalysis.log                              # 实时日志
grep -E "ERROR|Exception" logs/loganalysis.log | tail -50 # 最近错误
```

---

## 联系与记录

**首次上线前**必做：
- [ ] 在 staging 环境按 [阶段 1-3] 跑一遍
- [ ] 确认 `tools/DIFF_REPORT.md` 里列出的 10 个 DIFF 前端可接受
- [ ] 确认 `ENCRYPTION_FORBID_AUTO_GEN=true`
- [ ] systemd unit 文件写好并测试过 `systemctl start/stop`
- [ ] 回滚剧本演练过一次（上线后 5 分钟内能切回 Python）

**上线记录模板**：
```
上线时间：
执行人：
上线方式（切流/直切）：
前置检查通过：
灰度 diff 结果：PASS = ____/29
观察 1 小时指标：
观察 24 小时指标：
剩余 DIFF 前端确认：
回滚预案验证：
```
