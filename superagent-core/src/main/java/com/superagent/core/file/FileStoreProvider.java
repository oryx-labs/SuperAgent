package com.superagent.core.file;

import java.util.List;
import java.util.Map;

/**
 * A kind of file store (a plugin): local disk, S3-compatible, … It names itself, says which settings it needs —
 * the admin's settings form is built from them — and opens a store from those settings. One kind can back several
 * storage channels (e.g. two buckets); each file records the channel its content is in.
 */
public interface FileStoreProvider {

    /** Stored on each file as {@link WorkFile#storage()}; never changes once files use it. */
    String type();

    /** Shown in the settings, e.g. "S3-compatible (AWS S3, MinIO, R2, COS…)". */
    String label();

    List<Field> fields();

    /** A store for these settings ({@code config} holds every field, secrets decrypted). Throws on bad settings. */
    FileStore open(Map<String, String> config);

    /**
     * One setting; a secret is stored encrypted and never shown again. {@code locates}: the setting decides where
     * content lives (endpoint, bucket, prefix…), so changing it opens a new storage channel instead of moving the
     * old one — files already there must still be found. Credentials don't locate: rotating a key keeps the channel.
     */
    record Field(String key, String label, boolean secret, boolean required, String placeholder, boolean locates) {
    }
}
