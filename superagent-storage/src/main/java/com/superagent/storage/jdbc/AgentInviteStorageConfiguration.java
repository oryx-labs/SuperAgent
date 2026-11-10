package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentInviteStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link AgentInviteStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class AgentInviteStorageConfiguration {

    @Bean
    AgentInviteStore mysqlAgentInviteStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlAgentInviteStore(jdbc);
    }
}
