package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentWorkStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link SuperAgentWorkStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class SuperAgentWorkStorageConfiguration {

    @Bean
    SuperAgentWorkStore mysqlSuperAgentWorkStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlSuperAgentWorkStore(jdbc);
    }
}
