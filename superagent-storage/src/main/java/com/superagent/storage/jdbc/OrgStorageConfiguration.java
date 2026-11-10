package com.superagent.storage.jdbc;

import com.superagent.core.org.OrgStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link OrgStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class OrgStorageConfiguration {

    @Bean
    OrgStore mysqlOrgStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlOrgStore(jdbc);
    }
}
