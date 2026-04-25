package com.loganalysis;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * logAnalysis Java 启动类
 * 对齐原 Flask app.py，端口 8080。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.loganalysis.mapper")
public class LogAnalysisApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogAnalysisApplication.class, args);
    }
}
