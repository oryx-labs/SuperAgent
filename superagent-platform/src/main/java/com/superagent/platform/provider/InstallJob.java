package com.superagent.platform.provider;

import com.superagent.api.provider.InstallLogDto;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** One install or upgrade as it runs: the step it's on and what it has printed, kept to the last few hundred lines. */
final class InstallJob {

    private static final int MAX_LINES = 400;

    private final String action;
    private final Instant startedAt = Instant.now();
    private final Deque<String> lines = new ArrayDeque<>();
    private volatile String step;
    private volatile Instant finishedAt;
    private volatile String error;

    InstallJob(String action, String firstStep) {
        this.action = action;
        step(firstStep);
    }

    void step(String text) {
        step = text;
        line("▸ " + text);
    }

    synchronized void line(String text) {
        lines.addLast(text);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    void finish(String failure) {
        error = failure;
        line(failure == null ? "✓ 完成" : "✗ 失败：" + failure);
        finishedAt = Instant.now();
    }

    boolean running() {
        return finishedAt == null;
    }

    /** The current step while running, else null. */
    String currentStep() {
        return running() ? step : null;
    }

    synchronized InstallLogDto toDto() {
        return new InstallLogDto(action, currentStep(), startedAt, finishedAt, running(), error, List.copyOf(lines));
    }
}
