package com.superagent.task.harness.coding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs git (and the checks) as plain processes with a time limit, keeping what they printed. */
final class Git {

    record Result(int exit, String out, String err, long millis) {
        boolean ok() {
            return exit == 0;
        }

        String output() {
            return (out + (err.isBlank() ? "" : "\n" + err)).strip();
        }
    }

    private final Map<String, String> env;

    /**
     * The git program to start, per PATH: the binary itself from its exec path rather than whatever "git" is first on
     * the PATH. On macOS that is /usr/bin/git, a shim that looks the real one up through xcrun on every call, which
     * about doubles the cost of each of the many short git commands a coding task runs.
     */
    private static final Map<String, String> PROGRAM = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code env}: added to every process (PATH, and git's auth header when pushing). */
    Git(Map<String, String> env) {
        this.env = env;
    }

    Result git(Path dir, String... args) {
        return git(dir, 600, Map.of(), args);
    }

    Result git(Path dir, int timeoutSeconds, Map<String, String> extraEnv, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(program(dir));
        cmd.add("-c");
        cmd.add("core.quotepath=false");
        cmd.addAll(List.of(args));
        return run(cmd, dir, timeoutSeconds, extraEnv, false);
    }

    private String program(Path dir) {
        String path = env.getOrDefault("PATH", System.getenv("PATH"));
        return PROGRAM.computeIfAbsent(path == null ? "" : path, p -> {
            Result r = run(List.of("git", "--exec-path"), dir, 30, Map.of(), false);
            if (r.ok()) {
                Path real = Path.of(r.out().strip(), "git");
                if (java.nio.file.Files.isExecutable(real)) {
                    return real.toString();
                }
            }
            return "git";
        });
    }

    /** A check, through the shell, as a person would type it. */
    Result shell(Path dir, String command, int timeoutSeconds) {
        return run(List.of("/bin/sh", "-c", command), dir, timeoutSeconds, Map.of(), true);
    }

    String text(Path dir, String... args) {
        Result r = git(dir, args);
        if (!r.ok()) {
            throw new GitException(String.join(" ", args), r.output());
        }
        return r.out.strip();
    }

    /** {@code killedOnShutdown}: a check (a build, a test run) is ended with the server; git itself is let finish. */
    private Result run(List<String> cmd, Path dir, int timeoutSeconds, Map<String, String> extraEnv, boolean killedOnShutdown) {
        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.environment().putAll(env);
        pb.environment().putAll(extraEnv);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // never hang on a password prompt
        try {
            Process p = pb.start();
            if (killedOnShutdown) {
                com.superagent.runtime.cli.CliProcesses.track(p);
            }
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread a = pump(p.getInputStream(), out);
            Thread b = pump(p.getErrorStream(), err);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                a.join(2000);
                b.join(2000);
                return new Result(124, out.toString(StandardCharsets.UTF_8),
                        err.toString(StandardCharsets.UTF_8) + "\n（超过 " + timeoutSeconds + " 秒，已停止）",
                        System.currentTimeMillis() - start);
            }
            a.join(5000);
            b.join(5000);
            return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
                    System.currentTimeMillis() - start);
        } catch (IOException e) {
            return new Result(127, "", e.getMessage(), System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "", "interrupted", System.currentTimeMillis() - start);
        }
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            try (in) {
                in.transferTo(sink);
            } catch (IOException ignored) {
                // the process ended
            }
        }, "git-io");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static String tail(String s, int chars) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= chars ? t : "…" + t.substring(t.length() - chars);
    }

    /** A git step that had to work (and didn't). */
    static final class GitException extends RuntimeException {
        GitException(String what, String output) {
            super("git " + what + " 失败：" + tail(output, 800));
        }
    }
}
