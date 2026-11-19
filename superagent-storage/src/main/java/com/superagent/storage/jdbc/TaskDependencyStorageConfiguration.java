package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskDependencyStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link TaskDependencyStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class TaskDependencyStorageConfiguration {

    @Bean
    TaskDependencyStore mysqlTaskDependencyStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcTaskDependencyStore(jdbc, JdbcTaskDependencyStore.MYSQL_DDL);
    }
}
