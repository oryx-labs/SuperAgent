package com.superagent.runtime.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * npm's global bin dir holds a Unix launcher, a .cmd and maybe a .exe per CLI; find() must pick the one
 * this OS can actually start (the Unix script fails on Windows with CreateProcess error 193).
 */
class CliPathsTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    @TempDir
    Path root;

    @Test
    void picksTheLauncherThisOsCanStart() throws IOException {
        Path bin = privateBin(); // first on CliPaths' search path, ahead of the real PATH
        launcher(bin, "codex");      // npm's sh script
        launcher(bin, "codex.cmd");
        launcher(bin, "codex.exe");
        Path expected = WINDOWS ? bin.resolve("codex.exe") : bin.resolve("codex");

        CliPaths paths = new CliPaths(root.toString());
        assertEquals(expected, paths.find("codex").orElseThrow());
        assertEquals(expected, paths.find(bin.resolve("codex").toString()).orElseThrow()); // same by explicit path
    }

    @Test
    void cmdIsPreferredOverTheUnixScriptWhenNoExeExists() throws IOException {
        Path bin = privateBin();
        launcher(bin, "codex");      // bare Unix script must not win on Windows
        launcher(bin, "codex.cmd");
        Path expected = WINDOWS ? bin.resolve("codex.cmd") : bin.resolve("codex");

        assertEquals(expected, new CliPaths(root.toString()).find("codex").orElseThrow());
    }

    private Path privateBin() throws IOException {
        Path bin = root.resolve("current").resolve("bin");
        Files.createDirectories(bin);
        return bin;
    }

    private static void launcher(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "#!/bin/sh\n");
        if (!WINDOWS) { // on Unix isExecutable really checks the mode; on Windows any readable file passes
            Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
