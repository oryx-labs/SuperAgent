package com.superagent.storage.jdbc;

import com.superagent.core.file.ProjectFolderStore;
import com.superagent.core.project.ProjectContextStore;
import com.superagent.core.project.ProjectItemStore;
import com.superagent.core.project.ProjectStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC project stores (projects, items, context, shared folders) onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class ProjectStorageConfiguration {

    @Bean
    ProjectStore mysqlProjectStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlProjectStore(jdbc);
    }

    @Bean
    ProjectItemStore mysqlProjectItemStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlProjectItemStore(jdbc);
    }

    @Bean
    ProjectContextStore mysqlProjectContextStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlProjectContextStore(jdbc);
    }

    @Bean
    ProjectFolderStore mysqlProjectFolderStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlProjectFolderStore(jdbc);
    }
}
