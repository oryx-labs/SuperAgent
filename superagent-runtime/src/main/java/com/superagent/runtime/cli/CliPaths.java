package com.superagent.runtime.cli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Where vendor CLIs (and the Node.js they run on) live on this machine. One search path shared by the
 * installer, the status probe and the agent runners, so a CLI the Models page installed is also the one a
 * task runs. Order: SuperAgent's private Node (installed from the Models page when the server has none, or
 * one too old) first, then the process PATH, then common global-install dirs a service process's PATH lacks.
 */
@Component
public class CliPaths {

    private static final List<String> EXTRA_BIN_DIRS = List.of("/opt/homebrew/bin", "/usr/local/bin",
            System.getProperty("user.home") + "/.npm-global/bin", System.getProperty("user.home") + "/.local/bin");

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Path nodeRoot;

    public CliPaths(@Value("${superagent.runtime.node-dir:./data/runtime/node}") String nodeDir) {
        this.nodeRoot = Path.of(nodeDir).toAbsolutePath().normalize();
    }

    /** Where the private Node lives: {@code <root>/current} is the active install. */
    public Path nodeRoot() {
        return nodeRoot;
    }

    public Path privateNodeBin() {
        return nodeRoot.resolve("current").resolve("bin");
    }

    public List<String> searchPath() {
        Set<String> dirs = new LinkedHashSet<>();
        dirs.add(privateNodeBin().toString());
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                if (!d.isBlank()) {
                    dirs.add(d);
                }
            }
        }
        dirs.addAll(EXTRA_BIN_DIRS);
        return List.copyOf(dirs);
    }

    /**
     * On Windows an npm global bin dir holds one launcher per CLI in three files — {@code codex} (a sh
     * script), {@code codex.cmd} and {@code codex.ps1} — of which only the .exe (Volta's shims, some
     * packages) and .cmd (npm's own) can be started: CreateProcess rejects the extensionless Unix script
     * with error 193. Try the Windows-startable spellings before the bare name, which stays the only
     * spelling (and the right one) on macOS/Linux.
     */
    private static List<String> candidates(String cli) {
        return WINDOWS ? List.of(cli + ".exe", cli + ".cmd", cli + ".bat", cli) : List.of(cli);
    }

    private static boolean launcherFor(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }

    public Optional<Path> find(String cli) {
        if (cli == null || cli.isBlank()) {
            return Optional.empty();
        }
        if (cli.contains("/") || cli.contains("\\")) {
            Path p = Path.of(cli);
            Path dir = p.getParent() == null ? Path.of(".") : p.getParent();
            for (String name : candidates(p.getFileName().toString())) {
                Path candidate = dir.resolve(name);
                if (launcherFor(candidate)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
        for (String dir : searchPath()) {
            for (String name : candidates(cli)) {
                Path candidate = Path.of(dir, name);
                if (launcherFor(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Points a process at this search path: the command (argv[0]) resolved to its full path, and PATH set
     * so the CLI's own launcher finds {@code node}. A command that isn't found is left as is (start() reports it).
     */
    public ProcessBuilder apply(ProcessBuilder pb) {
        List<String> cmd = pb.command();
        if (!cmd.isEmpty()) {
            find(cmd.get(0)).ifPresent(bin -> cmd.set(0, bin.toString()));
        }
        pb.environment().put("PATH", String.join(File.pathSeparator, searchPath()));
        return pb;
    }
}
