package com.superagent.storage.jdbc;

import com.superagent.core.tenant.CompanyStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link CompanyStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class CompanyStorageConfiguration {

    @Bean
    CompanyStore mysqlCompanyStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcCompanyStore(jdbc, JdbcCompanyStore.MYSQL_DDL);
    }
}
