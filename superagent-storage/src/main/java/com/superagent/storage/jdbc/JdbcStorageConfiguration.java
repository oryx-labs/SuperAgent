package com.superagent.storage.jdbc;

import com.superagent.core.chat.ChatStore;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.task.TaskStore;
import com.superagent.core.task.TaskPhaseStore;
import com.superagent.core.user.UserStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * The storage backends. Everything is kept in MySQL: the business data always (its stores need transactions and
 * unique keys), and the analytics data (token usage, the agent run log, turn timings) by default too. Those
 * append-only, high-volume tables alone can go to ClickHouse with {@code superagent.storage.analytics=clickhouse};
 * only then is a ClickHouse connection opened. Spring Boot's {@code DataSourceAutoConfiguration} is excluded by the
 * server, so the pools are built here.
 */
@Configuration(proxyBeanMethods = false)
public class JdbcStorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JdbcStorageConfiguration.class);

    private static DataSource hikari(String url, String username, String password, String poolName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setPoolName(poolName);
        cfg.setMaximumPoolSize(8);
        return new HikariDataSource(cfg);
    }

    @Configuration(proxyBeanMethods = false)
    static class MysqlConfiguration {

        @Bean
        JdbcTemplate mysqlJdbcTemplate(
                @Value("${superagent.storage.mysql.url:jdbc:mysql://127.0.0.1:3306/superagent"
                        + "?createDatabaseIfNotExist=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true}")
                String url,
                @Value("${superagent.storage.mysql.username:root}") String username,
                @Value("${superagent.storage.mysql.password:}") String password) {
            log.info("MySQL storage active, url={}", url);
            return new JdbcTemplate(hikari(url, username, password, "superagent-mysql"));
        }

        @Bean
        ChatStore mysqlChatStore(JdbcTemplate mysqlJdbcTemplate) {
            return new MysqlChatStore(mysqlJdbcTemplate);
        }

        @Bean
        AgentStore mysqlAgentStore(JdbcTemplate mysqlJdbcTemplate) {
            return new MysqlAgentStore(mysqlJdbcTemplate);
        }

        @Bean
        ConnectorStore mysqlConnectorStore(JdbcTemplate mysqlJdbcTemplate) {
            return new MysqlConnectorStore(mysqlJdbcTemplate);
        }

        @Bean
        ConnectorCredentialStore mysqlConnectorCredentialStore(JdbcTemplate mysqlJdbcTemplate) {
            return new MysqlConnectorCredentialStore(mysqlJdbcTemplate);
        }

        @Bean
        TaskStore mysqlTaskStore(JdbcTemplate mysqlJdbcTemplate) {
            return new MysqlTaskStore(mysqlJdbcTemplate);
        }

        @Bean
        TaskPhaseStore mysqlTaskPhaseStore(JdbcTemplate mysqlJdbcTemplate) {
            return new MysqlTaskPhaseStore(mysqlJdbcTemplate);
        }

        @Bean
        UserStore mysqlUserStore(JdbcTemplate mysqlJdbcTemplate) {
            return new MysqlUserStore(mysqlJdbcTemplate);
        }
    }

    /** ClickHouse, for the analytics tables only (see the class comment). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.analytics", havingValue = "clickhouse")
    static class ClickHouseAnalyticsConfiguration {

        @Bean
        JdbcTemplate clickHouseJdbcTemplate(
                @Value("${superagent.storage.clickhouse.url:jdbc:ch://127.0.0.1:8123/superagent?compress=0}") String url,
                @Value("${superagent.storage.clickhouse.username:default}") String username,
                @Value("${superagent.storage.clickhouse.password:}") String password) {
            log.info("ClickHouse analytics storage active, url={}", url);
            return new JdbcTemplate(hikari(url, username, password, "superagent-clickhouse"));
        }
    }
}
