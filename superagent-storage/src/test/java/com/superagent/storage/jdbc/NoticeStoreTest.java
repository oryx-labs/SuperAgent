package com.superagent.storage.jdbc;

import com.superagent.core.notice.Notice;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeStoreTest {

    @Test
    void upsertsByIdFindsByKeyAndHidesTrackingRows() {
        MysqlNoticeStore store = new MysqlNoticeStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:notice-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant t = Instant.now();
        Notice track = new Notice("n1", "ada", "task:t1", Notice.Kind.TRACK, Notice.State.OPEN, "写周报", null, "写手",
                "task", "t1", t, t);
        store.save(track);
        assertThat(store.list("ada", 10)).isEmpty();

        store.save(track.with(Notice.Kind.DONE, Notice.State.OPEN, "写周报", "已完成", "写手"));
        assertThat(store.findByKey("ada", "task:t1")).hasValueSatisfying(n -> assertThat(n.kind()).isEqualTo(Notice.Kind.DONE));
        assertThat(store.list("ada", 10)).singleElement().satisfies(n -> assertThat(n.actor()).isEqualTo("写手"));
        assertThat(store.list("bob", 10)).isEmpty();
    }
}
