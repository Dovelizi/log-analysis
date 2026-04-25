package com.loganalysis.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * 设计要点：
 * 1. 按 DDD 分领域的包规则，扫描 {domain}/infrastructure/persistence/mapper 下的所有 Mapper 接口；
 *    目前尚未定义任何 Mapper（P2b 起引入），空匹配不会报错。
 * 2. 注册分页插件（MySQL 方言），为 P2b 起的业务表分页查询做准备。
 * 3. 项目已在 application.yml 设置 mybatis.configuration.map-underscore-to-camel-case=true，
 *    MyBatis-Plus 会自动继承该配置，此处无需重复声明。
 * 4. 本项目为混合持久化：
 *      - 配置类 Service（Credential/Topic/QueryConfig/ReportPushConfig）继续使用 JdbcTemplate
 *      - 5 个 Processor 的业务表写入（P2b）切换到 MyBatis-Plus
 *      - TableMappingService 的动态 DDL 永远保留 JdbcTemplate
 */
@Configuration
@MapperScan("com.loganalysis.**.infrastructure.persistence.mapper")
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 拦截器链（分页插件）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
