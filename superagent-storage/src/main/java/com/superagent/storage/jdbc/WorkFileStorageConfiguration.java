package com.superagent.storage.jdbc;

import com.superagent.core.file.WorkFileStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link WorkFileStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class WorkFileStorageConfiguration {

    @Bean
    WorkFileStore mysqlWorkFileStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlWorkFileStore(jdbc);
    }
}
