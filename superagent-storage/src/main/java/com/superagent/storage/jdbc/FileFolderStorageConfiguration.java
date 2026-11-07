package com.superagent.storage.jdbc;

import com.superagent.core.file.FileFolderStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link FileFolderStore} onto the active backend's {@link JdbcTemplate}. */
@Configuration(proxyBeanMethods = false)
public class FileFolderStorageConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
    static class Mysql {
        @Bean
        FileFolderStore mysqlFileFolderStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
            return new MysqlFileFolderStore(jdbc);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouse {
        @Bean
        FileFolderStore clickHouseFileFolderStore(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate jdbc) {
            return new ClickHouseFileFolderStore(jdbc);
        }
    }
}
