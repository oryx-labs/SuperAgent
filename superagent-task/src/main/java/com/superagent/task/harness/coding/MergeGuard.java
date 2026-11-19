package com.superagent.task.harness.coding;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Counts local merges in progress (rebase, squash-merge, commit, clean-up), so shutdown can wait for them: a process
 * stopped between those git commands leaves the user's repository half merged.
 */
public final class MergeGuard {

    private static final Object LOCK = new Object();
    private static int active;

    private MergeGuard() {
    }

    static void enter() {
        synchronized (LOCK) {
            active++;
        }
    }

    static void exit() {
        synchronized (LOCK) {
            active--;
            LOCK.notifyAll();
        }
    }

    /** Waits up to {@code timeout} for every merge in progress to finish; true when none is left. */
    public static boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (LOCK) {
            while (active > 0) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    return false;
                }
                LOCK.wait(left);
            }
            return true;
        }
    }
}
