package com.superagent.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** A deck's slides come out in slide order, one paragraph a line. */
class DocumentTextTest {

    @TempDir
    Path tmp;

    @Test
    void aDecksSlidesAreReadInOrder() throws Exception {
        Path deck = tmp.resolve("第02课.pptx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(deck))) {
            put(zip, "ppt/slides/slide10.xml", "<p:sld><a:p><a:r><a:t>总结</a:t></a:r></a:p></p:sld>");
            put(zip, "ppt/slides/slide2.xml", "<p:sld><a:p><a:r><a:t>LoRA &amp; </a:t></a:r><a:r><a:t>QLoRA</a:t></a:r></a:p>"
                    + "<a:p><a:r><a:t>低秩适配</a:t></a:r></a:p></p:sld>");
            put(zip, "ppt/presentation.xml", "<p/>");
        }
        assertThat(DocumentText.pages(deck)).containsExactly(new DocumentText.Page(2, "LoRA & QLoRA\n低秩适配"),
                new DocumentText.Page(10, "总结"));
        assertThat(DocumentText.readable(tmp.resolve("a.mp3"))).isFalse();
    }

    private static void put(ZipOutputStream zip, String name, String body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
