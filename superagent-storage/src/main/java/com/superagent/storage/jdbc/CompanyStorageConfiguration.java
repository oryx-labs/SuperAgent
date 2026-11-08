package com.superagent.storage.jdbc;

import com.superagent.core.tenant.CompanyStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link CompanyStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class CompanyStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        CompanyStore mysqlCompanyStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcCompanyStore(jdbc, JdbcCompanyStore.MYSQL_DDL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        CompanyStore clickHouseCompanyStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcCompanyStore(jdbc, JdbcCompanyStore.CLICKHOUSE_DDL);
        }
    }
}
