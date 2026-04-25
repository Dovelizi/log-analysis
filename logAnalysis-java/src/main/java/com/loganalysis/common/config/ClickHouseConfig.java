package com.loganalysis.common.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * ClickHouse 独立数据源 + 异步双写线程池。
 *
 * 条件装配策略：
 *   - {@code loganalysis.clickhouse.enabled=true} 时才创建 DataSource / JdbcTemplate / TaskExecutor
 *   - 为 false 时（默认）整个类不生效，行为完全等同于未引入 ClickHouse
 *
 * Bean 命名：
 *   - clickHouseDataSource：CH HikariCP 数据源
 *   - clickHouseJdbcTemplate：CH 专用 JdbcTemplate（不污染默认 jdbcTemplate）
 *   - clickHouseAsyncExecutor：异步双写线程池
 *
 * 所有配置项在 {@link ClickHouseProperties} 中收束，便于运维集中管理。
 */
@Configuration
@ConditionalOnProperty(prefix = "loganalysis.clickhouse", name = "enabled", havingValue = "true")
public class ClickHouseConfig {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "loganalysis.clickhouse")
    public ClickHouseProperties clickHouseProperties() {
        return new ClickHouseProperties();
    }

    /** ClickHouse HikariCP 数据源。Bean 名明确区分于主 MySQL 数据源。 */
    @Bean(name = "clickHouseDataSource", destroyMethod = "close")
    public DataSource clickHouseDataSource(ClickHouseProperties props) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(props.getUrl());
        hc.setUsername(props.getUsername());
        hc.setPassword(props.getPassword() == null ? "" : props.getPassword());
        hc.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        hc.setMaximumPoolSize(props.getPool().getMaximumPoolSize());
        hc.setMinimumIdle(props.getPool().getMinimumIdle());
        hc.setConnectionTimeout(props.getPool().getConnectionTimeout());
        hc.setIdleTimeout(props.getPool().getIdleTimeout());
        hc.setPoolName("HikariCP-ClickHouse");
        log.info("ClickHouse DataSource 初始化: url={}, user={}", props.getUrl(), props.getUsername());
        return new HikariDataSource(hc);
    }

    /** ClickHouse 专用 JdbcTemplate。注入时用 @Qualifier("clickHouseJdbcTemplate")。 */
    @Bean(name = "clickHouseJdbcTemplate")
    public JdbcTemplate clickHouseJdbcTemplate(@Qualifier("clickHouseDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * 异步双写线程池。
     *
     * 拒绝策略：按方案（用户选 A-异步双写），队列满时走 CallerRunsPolicy 让提交方短暂阻塞，
     * 避免直接丢数据；业务日志处理器拿到异常后会降级写入 ch_writeback_queue 补偿表。
     */
    @Bean(name = "clickHouseAsyncExecutor")
    public ThreadPoolTaskExecutor clickHouseAsyncExecutor(ClickHouseProperties props) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(props.getAsyncWrite().getCorePoolSize());
        exec.setMaxPoolSize(props.getAsyncWrite().getMaxPoolSize());
        exec.setQueueCapacity(props.getAsyncWrite().getQueueCapacity());
        exec.setThreadNamePrefix("ch-async-");
        // 队列满时阻塞调用方，避免直接抛异常丢数据；真正失败交由补偿队列兜底
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        log.info("ClickHouse 异步线程池初始化: core={}, max={}, queue={}",
                props.getAsyncWrite().getCorePoolSize(),
                props.getAsyncWrite().getMaxPoolSize(),
                props.getAsyncWrite().getQueueCapacity());
        return exec;
    }
}
