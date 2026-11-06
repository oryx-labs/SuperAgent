package com.superagent.files;

import com.superagent.core.file.FileStore;
import com.superagent.core.file.FileStoreProvider;
import com.superagent.core.file.WorkFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The default store: the server's own disk, {@code <files dir>/<id>/content}. Needs no settings. */
@Component
public class LocalFileStoreProvider implements FileStoreProvider {

    private final Path root;

    public LocalFileStoreProvider(@Value("${superagent.files.dir:./data/files}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public String type() {
        return WorkFile.INTERNAL;
    }

    @Override
    public String label() {
        return "Local disk";
    }

    @Override
    public List<Field> fields() {
        return List.of();
    }

    @Override
    public FileStore open(Map<String, String> config) {
        return new LocalFileStore(root);
    }

    static final class LocalFileStore implements FileStore {

        private final Path root;

        LocalFileStore(Path root) {
            this.root = root;
        }

        private Path path(String id) {
            Path p = root.resolve(id).resolve("content").normalize();
            if (!p.startsWith(root) || id.isBlank() || id.contains("/") || id.contains("\\") || id.startsWith(".")) {
                throw new IllegalArgumentException("Bad file id: " + id);
            }
            return p;
        }

        @Override
        public void put(String id, Path source, String contentType) throws IOException {
            Path target = path(id);
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "content-", ".tmp");
            try {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Override
        public InputStream open(String id) throws IOException {
            return Files.newInputStream(path(id));
        }

        @Override
        public void delete(String id) throws IOException {
            Files.deleteIfExists(path(id));
            Files.deleteIfExists(path(id).getParent());
        }

        @Override
        public Optional<Path> localPath(String id) {
            Path p = path(id);
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
    }
}
