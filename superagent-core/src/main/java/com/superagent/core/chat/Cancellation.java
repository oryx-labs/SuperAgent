package com.superagent.core.chat;

/**
 * Cooperative cancellation handle for a running agent turn.
 *
 * <p>The orchestrator creates one per turn and can {@link #cancel()} it (e.g.
 * when the user hits stop). The {@link AgentRunner} registers how to abort the
 * work — typically destroying the CLI subprocess — via {@link #onCancel(Runnable)};
 * if cancellation already happened, the action runs immediately.
 */
public final class Cancellation {

    private Runnable canceller;
    private boolean cancelled;

    /** Registers the action that aborts the run. Runs now if already cancelled. */
    public synchronized void onCancel(Runnable canceller) {
        this.canceller = canceller;
        if (cancelled && canceller != null) {
            canceller.run();
        }
    }

    /** Requests cancellation and triggers the registered action, if any. */
    public synchronized void cancel() {
        cancelled = true;
        if (canceller != null) {
            canceller.run();
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }
}
