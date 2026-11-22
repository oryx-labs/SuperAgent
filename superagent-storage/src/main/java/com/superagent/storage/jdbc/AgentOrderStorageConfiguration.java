package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentOrderStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link AgentOrderStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class AgentOrderStorageConfiguration {

    @Bean
    AgentOrderStore mysqlAgentOrderStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlAgentOrderStore(jdbc);
    }
}
