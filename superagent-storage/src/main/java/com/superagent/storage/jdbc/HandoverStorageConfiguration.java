package com.superagent.storage.jdbc;

import com.superagent.core.handover.HandoverRecordStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link HandoverRecordStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class HandoverStorageConfiguration {

    @Bean
    HandoverRecordStore mysqlHandoverRecordStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlHandoverRecordStore(jdbc);
    }
}
