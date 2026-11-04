package com.superagent.storage.jdbc;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the inbox stores (one bean implementing all three store interfaces) onto the active backend. */
@Configuration(proxyBeanMethods = false)
public class InboxStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        MysqlInboxStore mysqlInboxStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new MysqlInboxStore(jdbc);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        ClickHouseInboxStore clickHouseInboxStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new ClickHouseInboxStore(jdbc);
        }
    }
}
