package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskModelChoiceStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link TaskModelChoiceStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class TaskModelChoiceStorageConfiguration {

    @Bean
    TaskModelChoiceStore mysqlTaskModelChoiceStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcTaskModelChoiceStore(jdbc, JdbcTaskModelChoiceStore.MYSQL_DDL);
    }
}
