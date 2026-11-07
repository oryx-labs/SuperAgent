package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskModelChoiceStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link TaskModelChoiceStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class TaskModelChoiceStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        TaskModelChoiceStore mysqlTaskModelChoiceStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcTaskModelChoiceStore(jdbc, JdbcTaskModelChoiceStore.MYSQL_DDL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        TaskModelChoiceStore clickHouseTaskModelChoiceStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcTaskModelChoiceStore(jdbc, JdbcTaskModelChoiceStore.CLICKHOUSE_DDL);
        }
    }
}
