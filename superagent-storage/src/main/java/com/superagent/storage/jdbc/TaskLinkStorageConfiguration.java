package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskLinkStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link TaskLinkStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class TaskLinkStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        TaskLinkStore mysqlTaskLinkStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcTaskLinkStore(jdbc, JdbcTaskLinkStore.MYSQL_DDL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        TaskLinkStore clickHouseTaskLinkStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcTaskLinkStore(jdbc, JdbcTaskLinkStore.CLICKHOUSE_DDL);
        }
    }
}
