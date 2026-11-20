package com.superagent.task.harness;

import com.superagent.core.chat.Cancellation;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One run of a harness on one task: the state it advances and the ways it can end. */
public final class HarnessRun {

    private final HarnessHost host;
    private final HarnessState state;
    private final Cancellation cancellation;
    private final String reply;
    private Task task;
    private boolean settled;
    private List<String> held; // non-null: run-log lines kept back until it's known whether this is a repeated stop

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

    /** A turn on {@code model} (null: the task's own) in the new conversation {@code conversation}. */
    public HarnessHost.Turn reviewTurn(HarnessHost.Model model, String prompt, String conversation, String label) {
        return host.reviewTurn(task, model, prompt, conversation, label, cancellation);
    }

    /** A turn on {@code model} (null: the task's own) in a new conversation. */
    public HarnessHost.Turn turnOn(HarnessHost.Model model, String prompt, String conversation, String label) {
        return host.turnOn(task, model, prompt, conversation, label, cancellation);
    }

    public void workIn(String dir) {
        task = host.workIn(task, dir);
    }

    public HarnessHost host() {
        return host;
    }

    public void note(String text) {
        if (held != null) {
            held.add(text);
        } else {
            host.note(task, text);
        }
    }

    /**
     * Before beginning {@code step} again after it stopped on a question: keeps the run log back until the step ends,
     * so stopping again on the same question (the user said "continue" but nothing changed) logs nothing new.
     */
    public void quietIfAskedAgain(HarnessState.Step step) {
        if (step.status == HarnessState.Status.WAITING && state.askedKey != null && state.askedKey.startsWith(step.key + "\n")) {
            held = new ArrayList<>();
        }
    }

    private void release(boolean write) {
        List<String> lines = held;
        held = null;
        if (write && lines != null) {
            lines.forEach(l -> host.note(task, l));
        }
    }

    public void begin(HarnessState.Step step) {
        begin(step, step.name);
    }

    /** {@code label}: how the run log heads the step (with its round, say). */
    public void begin(HarnessState.Step step, String label) {
        if (step.status != HarnessState.Status.RUNNING) {
            step.status = HarnessState.Status.RUNNING;
            step.startedAt = step.startedAt == null ? Instant.now() : step.startedAt;
            step.note = null;
            note("── " + label + " ──");
        }
        state.waitingOn = null;
        save();
        host.progress(task, progressAt(step, false));
    }

    public void done(HarnessState.Step step, String note) {
        release(true);
        state.askedKey = null; // past it: a later stop is news again
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

    /**
     * Stops at {@code step} until the user answers; {@code ask} is shown to them (with a continue button). Stopping again
     * on the same question (the user said "continue" but nothing changed) doesn't ask or notify them again.
     */
    public void waitFor(HarnessState.Step step, String ask) {
        String key = step.key + "\n" + ask;
        boolean repeated = key.equals(state.askedKey);
        release(!repeated);
        step.status = HarnessState.Status.WAITING;
        step.note = ask;
        state.waitingOn = step.key;
        state.askedKey = key;
        save();
        settled = true;
        if (!repeated) {
            host.ask(task, ask);
        }
        host.settle(task, TaskStatus.NEEDS_CONFIRMATION, progressAt(step, false), null, null, repeated);
    }

    public void fail(HarnessState.Step step, String why) {
        fail(step, why, true);
    }

    /** {@code escalate}: false when a stronger model wouldn't help (the failure isn't the model's). */
    public void fail(HarnessState.Step step, String why, boolean escalate) {
        release(true);
        step.status = HarnessState.Status.FAILED;
        step.note = why;
        save();
        settled = true;
        host.settle(task, TaskStatus.ERROR, progressAt(step, false), null, why, false, escalate);
    }

    public void stopped(HarnessState.Step step) {
        release(true);
        step.status = HarnessState.Status.PENDING;
        save();
        settled = true;
        host.settle(task, TaskStatus.STOPPED, progressAt(step, false), null, null);
    }

    public void finish(String output) {
        release(true);
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
