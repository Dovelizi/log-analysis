package com.loganalysis.dashboard.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dashboard 业务专用异步执行池。
 *
 * 用途：service 内部用 CompletableFuture 并发执行多条 SQL 时使用此池，
 * 避免占用 ForkJoinPool.commonPool（默认 CPU-1 个线程，高并发会耗尽影响其他业务）。
 *
 * 池参数取舍（基于 dashboard 接口典型 3 条并发 SQL）：
 *  - core/max=8: 容许同时 ~2~3 个 dashboard 接口并发，每个用 3 个线程
 *  - 队列 32: 短暂超过 max 时排队，防止任务被拒
 *  - CallerRunsPolicy: 队列满时调用线程自己跑（退化为串行，业务不报错只是变慢）
 *
 * 参数通过 {@code loganalysis.dashboard.async.*} 外置，默认值与原硬编码一致。
 */
@Configuration
public class DashboardAsyncConfig {

    @Value("${loganalysis.dashboard.async.core-pool-size:8}")
    private int corePoolSize;

    @Value("${loganalysis.dashboard.async.queue-capacity:32}")
    private int queueCapacity;

    @Value("${loganalysis.dashboard.async.keep-alive-seconds:60}")
    private long keepAliveSeconds;

    @Bean(name = "dashboardAsyncExecutor", destroyMethod = "shutdown")
    public ExecutorService dashboardAsyncExecutor() {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "dashboard-async-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                corePoolSize, corePoolSize,
                keepAliveSeconds, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                tf,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
