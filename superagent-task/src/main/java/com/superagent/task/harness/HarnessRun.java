package com.superagent.task.harness;

import com.superagent.core.chat.Cancellation;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;

import java.time.Instant;

/** One run of a harness on one task: the state it advances and the ways it can end. */
public final class HarnessRun {

    private final HarnessHost host;
    private final HarnessState state;
    private final Cancellation cancellation;
    private final String reply;
    private Task task;
    private boolean settled;

    public HarnessRun(HarnessHost host, Task task, HarnessState state, String reply, Cancellation cancellation) {
        this.host = host;
        this.task = task;
        this.state = state;
        this.reply = reply == null || reply.isBlank() ? null : reply.strip();
        this.cancellation = cancellation;
    }

    public Task task() {
        return task;
    }

    public HarnessState state() {
        return state;
    }

    /** What the user sent to answer the step the task waited on (null on a fresh or recovered run). */
    public String reply() {
        return reply;
    }

    public boolean cancelled() {
        return cancellation.isCancelled();
    }

    public HarnessHost.Turn turn(String prompt, String conversation, boolean first, String label) {
        return host.turn(task, prompt, conversation, first, label, cancellation);
    }

    public void workIn(String dir) {
        task = host.workIn(task, dir);
    }

    public HarnessHost host() {
        return host;
    }

    public void note(String text) {
        host.note(task, text);
    }

    public void begin(HarnessState.Step step) {
        if (step.status != HarnessState.Status.RUNNING) {
            step.status = HarnessState.Status.RUNNING;
            step.startedAt = step.startedAt == null ? Instant.now() : step.startedAt;
            step.note = null;
            note("── " + step.name + " ──");
        }
        state.waitingOn = null;
        save();
        host.progress(task, progressAt(step, false));
    }

    public void done(HarnessState.Step step, String note) {
        step.status = HarnessState.Status.DONE;
        step.finishedAt = Instant.now();
        step.note = note;
        save();
        host.progress(task, progressAt(step, true));
    }

    public void tell(String text) {
        host.tell(task, text);
    }

    public void save() {
        host.save(task, state);
    }

    /** Stops at {@code step} until the user answers; {@code ask} is shown to them (with a continue button). */
    public void waitFor(HarnessState.Step step, String ask) {
        step.status = HarnessState.Status.WAITING;
        step.note = ask;
        state.waitingOn = step.key;
        save();
        settled = true;
        host.ask(task, ask);
        host.settle(task, TaskStatus.NEEDS_CONFIRMATION, progressAt(step, false), null, null);
    }

    public void fail(HarnessState.Step step, String why) {
        step.status = HarnessState.Status.FAILED;
        step.note = why;
        save();
        settled = true;
        host.settle(task, TaskStatus.ERROR, progressAt(step, false), null, why);
    }

    public void stopped(HarnessState.Step step) {
        step.status = HarnessState.Status.PENDING;
        save();
        settled = true;
        host.settle(task, TaskStatus.STOPPED, progressAt(step, false), null, null);
    }

    public void finish(String output) {
        save();
        settled = true;
        host.settle(task, TaskStatus.DONE, 100, output, null);
    }

    public boolean settled() {
        return settled;
    }

    private int progressAt(HarnessState.Step step, boolean done) {
        int n = state.steps.size();
        int i = state.indexOf(step) + (done ? 1 : 0);
        return Math.max(5, Math.min(99, Math.round(i * 100f / n)));
    }
}
