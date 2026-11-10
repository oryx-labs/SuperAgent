package com.superagent.storage.jdbc;

import com.superagent.core.provider.ModelProviderStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link ModelProviderStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class ModelProviderStorageConfiguration {

    @Bean
    ModelProviderStore mysqlModelProviderStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlModelProviderStore(jdbc);
    }
}
