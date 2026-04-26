package com.loganalysis.common.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HTTP 接口耗时拦截器。
 *
 * 行为：
 *  - preHandle: 记录起始时间到 request attribute
 *  - afterCompletion: 计算耗时并按阈值分级输出日志
 *
 * 日志去向：logger=api → logs/api.log（已在 logback-spring.xml 配置）。
 *
 * 阈值由 {@link PerformanceProperties} 提供，默认：&gt;1000ms WARN，&gt;5000ms ERROR，否则 INFO。
 *
 * 设计取舍：
 *  - 用 HandlerInterceptor 而非 Filter：能拿到 HandlerMethod 精确到 Controller#method，便于定位
 *  - 不记录 request body：避免大 body 拖慢日志和泄露敏感字段
 *  - 不抛异常：观测代码失败不能影响业务请求
 */
@Component
public class HttpAccessLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger("api");
    private static final String START_TIME_ATTR = "_perf_start_nano";

    private final PerformanceProperties props;

    public HttpAccessLogInterceptor(PerformanceProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        try {
            Object startObj = request.getAttribute(START_TIME_ATTR);
            if (!(startObj instanceof Long)) {
                return; // preHandle 未执行（极少见），不记录避免误导
            }
            long durationMs = (System.nanoTime() - (Long) startObj) / 1_000_000L;

            String method = request.getMethod();
            String uri = request.getRequestURI();
            String query = request.getQueryString();
            int status = response.getStatus();
            String handlerSig = handlerSignature(handler);
            String clientIp = clientIp(request);

            String line = String.format(
                "%s %s%s -> %d %dms handler=%s ip=%s%s",
                method,
                uri,
                query == null ? "" : "?" + query,
                status,
                durationMs,
                handlerSig,
                clientIp,
                ex == null ? "" : " ex=" + ex.getClass().getSimpleName() + ":" + ex.getMessage()
            );

            if (durationMs >= props.getHttpErrorMs()) {
                log.error("[SLOW-API-CRITICAL] {}", line);
            } else if (durationMs >= props.getHttpWarnMs()) {
                log.warn("[SLOW-API] {}", line);
            } else {
                log.info(line);
            }
        } catch (Throwable t) {
            // 观测代码失败不能影响业务，吞掉但写一行 warn 便于排查
            log.warn("HttpAccessLogInterceptor afterCompletion failed: {}", t.getMessage());
        }
    }

    private String handlerSignature(Object handler) {
        if (handler instanceof HandlerMethod) {
            HandlerMethod hm = (HandlerMethod) handler;
            return hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
        }
        return handler == null ? "null" : handler.getClass().getSimpleName();
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
