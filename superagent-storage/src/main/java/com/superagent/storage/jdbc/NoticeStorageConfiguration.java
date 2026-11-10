package com.superagent.storage.jdbc;

import com.superagent.core.notice.NoticeStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the JDBC {@link NoticeStore} onto MySQL. */
@Configuration(proxyBeanMethods = false)
public class NoticeStorageConfiguration {

    @Bean
    NoticeStore mysqlNoticeStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlNoticeStore(jdbc);
    }
}
