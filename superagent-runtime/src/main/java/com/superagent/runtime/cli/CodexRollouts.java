package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Codex's {@code turn.completed} usage on {@code exec resume} is the thread's running total (checked against a real
 * run: a second one-word turn reported output 10 = 5 + 5). The thread's rollout file
 * ({@code <CODEX_HOME>/sessions/YYYY/MM/DD/rollout-…-<thread>.jsonl}) logs that total on each {@code token_count}
 * event, so the last one before a run is what the run's report is measured from, across restarts too.
 */
final class CodexRollouts {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CodexRollouts() {
    }

    static Path home() {
        String env = System.getenv("CODEX_HOME");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".codex");
    }

    /** The thread's total so far, as {@code turn.completed} reports usage; empty when its rollout can't be found. */
    static Optional<JsonNode> lastTotal(Path home, String threadId) {
        return rollout(home, threadId).flatMap(CodexRollouts::lastTotalIn);
    }

    /**
     * The context the thread's last request read, cached part included ({@code last_token_usage}, the per-request
     * figure next to the running total); empty when the rollout or that figure can't be found.
     */
    static Optional<Long> lastRequestInput(Path home, String threadId) {
        return rollout(home, threadId).flatMap(CodexRollouts::lastRequestInputIn);
    }

    private static Optional<Path> rollout(Path home, String threadId) {
        Path sessions = home.resolve("sessions");
        if (threadId == null || !Files.isDirectory(sessions)) {
            return Optional.empty();
        }
        String suffix = "-" + threadId + ".jsonl";
        try (Stream<Path> found = Files.find(sessions, 4, (p, a) -> a.isRegularFile() && p.getFileName().toString().endsWith(suffix))) {
            return found.findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Long> lastRequestInputIn(Path rollout) {
        Long input = null;
        try (BufferedReader r = Files.newBufferedReader(rollout, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("\"token_count\"")) {
                    continue;
                }
                JsonNode last = JSON.readTree(line).path("payload").path("info").path("last_token_usage");
                if (last.path("input_tokens").canConvertToLong()) {
                    input = Math.max(0, last.path("input_tokens").asLong());
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(input);
    }

    private static Optional<JsonNode> lastTotalIn(Path rollout) {
        JsonNode total = null;
        try (BufferedReader r = Files.newBufferedReader(rollout, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("\"token_count\"")) {
                    continue;
                }
                JsonNode t = JSON.readTree(line).path("payload").path("info").path("total_token_usage");
                if (t.isObject()) {
                    total = t;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(total);
    }
}
