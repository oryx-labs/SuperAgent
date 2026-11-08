package com.superagent.files;

import com.superagent.core.file.FileStore;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStoreProviderTest {

    @Test
    void aCompanyCannotReachAnothersFileByItsId(@TempDir Path tmp) throws Exception {
        FileStore store = new LocalFileStoreProvider(tmp.resolve("files").toString()).open(Map.of());
        Path source = Files.writeString(tmp.resolve("a.txt"), "acme only");
        TenantContext.callChecked("acme", () -> {
            store.put("f1", source, "text/plain");
            return null;
        });

        assertThat(tmp.resolve("files/acme/f1/content")).hasContent("acme only");
        TenantContext.run("globex", () -> {
            assertThat(store.localPath("f1")).isEmpty();
            assertThatThrownBy(() -> {
                try (InputStream ignored = store.open("f1")) {
                    // unreachable
                }
            }).isInstanceOf(NoSuchFileException.class);
            assertThatThrownBy(() -> store.open("../acme/f1")).isInstanceOf(IllegalArgumentException.class);
        });
        assertThatThrownBy(() -> store.localPath("f1")).isInstanceOf(IllegalStateException.class);
    }
}
