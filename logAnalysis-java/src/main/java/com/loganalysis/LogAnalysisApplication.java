package com.loganalysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * logAnalysis Java 启动类
 * 对齐原 Flask app.py，端口 8080。
 *
 * Mapper 扫描由 {@link com.loganalysis.common.config.MybatisPlusConfig} 统一接管，
 * 扫描路径：com.loganalysis.**.infrastructure.persistence.mapper
 */
@SpringBootApplication
@EnableScheduling
public class LogAnalysisApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogAnalysisApplication.class, args);
    }
}

