package com.superagent.platform.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeRuntimeServiceTest {

    @TempDir
    Path tmp;

    @Test
    void anArchiveIsUnpackedOnlyWhenItMatchesThePublishedChecksum() throws IOException {
        Path archive = Files.writeString(tmp.resolve("node.tar.gz"), "node");
        // sha256("node")
        String published = "545ea538461003efdc8c81c244531b003f6f26cfccf6c0073b3239fdedf49446";
        assertThatCode(() -> NodeRuntimeService.verify(archive, published)).doesNotThrowAnyException();
        assertThatThrownBy(() -> NodeRuntimeService.verify(archive, published.replace('5', '6')))
                .isInstanceOf(IOException.class).hasMessageContaining("SHA-256");
    }
}
