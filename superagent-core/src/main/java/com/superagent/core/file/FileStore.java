package com.superagent.core.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where work files' content lives: the server's disk, an S3-compatible bucket, … Files are addressed by their id;
 * how an id maps to a path or an object key is the store's business. The index (name, owner, tags, …) always stays
 * in the database — a store only holds bytes.
 */
public interface FileStore {

    /** Stores {@code source}'s bytes as the content of {@code id}, replacing any there. */
    void put(String id, Path source, String contentType) throws IOException;

    /** The content of {@code id}; the caller closes it. */
    InputStream open(String id) throws IOException;

    /** Removes the content of {@code id}; nothing there is fine. */
    void delete(String id) throws IOException;

    /** The content as a file on this machine, when it already is one (the local store); empty otherwise. */
    default Optional<Path> localPath(String id) {
        return Optional.empty();
    }
}
