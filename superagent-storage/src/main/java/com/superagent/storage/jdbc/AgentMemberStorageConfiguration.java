package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMemberStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link AgentMemberStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class AgentMemberStorageConfiguration {

    @Bean
    AgentMemberStore mysqlAgentMemberStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlAgentMemberStore(jdbc);
    }
}
