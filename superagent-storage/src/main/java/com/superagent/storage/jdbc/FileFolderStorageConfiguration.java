package com.superagent.storage.jdbc;

import com.superagent.core.file.FileFolderStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link FileFolderStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class FileFolderStorageConfiguration {

    @Bean
    FileFolderStore mysqlFileFolderStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlFileFolderStore(jdbc);
    }
}
