package com.superagent.storage.jdbc;

import com.superagent.core.provider.ProviderCredentialStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link ProviderCredentialStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class ProviderCredentialStorageConfiguration {

    @Bean
    ProviderCredentialStore mysqlProviderCredentialStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlProviderCredentialStore(jdbc);
    }
}
