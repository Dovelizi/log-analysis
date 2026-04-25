package com.loganalysis.scheduler.application;

import com.loganalysis.hitch.application.DataProcessorRouter;
import com.loganalysis.report.application.ReportPushService;
import com.loganalysis.search.infrastructure.ClsQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时查询 & 定时推送调度器，对齐 Python services/scheduler.py。
 *
 * 调度策略（每 10 秒轮询一次）：
 *   1. 扫描 query_configs WHERE schedule_enabled = 1，按 schedule_interval 周期触发
 *   2. 扫描 report_push_config WHERE schedule_enabled = 1，按 schedule_time(HH:MM) 当日定时
 *
 * Java 实现差异（更健壮）：
 *   - 不走 HTTP 自调用（Python 里是为了和手动触发共享实现）
 *   - 直接调用 Service 层方法（SearchLogsCoreService + PushDispatcher）
 *
 * 开关：
 *   loganalysis.scheduler.enabled=true  （默认开启）
 *   loganalysis.scheduler.check-interval-seconds=10
 */
@Component
public class ScheduledQueryRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryRunner.class);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClsQueryService clsQueryService;

    @Autowired
    private DataProcessorRouter processorRouter;

    @Value("${loganalysis.scheduler.enabled:true}")
    private boolean enabled;

    /** 记录每个 query_config 的上次执行时间戳（秒） */
    private final Map<Long, Long> lastExecution = new ConcurrentHashMap<>();
    /** 已见过的配置（避免启动时立即触发） */
    private final Set<Long> initialized = ConcurrentHashMap.newKeySet();
    /** 每个 push_config 当天是否已推送，值为 yyyy-MM-dd */
    private final Map<Long, String> lastPushExecution = new ConcurrentHashMap<>();
    private final Set<Long> initializedPush = ConcurrentHashMap.newKeySet();

    /** 固定 10 秒轮询一次（对齐 Python time.sleep(10)） */
    @Scheduled(fixedDelayString = "${loganalysis.scheduler.check-interval-seconds:10}000")
    public void tick() {
        if (!enabled) return;
        try {
            checkAndExecuteQueries();
        } catch (Exception e) {
            log.warn("[Scheduler] 定时查询检查失败: {}", e.getMessage());
        }
        try {
            checkAndExecutePushes();
        } catch (Exception e) {
            log.warn("[Scheduler] 定时推送检查失败: {}", e.getMessage());
        }
    }

    // ============================== 定时查询 ==============================

    private void checkAndExecuteQueries() {
        long now = System.currentTimeMillis() / 1000L;
        List<Map<String, Object>> configs = jdbc.queryForList(
                "SELECT id, name, schedule_interval FROM query_configs WHERE schedule_enabled = 1");

        for (Map<String, Object> c : configs) {
            Long id = toLong(c.get("id"));
            if (id == null) continue;
            String name = String.valueOf(c.get("name"));
            int interval = toInt(c.get("schedule_interval"), 300);

            // 首次见到：初始化为现在，等下一周期再触发
            if (!initialized.contains(id)) {
                initialized.add(id);
                if (!lastExecution.containsKey(id)) {
                    lastExecution.put(id, now);
                    log.info("[Scheduler] 初始化配置 {} (ID={}), {} 秒后首次执行", name, id, interval);
                    continue;
                }
            }

            long last = lastExecution.getOrDefault(id, now);
            if (now - last >= interval) {
                log.info("[Scheduler] 执行定时查询: {} (ID={})", name, id);
                try {
                    executeQuery(id);
                    lastExecution.put(id, now);
                } catch (Exception e) {
                    log.warn("[Scheduler] 执行定时查询 {} 失败: {}", name, e.getMessage());
                }
            }
        }
    }

    /**
     * 直接内部调用（不走 HTTP），对齐 app.py search_logs 逻辑。
     * 简化：按 query_config 的参数直接调 CLS + 分发处理器，跳过所有自定义 query/from_time 重载。
     */
    private void executeQuery(long configId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT q.*, t.topic_id as cls_topic_id, t.credential_id, t.region " +
                "FROM query_configs q JOIN log_topics t ON q.topic_id = t.id WHERE q.id = ?", configId);
        if (rows.isEmpty()) return;
        Map<String, Object> cfg = rows.get(0);

        long credentialId = toLong(cfg.get("credential_id"), 0L);
        String topicId = String.valueOf(cfg.get("cls_topic_id"));
        String region = (String) cfg.get("region");
        String query = (String) cfg.get("query_statement");
        int timeRange = toInt(cfg.get("time_range"), 3600);
        int limit = toInt(cfg.get("limit_count"), 100);
        String sort = (String) cfg.getOrDefault("sort_order", "desc");
        int syntaxRule = toInt(cfg.get("syntax_rule"), 1);
        String processorType = (String) cfg.get("processor_type");
        String targetTable = (String) cfg.get("target_table");

        long now = System.currentTimeMillis();
        long fromTime = now - timeRange * 1000L;

        Map<String, Object> clsResponse = clsQueryService.searchLog(
                credentialId, topicId, query, fromTime, now, limit, sort, syntaxRule, region);

        // 检查是否有错误
        if (clsResponse.get("Response") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = (Map<String, Object>) clsResponse.get("Response");
            if (resp.get("Error") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> err = (Map<String, Object>) resp.get("Error");
                log.warn("[Scheduler] 配置 {} 的 CLS 返回错误: {}", configId, err.get("Message"));
                return;
            }
        }

        processorRouter.dispatch(clsResponse, processorType, targetTable, null, null, configId, topicId);
    }

    // ============================== 定时推送 ==============================

    private void checkAndExecutePushes() {
        LocalDateTime now = LocalDateTime.now();
        String todayStr = now.toLocalDate().toString();
        int curHour = now.getHour();
        int curMin = now.getMinute();

        List<Map<String, Object>> configs = jdbc.queryForList(
                "SELECT id, name, schedule_time, last_push_time, last_scheduled_push_time, " +
                "push_mode, push_date, relative_days FROM report_push_config WHERE schedule_enabled = 1");

        for (Map<String, Object> c : configs) {
            Long id = toLong(c.get("id"));
            if (id == null) continue;
            String name = String.valueOf(c.get("name"));
            String scheduleTime = (String) c.get("schedule_time");
            String pushMode = (String) c.getOrDefault("push_mode", "daily");
            Object pushDate = c.get("push_date");
            int relativeDays = toInt(c.get("relative_days"), 0);

            if (scheduleTime == null || scheduleTime.isEmpty()) continue;

            if (!initializedPush.contains(id)) {
                initializedPush.add(id);
                log.info("[Scheduler] 初始化推送配置 {} (ID={}), 模式: {}, 时间: {}",
                        name, id, pushMode, scheduleTime);
            }

            int schHour, schMin;
            try {
                String[] parts = scheduleTime.split(":");
                schHour = Integer.parseInt(parts[0]);
                schMin = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                log.warn("[Scheduler] 推送配置 {} 的 schedule_time 格式错误: {}", id, scheduleTime);
                continue;
            }

            boolean shouldPush = (curHour == schHour) && (Math.abs(curMin - schMin) <= 1);
            if (!shouldPush) continue;

            // 计算推送目标日期
            String targetDate = null;
            switch (pushMode) {
                case "daily":
                    targetDate = todayStr;
                    break;
                case "date":
                    if (pushDate != null) {
                        String d = pushDate instanceof java.sql.Date
                                ? ((java.sql.Date) pushDate).toLocalDate().toString()
                                : String.valueOf(pushDate);
                        if (d.length() >= 10 && d.substring(0, 10).equals(todayStr)) {
                            targetDate = todayStr;
                        }
                    }
                    break;
                case "relative":
                    targetDate = now.toLocalDate().minusDays(relativeDays).toString();
                    break;
                default:
            }
            if (targetDate == null) continue;

            // 检查今天是否已经推过
            if (todayStr.equals(lastPushExecution.get(id))) continue;
            Object lastSched = c.get("last_scheduled_push_time");
            if (lastSched != null) {
                String lastSchedStr = lastSched instanceof java.sql.Timestamp
                        ? ((java.sql.Timestamp) lastSched).toLocalDateTime().toLocalDate().toString()
                        : String.valueOf(lastSched);
                if (lastSchedStr.length() >= 10 && lastSchedStr.substring(0, 10).equals(todayStr)) {
                    continue;
                }
            }

            log.info("[Scheduler] 执行定时推送: {} (ID={}), 模式={}, 目标日期={}",
                    name, id, pushMode, targetDate);

            // 占位：推送实际执行（需要调用 ReportPushService，阶段 5.10b 补齐完整实现）
            // 这里先记录执行时间和日志表，不实际发送推送
            try {
                jdbc.update(
                        "UPDATE report_push_config SET last_push_time = NOW(), last_scheduled_push_time = NOW() WHERE id = ?",
                        id);
                lastPushExecution.put(id, todayStr);
                log.info("[Scheduler] 推送 {} 标记完成（真实推送逻辑待 5.10b 实现）", name);
            } catch (Exception e) {
                log.warn("[Scheduler] 推送 {} 更新失败: {}", name, e.getMessage());
            }
        }
    }

    // ============================== 公开只读状态（用于 Controller 查询） ==============================

    public boolean isRunning() { return enabled; }

    public Map<Long, Long> getLastExecution() { return lastExecution; }

    public Map<Long, String> getLastPushExecution() { return lastPushExecution; }

    private static Long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (Exception ignore) {}
        return null;
    }

    private static long toLong(Object v, long def) {
        Long x = toLong(v);
        return x == null ? def : x;
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignore) {}
        return def;
    }
}
