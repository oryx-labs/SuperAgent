package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentParentStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link AgentParentStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class AgentParentStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        AgentParentStore mysqlAgentParentStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcAgentParentStore(jdbc, JdbcAgentParentStore.MYSQL_DDL);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        AgentParentStore clickHouseAgentParentStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new JdbcAgentParentStore(jdbc, JdbcAgentParentStore.CLICKHOUSE_DDL);
        }
    }
}
