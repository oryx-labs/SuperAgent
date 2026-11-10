package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMemoryStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link AgentMemoryStateStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class AgentMemoryStateStorageConfiguration {

    @Bean
    AgentMemoryStateStore mysqlAgentMemoryStateStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlAgentMemoryStateStore(jdbc);
    }
}
