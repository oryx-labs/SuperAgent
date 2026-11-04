package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentWorkStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link SuperAgentWorkStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class SuperAgentWorkStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        SuperAgentWorkStore mysqlSuperAgentWorkStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new MysqlSuperAgentWorkStore(jdbc);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        SuperAgentWorkStore clickHouseSuperAgentWorkStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new ClickHouseSuperAgentWorkStore(jdbc);
        }
    }
}
