package com.superagent.storage.jdbc;

import com.superagent.core.timing.TurnTimingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link TurnTimingStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class TurnTimingStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        TurnTimingStore mysqlTurnTimingStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new MysqlTurnTimingStore(jdbc);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        TurnTimingStore clickHouseTurnTimingStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new ClickHouseTurnTimingStore(jdbc);
        }
    }
}
