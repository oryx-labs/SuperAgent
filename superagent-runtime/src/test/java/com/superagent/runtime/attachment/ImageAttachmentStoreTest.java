package com.superagent.runtime.attachment;

import com.superagent.api.chat.ImageUploadDto;
import com.superagent.core.chat.ImageAttachment;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageAttachmentStoreTest {

    private static final String OWNER = UUID.randomUUID().toString();
    private static final String MESSAGE = UUID.randomUUID().toString();

    private static String b64(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static final String PNG = b64(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2);
    private static final String JPEG = b64(0xFF, 0xD8, 0xFF, 0, 1);

    @BeforeEach
    void company() {
        TenantContext.set("acme");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void storesInOrderTakesTheRealFormatAndServesItBack(@TempDir Path dir) {
        ImageAttachmentStore store = new ImageAttachmentStore(dir.toString());
        // Declared types are ignored: the bytes decide (the first one claims to be a GIF).
        List<ImageAttachment> saved = store.save(OWNER, MESSAGE,
                List.of(new ImageUploadDto("image/gif", PNG), new ImageUploadDto("image/png", JPEG)));

        assertThat(saved).extracting(ImageAttachment::mediaType).containsExactly("image/png", "image/jpeg");
        assertThat(store.list(OWNER, MESSAGE)).isEqualTo(saved);
        assertThat(store.read(OWNER, MESSAGE, 1).mediaType()).isEqualTo("image/jpeg");
        assertThat(store.urls("/api/v1/tasks/t/messages", OWNER, MESSAGE))
                .containsExactly("/api/v1/tasks/t/messages/" + MESSAGE + "/images/0",
                        "/api/v1/tasks/t/messages/" + MESSAGE + "/images/1");
        assertThatThrownBy(() -> store.read(OWNER, MESSAGE, 2)).isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    void rejectsNonImagesTooManyImagesAndUnsafeIds(@TempDir Path dir) {
        ImageAttachmentStore store = new ImageAttachmentStore(dir.toString());
        String svg = Base64.getEncoder().encodeToString("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> store.save(OWNER, MESSAGE, List.of(new ImageUploadDto("image/png", svg))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(OWNER, MESSAGE,
                Collections.nCopies(ImageAttachmentStore.MAX_IMAGES + 1, new ImageUploadDto("image/png", PNG))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save("../../etc", MESSAGE, List.of(new ImageUploadDto("image/png", PNG))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.list("../../etc", MESSAGE)).isEmpty();
    }

    @Test
    void oneCompanyNeverSeesAnothersImages(@TempDir Path dir) {
        ImageAttachmentStore store = new ImageAttachmentStore(dir.toString());
        store.save(OWNER, MESSAGE, List.of(new ImageUploadDto("image/png", PNG)));

        assertThat(store.list(OWNER, MESSAGE).getFirst().path()).startsWith(dir.resolve("acme").toString());
        TenantContext.run("globex", () -> {
            assertThat(store.list(OWNER, MESSAGE)).isEmpty();
            assertThatThrownBy(() -> store.read(OWNER, MESSAGE, 0)).isInstanceOf(AttachmentNotFoundException.class);
        });
    }
}
