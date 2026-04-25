package com.loganalysis.common.config;

import lombok.Data;

/**
 * ClickHouse 相关配置。对应 application.yml 的 loganalysis.clickhouse.* 节点。
 *
 * 仅在 {@link ClickHouseConfig} 被激活（enabled=true）时创建 bean。
 */
@Data
public class ClickHouseProperties {

    /** 全局总开关 */
    private boolean enabled = false;

    /** 读路径来源：clickhouse（主）/ mysql（灰度期回退） */
    private String readSource = "mysql";

    /** 是否双写到 ClickHouse（enabled=true 时才生效） */
    private boolean dualWrite = true;

    private String url;
    private String username = "default";
    private String password = "";

    private Pool pool = new Pool();
    private AsyncWrite asyncWrite = new AsyncWrite();
    private Writeback writeback = new Writeback();

    @Data
    public static class Pool {
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;
        private long connectionTimeout = 5000;
        private long idleTimeout = 600000;
    }

    @Data
    public static class AsyncWrite {
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
        private int queueCapacity = 1000;
        /** 队列满时是否降级到补偿队列（预留扩展点，当前实现用 CallerRunsPolicy） */
        private boolean rejectToWritebackQueue = true;
    }

    @Data
    public static class Writeback {
        private boolean enabled = true;
        /** 补偿任务重放间隔（秒） */
        private long replayIntervalSeconds = 30;
        /** 单批次重放上限 */
        private int batchSize = 200;
        /** 积压告警阈值（超过则告警） */
        private long alertThreshold = 100;
    }
}
