package com.superagent.core.tenant;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The company the current work belongs to. Every way into the system sets it: an HTTP request (from the
 * signed-in user's company), an MCP call (from its run grant), a background loop (once per company it visits),
 * an outside event (from the connection it arrived on). Stores read it to scope every query; with none set they
 * fail ({@link #require()}) rather than read or write across companies.
 *
 * <p>Thread-bound, so work handed to another thread must carry it: {@link #wrap(Runnable)} captures the current
 * company for a task that will run elsewhere.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** The current company, or empty outside any company (start-up, platform-wide work). */
    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current company; throws when there is none, so nothing is ever read or written for "any company". */
    public static String require() {
        String id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No company context: this work must run inside TenantContext.run(companyId, …)");
        }
        return id;
    }

    /** Runs {@code work} as {@code companyId}, restoring whatever was current before. */
    public static void run(String companyId, Runnable work) {
        call(companyId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T call(String companyId, Supplier<T> work) {
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException("companyId is required");
        }
        String before = CURRENT.get();
        CURRENT.set(companyId);
        try {
            return work.get();
        } finally {
            restore(before);
        }
    }

    public static <T> T callChecked(String companyId, Callable<T> work) throws Exception {
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException("companyId is required");
        }
        String before = CURRENT.get();
        CURRENT.set(companyId);
        try {
            return work.call();
        } finally {
            restore(before);
        }
    }

    /** {@code work} bound to the current company, for running on another thread. Fails now if there is none. */
    public static Runnable wrap(Runnable work) {
        String id = require();
        return () -> run(id, work);
    }

    /**
     * Sets the company for the rest of this thread's request; the caller must {@link #clear()} it afterwards. Only
     * for request filters, where the start and end of the work are separate callbacks.
     */
    public static void set(String companyId) {
        CURRENT.set(companyId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static void restore(String before) {
        if (before == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(before);
        }
    }
}
