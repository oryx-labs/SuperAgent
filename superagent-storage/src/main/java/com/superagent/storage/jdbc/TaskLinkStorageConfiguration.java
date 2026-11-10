package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskLinkStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link TaskLinkStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class TaskLinkStorageConfiguration {

    @Bean
    TaskLinkStore mysqlTaskLinkStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcTaskLinkStore(jdbc, JdbcTaskLinkStore.MYSQL_DDL);
    }
}
