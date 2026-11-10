package com.superagent.storage.jdbc;

import com.superagent.core.settings.SystemSettingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link SystemSettingStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class SystemSettingStorageConfiguration {

    @Bean
    SystemSettingStore mysqlSystemSettingStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlSystemSettingStore(jdbc);
    }
}
