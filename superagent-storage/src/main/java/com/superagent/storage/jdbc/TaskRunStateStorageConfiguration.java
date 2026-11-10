package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskRunStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
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

    @Bean
    TaskRunStateStore mysqlTaskRunStateStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlTaskRunStateStore(jdbc);
    }
}
