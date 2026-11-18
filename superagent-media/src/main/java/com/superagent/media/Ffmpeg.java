package com.superagent.media;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** The server's ffmpeg, to turn any audio or video into mono mp3 pieces the transcription API takes. */
final class Ffmpeg {

    private final Path exe;

    Ffmpeg(Path exe) {
        this.exe = exe;
    }

    /** On the PATH or in the usual places; null when the server has none. */
    static Ffmpeg find() {
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            dirs.addAll(List.of(path.split(File.pathSeparator)));
        }
        dirs.addAll(List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin"));
        return dirs.stream().filter(d -> !d.isBlank()).map(d -> Path.of(d, "ffmpeg")).filter(Files::isExecutable)
                .findFirst().map(Ffmpeg::new).orElse(null);
    }

    /** Pieces of {@code seconds} each, in order: 16 kHz mono mp3 at 48 kbit/s (about 7 MB for 20 minutes). */
    List<Path> split(Path input, Path dir, int seconds) throws IOException {
        Process p = new ProcessBuilder(exe.toString(), "-nostdin", "-loglevel", "error", "-y", "-i", input.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame", "-b:a", "48k",
                "-f", "segment", "-segment_time", String.valueOf(seconds), "-reset_timestamps", "1",
                dir.resolve("part%03d.mp3").toString())
                // To a file, not a pipe: reading a pipe would block past the timeout.
                .redirectErrorStream(true).redirectOutput(dir.resolve("ffmpeg.log").toFile()).start();
        p.getOutputStream().close();
        try {
            if (!p.waitFor(30, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IllegalArgumentException("ffmpeg 处理这个文件超过了 30 分钟");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> pieces = files.filter(f -> f.getFileName().toString().startsWith("part")).sorted().toList();
            if (p.exitValue() != 0 || pieces.isEmpty()) {
                String why = Files.readString(dir.resolve("ffmpeg.log")).strip();
                throw new IllegalArgumentException("ffmpeg 读不了这个文件，它可能不是音频或视频"
                        + (why.isEmpty() ? "" : "：" + (why.length() > 200 ? why.substring(why.length() - 200) : why)));
            }
            return pieces;
        }
    }
}
