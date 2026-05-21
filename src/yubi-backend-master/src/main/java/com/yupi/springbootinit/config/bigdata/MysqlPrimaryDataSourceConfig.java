package com.yupi.springbootinit.config.bigdata;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 启用 ClickHouse 时会注册第二个 {@link DataSource}，导致 Spring Boot 不再自动创建 MySQL 数据源，
 * MyBatis 误用 ClickHouse 连接（登录等查询 user 表会报 UNKNOWN_TABLE）。
 * 此处用 {@link DataSourceProperties} 显式构建带 jdbcUrl 的 MySQL 主库。
 */
@Configuration
@ConditionalOnProperty(prefix = "bigdata.clickhouse", name = "enabled", havingValue = "true")
public class MysqlPrimaryDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }
}
