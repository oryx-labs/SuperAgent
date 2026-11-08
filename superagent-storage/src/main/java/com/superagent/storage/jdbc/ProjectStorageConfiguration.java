package com.superagent.storage.jdbc;

import com.superagent.core.project.ProjectItemStore;
import com.superagent.core.project.ProjectStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC project stores onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class ProjectStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        ProjectStore mysqlProjectStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new MysqlProjectStore(jdbc);
        }

        @Bean
        ProjectItemStore mysqlProjectItemStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new MysqlProjectItemStore(jdbc);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        ProjectStore clickHouseProjectStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new ClickHouseProjectStore(jdbc);
        }

        @Bean
        ProjectItemStore clickHouseProjectItemStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new ClickHouseProjectItemStore(jdbc);
        }
    }
}
