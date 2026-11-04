package com.superagent.web.provider;

import com.superagent.core.provider.ProviderCatalog;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inspects a vendor CLI on this machine: whether its binary is on PATH, its version,
 * whether it looks authenticated, and (over the network) the latest published version.
 * Commands run via {@link ProcessBuilder} argv — never through a shell.
 */
@Component
public class ProviderProbe {

    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    /** Common install locations that a service process's PATH often lacks (npm/Homebrew globals). */
    private static final List<String> EXTRA_BIN_DIRS = List.of("/opt/homebrew/bin", "/usr/local/bin",
            System.getProperty("user.home") + "/.npm-global/bin", System.getProperty("user.home") + "/.local/bin");

    public Optional<Path> findBinary(String cli) {
        if (cli == null || cli.isBlank()) {
            return Optional.empty();
        }
        for (String dir : searchPath()) {
            Path candidate = Path.of(dir, cli);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Runs {@code <binary> <versionArgs>} with a short timeout and extracts a semver; empty if it fails. */
    public Optional<String> version(Path binary, List<String> versionArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.toString());
        cmd.addAll(versionArgs);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return semver(out);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Heuristic auth signal: a known credentials file exists or a known API-key env var is set. */
    public boolean looksAuthenticated(ProviderCatalog.Entry entry) {
        for (String env : entry.authEnvVars()) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) {
                return true;
            }
        }
        for (String f : entry.authFiles()) {
            if (Files.isRegularFile(Path.of(expandHome(f)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Latest published version via {@code npm view}, empty on any failure. Asks npm rather than
     * calling the registry over HTTP: it uses the same registry/proxy/CA config as the install
     * command, whereas the JDK truststore may reject a corporate TLS chain that npm accepts.
     */
    public Optional<String> latestNpmVersion(String npmPackage) {
        if (npmPackage == null || npmPackage.isBlank()) {
            return Optional.empty();
        }
        Optional<Path> npm = findBinary("npm");
        if (npm.isEmpty()) {
            return Optional.empty();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(npm.get().toString(), "view", npmPackage, "version")
                    .redirectErrorStream(true);
            // npm's launcher needs node on PATH; a service process often lacks the global bin dirs.
            pb.environment().put("PATH", String.join(File.pathSeparator, searchPath()));
            Process p = pb.start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.exitValue() == 0 ? semver(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** True when {@code latest} is a strictly newer semver than {@code current}. */
    public static boolean isNewer(String latest, String current) {
        if (latest == null || current == null) {
            return false;
        }
        int[] a = parts(latest);
        int[] b = parts(current);
        if (a == null || b == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    /** The search path for CLIs: the process PATH plus common global-install dirs. */
    List<String> searchPath() {
        Set<String> dirs = new LinkedHashSet<>();
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

    static Optional<String> semver(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = SEMVER.matcher(text);
        return m.find() ? Optional.of(m.group(0)) : Optional.empty();
    }

    private static int[] parts(String v) {
        Matcher m = SEMVER.matcher(v);
        if (!m.find()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    private static String expandHome(String p) {
        return p.startsWith("~/") ? System.getProperty("user.home") + p.substring(1) : p;
    }
}
