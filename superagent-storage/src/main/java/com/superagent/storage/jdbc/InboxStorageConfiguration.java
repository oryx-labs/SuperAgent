package com.superagent.storage.jdbc;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the inbox stores (one bean implementing all three store interfaces) onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class InboxStorageConfiguration {

    @Bean
    MysqlInboxStore mysqlInboxStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlInboxStore(jdbc);
    }
}
