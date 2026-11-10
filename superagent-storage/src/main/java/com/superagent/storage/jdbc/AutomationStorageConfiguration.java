package com.superagent.storage.jdbc;

import com.superagent.core.automation.AutomationStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link AutomationStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class AutomationStorageConfiguration {

    @Bean
    AutomationStore mysqlAutomationStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlAutomationStore(jdbc);
    }
}
