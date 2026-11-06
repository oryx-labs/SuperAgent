package com.superagent.platform.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.provider.ProviderCatalog;
import com.superagent.runtime.cli.CliPaths;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CliPaths paths;

    public ProviderProbe(CliPaths paths) {
        this.paths = paths;
    }

    public Optional<Path> findBinary(String cli) {
        return paths.find(cli);
    }

    /**
     * An npm CLI's version from its own package.json (the binary is a symlink into the package), so the status
     * check needn't run a CLI that may not support {@code --version}; falls back to running it.
     */
    public Optional<String> version(Path binary, ProviderCatalog.Entry entry) {
        if (entry.npmPackage() != null) {
            Optional<String> fromPackage = packageVersion(binary, entry.npmPackage());
            if (fromPackage.isPresent()) {
                return fromPackage;
            }
        }
        return version(binary, entry.versionArgs());
    }

    static Optional<String> packageVersion(Path binary, String npmPackage) {
        try {
            for (Path dir = binary.toRealPath().getParent(); dir != null; dir = dir.getParent()) {
                Path pkg = dir.resolve("package.json");
                if (Files.isRegularFile(pkg)) {
                    JsonNode json = JSON.readTree(pkg.toFile());
                    if (npmPackage.equals(json.path("name").asText())) {
                        return semver(json.path("version").asText(""));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // not an npm install, or unreadable: fall back to asking the binary
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

    /** The search path for CLIs (private Node first, then PATH, then common global-install dirs). */
    List<String> searchPath() {
        return paths.searchPath();
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
