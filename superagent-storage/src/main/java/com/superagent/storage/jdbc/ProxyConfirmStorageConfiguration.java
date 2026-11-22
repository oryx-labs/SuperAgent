package com.superagent.storage.jdbc;

import com.superagent.core.proxyconfirm.ProxyConfirmStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link ProxyConfirmStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class ProxyConfirmStorageConfiguration {

    @Bean
    ProxyConfirmStore mysqlProxyConfirmStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlProxyConfirmStore(jdbc);
    }
}
