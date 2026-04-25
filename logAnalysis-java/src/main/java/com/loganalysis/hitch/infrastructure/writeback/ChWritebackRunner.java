package com.loganalysis.hitch.infrastructure.writeback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalysis.common.config.ClickHouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse 双写补偿任务。
 *
 * 激活条件（ConditionalOnProperty）：
 *   loganalysis.clickhouse.enabled=true 且 loganalysis.clickhouse.writeback.enabled=true
 *
 * 执行逻辑：
 *   - {@link #replay()} 每 {@code replay-interval-seconds} 秒执行一次（默认 30s）
 *   - 从 ch_writeback_queue 拉最多 batch-size（默认 200）条到期任务
 *   - 每条任务反序列化 payload_json，调用 {@link ChDualWriter#doWrite}（同步路径）
 *   - 成功：删除行；失败：retry_count+1，next_retry_at 指数退避
 *   - 积压超过 alert-threshold：WARN 日志（可扩展接企微）
 *
 * 指数退避：next_retry_at = NOW() + 2^retry_count 分钟，封顶 1 小时
 */
@Component
@ConditionalOnProperty(
        prefix = "loganalysis.clickhouse",
        name = {"enabled", "writeback.enabled"},
        havingValue = "true")
public class ChWritebackRunner {

    private static final Logger log = LoggerFactory.getLogger(ChWritebackRunner.class);

    /** 最长退避：1 小时 */
    private static final long MAX_BACKOFF_MINUTES = 60;

    @Autowired
    private ChWritebackQueueMapper queueMapper;

    @Autowired
    private ChDualWriter dualWriter;

    @Autowired
    private ClickHouseProperties props;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 定时补偿任务。
     *
     * 注意：固定 30 秒间隔（与 application.yml 的 replay-interval-seconds 对齐）；
     * 若需运行时调整，需扩展 SchedulingConfigurer，当前场景不做此复杂度。
     */
    @Scheduled(fixedDelayString = "${loganalysis.clickhouse.writeback.replay-interval-seconds:30}000")
    public void replay() {
        try {
            int batchSize = props.getWriteback().getBatchSize();
            List<ChWritebackQueuePO> due = queueMapper.pullDue(batchSize);
            if (due.isEmpty()) return;

            int success = 0;
            int failed = 0;
            for (ChWritebackQueuePO task : due) {
                if (replayOne(task)) success++;
                else failed++;
            }
            log.info("ClickHouse 补偿重放: due={}, success={}, failed={}", due.size(), success, failed);

            // 积压告警
            long backlog = queueMapper.countBacklog();
            if (backlog > props.getWriteback().getAlertThreshold()) {
                log.warn("ClickHouse 补偿队列积压告警: backlog={}, threshold={}",
                        backlog, props.getWriteback().getAlertThreshold());
            }
        } catch (Exception e) {
            log.error("ClickHouse 补偿任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 重放单条任务。成功返回 true（行已删除），失败返回 false（retry_count+1 + next_retry_at 退避）。
     */
    private boolean replayOne(ChWritebackQueuePO task) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    task.getPayloadJson(), new TypeReference<Map<String, Object>>() {});
            dualWriter.doWrite(task.getOperation(), task.getTargetTable(), payload);
            queueMapper.deleteById(task.getId());
            return true;
        } catch (Exception e) {
            int nextRetry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
            long backoffMinutes = Math.min((long) Math.pow(2, nextRetry), MAX_BACKOFF_MINUTES);
            ChWritebackQueuePO update = new ChWritebackQueuePO();
            update.setId(task.getId());
            update.setRetryCount(nextRetry);
            String err = e.getMessage();
            if (err != null && err.length() > 1000) err = err.substring(0, 1000);
            update.setLastError(err);
            update.setNextRetryAt(LocalDateTime.now().plusMinutes(backoffMinutes));
            queueMapper.updateById(update);
            return false;
        }
    }
}
