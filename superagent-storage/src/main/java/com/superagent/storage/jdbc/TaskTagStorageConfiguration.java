package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskTagStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link TaskTagStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class TaskTagStorageConfiguration {

    @Bean
    TaskTagStore mysqlTaskTagStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcTaskTagStore(jdbc, JdbcTaskTagStore.MYSQL_DDL);
    }
}
