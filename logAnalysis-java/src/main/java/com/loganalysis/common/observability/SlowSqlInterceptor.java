package com.loganalysis.common.observability;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * MyBatis SQL 耗时拦截器。
 *
 * 拦截目标：Executor.query / Executor.update（覆盖所有 select / insert / update / delete）。
 * 输出：超过 {@link PerformanceProperties#getSqlWarnMs()} 的 SQL 写入 logger=slow-sql → logs/slow-sql.log。
 *
 * 设计取舍：
 *  - 用 Executor 拦截而非 StatementHandler：能拿到 MappedStatement 完整信息（含 mapper 方法 id），便于追踪到代码
 *  - SQL 文本做基础压缩（去多余空白），不还原参数到 ?，便于直接复制到客户端复现
 *  - 异常完全吞掉：观测层失败不能影响业务
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        org.apache.ibatis.cache.CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
@Component
public class SlowSqlInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger("slow-sql");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final PerformanceProperties props;

    public SlowSqlInterceptor(PerformanceProperties props) {
        this.props = props;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.nanoTime();
        Throwable error = null;
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                long durationMs = (System.nanoTime() - start) / 1_000_000L;
                // 仅记录"超阈值"或"异常"的 SQL；否则快速返回
                if (durationMs >= props.getSqlWarnMs() || error != null) {
                    Object[] args = invocation.getArgs();
                    MappedStatement ms = (MappedStatement) args[0];
                    Object parameter = args.length > 1 ? args[1] : null;
                    BoundSql boundSql = ms.getBoundSql(parameter);
                    String sql = WHITESPACE.matcher(boundSql.getSql()).replaceAll(" ").trim();

                    StringBuilder sb = new StringBuilder();
                    sb.append(error == null ? "[SLOW-SQL]" : "[SQL-ERROR]")
                      .append(" ").append(durationMs).append("ms")
                      .append(" id=").append(ms.getId())
                      .append(" sql=").append(sql);

                    if (props.isSqlLogParams()) {
                        sb.append(" params=").append(extractParams(boundSql));
                    }
                    if (error != null) {
                        sb.append(" error=").append(error.getClass().getSimpleName())
                          .append(":").append(error.getMessage());
                        log.error(sb.toString());
                    } else {
                        log.warn(sb.toString());
                    }
                }
            } catch (Throwable t) {
                log.warn("SlowSqlInterceptor logging failed: {}", t.getMessage());
            }
        }
    }

    private String extractParams(BoundSql boundSql) {
        try {
            Object pobj = boundSql.getParameterObject();
            if (pobj == null) return "[]";
            List<ParameterMapping> mappings = boundSql.getParameterMappings();
            if (mappings == null || mappings.isEmpty()) {
                return String.valueOf(pobj);
            }
            StringBuilder out = new StringBuilder("[");
            MetaObject meta = SystemMetaObject.forObject(pobj);
            for (int i = 0; i < mappings.size(); i++) {
                if (i > 0) out.append(", ");
                String prop = mappings.get(i).getProperty();
                Object val;
                if (boundSql.hasAdditionalParameter(prop)) {
                    val = boundSql.getAdditionalParameter(prop);
                } else if (meta.hasGetter(prop)) {
                    val = meta.getValue(prop);
                } else {
                    val = pobj;
                }
                out.append(prop).append("=").append(abbreviate(val));
            }
            out.append("]");
            return out.toString();
        } catch (Throwable t) {
            return "<extract-failed:" + t.getClass().getSimpleName() + ">";
        }
    }

    private String abbreviate(Object val) {
        if (val == null) return "null";
        String s = val.toString();
        return s.length() > 200 ? s.substring(0, 200) + "...(" + s.length() + ")" : s;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }
}
