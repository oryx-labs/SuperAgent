package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskRunStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the JDBC-backed {@link TaskRunStateStore} onto the same {@link JdbcTemplate}
 * the active backend already builds in {@code JdbcStorageConfiguration}. Kept as a
 * separate configuration so adding this store never has to edit that file.
 */
@Configuration(proxyBeanMethods = false)
public class TaskRunStateStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        TaskRunStateStore mysqlTaskRunStateStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new MysqlTaskRunStateStore(jdbc);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        TaskRunStateStore clickHouseTaskRunStateStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new ClickHouseTaskRunStateStore(jdbc);
        }
    }
}
