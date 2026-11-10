package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskAgentStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link TaskAgentStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class TaskAgentStorageConfiguration {

    @Bean
    TaskAgentStore mysqlTaskAgentStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlTaskAgentStore(jdbc);
    }
}
