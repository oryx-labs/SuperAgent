package com.superagent.storage.jdbc;

import com.superagent.core.skill.SkillDeletionStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link SkillDeletionStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class SkillDeletionStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        SkillDeletionStore mysqlSkillDeletionStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new MysqlSkillDeletionStore(jdbc);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        SkillDeletionStore clickHouseSkillDeletionStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new ClickHouseSkillDeletionStore(jdbc);
        }
    }
}
