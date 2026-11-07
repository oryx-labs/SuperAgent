package com.superagent.storage.jdbc;

import com.superagent.core.usage.UsageStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link UsageStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class UsageStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        UsageStore mysqlUsageStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcUsageStore(jdbc, JdbcUsageStore.MYSQL_DDL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        UsageStore clickHouseUsageStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcUsageStore(jdbc, JdbcUsageStore.CLICKHOUSE_DDL);
        }
    }
}
