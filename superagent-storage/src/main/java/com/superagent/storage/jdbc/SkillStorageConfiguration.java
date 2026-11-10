package com.superagent.storage.jdbc;

import com.superagent.core.skill.SkillStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link SkillStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class SkillStorageConfiguration {

    @Bean
    SkillStore mysqlSkillStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlSkillStore(jdbc);
    }
}
