package com.loganalysis.common.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 给所有 JdbcTemplate Bean 套一层 CGLIB 子类代理，对 query / update / execute 等方法做耗时埋点。
 *
 * 设计动因：
 *  - 项目中 SQL 调用走两条路径：MyBatis（被 {@link SlowSqlInterceptor} 覆盖）+ JdbcTemplate（本类覆盖）
 *  - 必须用 CGLIB 子类代理而不是 JDK Proxy，否则业务里 `@Autowired JdbcTemplate` 注入会因类型不匹配失败
 *  - 不引入 AOP starter；spring-core 已自带 CGLIB，零新增依赖
 *
 * 输出：超过 {@link PerformanceProperties#getSqlWarnMs()} 的 SQL → logger=slow-sql → logs/slow-sql.log。
 */
@Component
public class JdbcTemplateSlowSqlAspect implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger("slow-sql");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 需要计时的方法名前缀（覆盖 query / update / execute / batchUpdate / queryForXxx 等） */
    private static final String[] TIMED_METHOD_PREFIXES = {
            "query", "update", "execute", "batchUpdate"
    };

    private final ObjectProvider<PerformanceProperties> propsProvider;
    private volatile PerformanceProperties cachedProps;

    public JdbcTemplateSlowSqlAspect(ObjectProvider<PerformanceProperties> propsProvider) {
        this.propsProvider = propsProvider;
    }

    /** 延迟解析 PerformanceProperties，避免 BPP 早期实例化时配置尚未绑定 */
    private PerformanceProperties props() {
        PerformanceProperties p = cachedProps;
        if (p != null) return p;
        synchronized (this) {
            if (cachedProps == null) {
                cachedProps = propsProvider.getObject();
            }
            return cachedProps;
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof JdbcTemplate)) {
            return bean;
        }
        JdbcTemplate target = (JdbcTemplate) bean;
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(JdbcTemplate.class);
        enhancer.setCallback(new TimingInterceptor(target, beanName));
        // JdbcTemplate 有无参构造，可直接 create()
        Object proxy = enhancer.create();
        // 拷贝必要状态（dataSource 等）到子类代理实例
        ((JdbcTemplate) proxy).setDataSource(target.getDataSource());
        ((JdbcTemplate) proxy).setFetchSize(target.getFetchSize());
        ((JdbcTemplate) proxy).setMaxRows(target.getMaxRows());
        if (target.getQueryTimeout() > 0) {
            ((JdbcTemplate) proxy).setQueryTimeout(target.getQueryTimeout());
        }
        log.info("JdbcTemplate bean '{}' wrapped with slow-sql timing CGLIB proxy", beanName);
        return proxy;
    }

    private class TimingInterceptor implements MethodInterceptor {
        private final JdbcTemplate target;
        private final String beanName;

        TimingInterceptor(JdbcTemplate target, String beanName) {
            this.target = target;
            this.beanName = beanName;
        }

        @Override
        public Object intercept(Object obj, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            // 对未列入计时白名单的方法，仍委托给原 target，保留 set/get 等行为
            if (!isTimedMethod(method.getName())) {
                return method.invoke(target, args);
            }
            long start = System.nanoTime();
            Throwable error = null;
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                error = ite.getTargetException();
                throw error;
            } catch (Throwable t) {
                error = t;
                throw t;
            } finally {
                try {
                    long durationMs = (System.nanoTime() - start) / 1_000_000L;
                    PerformanceProperties p = props();
                    if (durationMs >= p.getSqlWarnMs() || error != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(error == null ? "[SLOW-SQL]" : "[SQL-ERROR]")
                          .append(" ").append(durationMs).append("ms")
                          .append(" jdbcTemplate=").append(beanName)
                          .append(" method=").append(method.getName())
                          .append(" sql=").append(extractSql(args));
                        if (p.isSqlLogParams()) {
                            sb.append(" args=").append(extractArgs(args));
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
                    log.warn("JdbcTemplateSlowSqlAspect logging failed: {}", t.getMessage());
                }
            }
        }

        private boolean isTimedMethod(String name) {
            for (String prefix : TIMED_METHOD_PREFIXES) {
                if (name.startsWith(prefix)) return true;
            }
            return false;
        }

        private String extractSql(Object[] args) {
            if (args == null || args.length == 0) return "<no-args>";
            Object first = args[0];
            if (first instanceof String) {
                return WHITESPACE.matcher((String) first).replaceAll(" ").trim();
            }
            return "<" + first.getClass().getSimpleName() + ">";
        }

        private String extractArgs(Object[] args) {
            if (args == null || args.length <= 1) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 1; i < args.length; i++) {
                if (i > 1) sb.append(", ");
                sb.append(abbreviate(args[i]));
            }
            sb.append("]");
            return sb.toString();
        }

        private String abbreviate(Object val) {
            if (val == null) return "null";
            String s;
            if (val instanceof Object[]) {
                s = Arrays.deepToString((Object[]) val);
            } else {
                s = val.toString();
            }
            return s.length() > 200 ? s.substring(0, 200) + "...(" + s.length() + ")" : s;
        }
    }
}
