package com.superagent.storage.jdbc;

import com.superagent.core.chat.ChatStore;
import com.superagent.core.connector.ConnectorCredentialStore;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.task.TaskStore;
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
 * Wires the JDBC-backed {@link ChatStore} and {@link ConnectorStore} selected by
 * {@code superagent.storage.type}. Each backend builds one {@link DataSource}
 * shared by both stores (Spring Boot's {@code DataSourceAutoConfiguration} is
 * excluded by the server), so adding the JDBC drivers to the classpath never
 * forces a DataSource when the file backend is in use.
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
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "mysql", matchIfMissing = true)
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
        UserStore mysqlUserStore(JdbcTemplate mysqlJdbcTemplate) {
            return new MysqlUserStore(mysqlJdbcTemplate);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "superagent.storage.type", havingValue = "clickhouse")
    static class ClickHouseConfiguration {

        @Bean
        JdbcTemplate clickHouseJdbcTemplate(
                @Value("${superagent.storage.clickhouse.url:jdbc:ch://127.0.0.1:8123/superagent?compress=0}") String url,
                @Value("${superagent.storage.clickhouse.username:default}") String username,
                @Value("${superagent.storage.clickhouse.password:}") String password) {
            log.info("ClickHouse storage active, url={}", url);
            return new JdbcTemplate(hikari(url, username, password, "superagent-clickhouse"));
        }

        @Bean
        ChatStore clickHouseChatStore(JdbcTemplate clickHouseJdbcTemplate) {
            return new ClickHouseChatStore(clickHouseJdbcTemplate);
        }

        @Bean
        ConnectorStore clickHouseConnectorStore(JdbcTemplate clickHouseJdbcTemplate) {
            return new ClickHouseConnectorStore(clickHouseJdbcTemplate);
        }

        @Bean
        ConnectorCredentialStore clickHouseConnectorCredentialStore(JdbcTemplate clickHouseJdbcTemplate) {
            return new ClickHouseConnectorCredentialStore(clickHouseJdbcTemplate);
        }

        @Bean
        TaskStore clickHouseTaskStore(JdbcTemplate clickHouseJdbcTemplate) {
            return new ClickHouseTaskStore(clickHouseJdbcTemplate);
        }

        @Bean
        UserStore clickHouseUserStore(JdbcTemplate clickHouseJdbcTemplate) {
            return new ClickHouseUserStore(clickHouseJdbcTemplate);
        }
    }
}
