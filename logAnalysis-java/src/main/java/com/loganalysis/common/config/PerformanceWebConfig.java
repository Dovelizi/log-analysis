package com.loganalysis.common.config;

import com.loganalysis.common.observability.HttpAccessLogInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 {@link HttpAccessLogInterceptor}，对所有接口（除 static 资源）做耗时埋点。
 *
 * 单独配置类，避免污染 CorsConfig 的单一职责。
 */
@Configuration
public class PerformanceWebConfig implements WebMvcConfigurer {

    private final HttpAccessLogInterceptor httpAccessLogInterceptor;

    public PerformanceWebConfig(HttpAccessLogInterceptor httpAccessLogInterceptor) {
        this.httpAccessLogInterceptor = httpAccessLogInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(httpAccessLogInterceptor)
                .addPathPatterns("/**")
                // 排除静态资源与健康检查，避免噪声
                .excludePathPatterns(
                        "/", "/index.html", "/favicon.ico",
                        "/static/**", "/css/**", "/js/**", "/images/**",
                        "/actuator/**"
                );
    }
}
