package com.loganalysis.common.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 性能观测阈值配置。
 *
 * 项目硬性红线：每个 HTTP 接口耗时必须 &lt; 1000ms。新增接口需自测达标。
 * 本类提供阈值的可配置入口（application.yml: loganalysis.performance.*），便于线上调优而不改代码。
 */
@Configuration
@ConfigurationProperties(prefix = "loganalysis.performance")
public class PerformanceProperties {

    /** HTTP 接口耗时 WARN 阈值（毫秒）。超过即视为违反 &lt; 1s 红线。 */
    private long httpWarnMs = 1000L;

    /** HTTP 接口耗时 ERROR 阈值（毫秒）。严重违反，需立即排查。 */
    private long httpErrorMs = 5000L;

    /** SQL 慢查询 WARN 阈值（毫秒）。给接口红线留余量，单条 SQL 不应超过此值。 */
    private long sqlWarnMs = 500L;

    /** 是否打印 SQL 参数（关闭可避免敏感数据落盘，开启便于排查）。 */
    private boolean sqlLogParams = true;

    public long getHttpWarnMs() { return httpWarnMs; }
    public void setHttpWarnMs(long httpWarnMs) { this.httpWarnMs = httpWarnMs; }

    public long getHttpErrorMs() { return httpErrorMs; }
    public void setHttpErrorMs(long httpErrorMs) { this.httpErrorMs = httpErrorMs; }

    public long getSqlWarnMs() { return sqlWarnMs; }
    public void setSqlWarnMs(long sqlWarnMs) { this.sqlWarnMs = sqlWarnMs; }

    public boolean isSqlLogParams() { return sqlLogParams; }
    public void setSqlLogParams(boolean sqlLogParams) { this.sqlLogParams = sqlLogParams; }
}
