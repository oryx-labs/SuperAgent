package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentParentStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link AgentParentStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class AgentParentStorageConfiguration {

    @Bean
    AgentParentStore mysqlAgentParentStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcAgentParentStore(jdbc, JdbcAgentParentStore.MYSQL_DDL);
    }
}
