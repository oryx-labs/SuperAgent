package com.superagent.storage.jdbc;

import com.superagent.core.file.FileFolder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FileFolderStoreTest {

    @Test
    void keepsFoldersPerPersonAndAPlacementPerPersonAndFile() {
        MysqlFileFolderStore store = new MysqlFileFolderStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:folder-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant t = Instant.parse("2026-11-07T10:00:00Z");
        FileFolder top = new FileFolder("a", "ada", "项目", null, false, t, t);
        store.save(top);
        store.save(new FileFolder("b", "ada", "Q4", "a", false, t, t));
        store.save(new FileFolder("c", "bob", "其他", null, false, t, t));

        assertThat(store.list("ada")).extracting(FileFolder::id).containsExactlyInAnyOrder("a", "b");
        assertThat(store.find("b")).hasValueSatisfying(f -> assertThat(f.parentId()).isEqualTo("a"));
        assertThat(store.find("a")).hasValueSatisfying(f -> assertThat(f.parentId()).isNull());
        assertThat(store.owners()).containsExactlyInAnyOrder("ada", "bob");

        store.save(top.asDeleted());
        assertThat(store.find("a")).isEmpty();
        assertThat(store.list("ada")).extracting(FileFolder::id).containsExactly("b");

        store.place("ada", "f1", "b", "auto");
        store.place("ada", "f1", "", "user"); // moved out: replaces, and stays known
        store.place("bob", "f1", "c", "user");
        assertThat(store.placements("ada")).containsEntry("f1", "");
        assertThat(store.placements("bob")).containsEntry("f1", "c");
    }
}
