package com.superagent.task.harness;

import com.superagent.core.chat.Cancellation;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;

import java.math.BigDecimal;
import java.nio.file.Path;

/** What a harness asks of the task service: run an agent turn, record what it did, and settle the task. */
public interface HarnessHost {

    /** One agent turn in {@code dir} on conversation {@code conversation} ({@code first}: start it), shown under {@code label}. */
    Turn turn(Task task, String prompt, String conversation, boolean first, String label, Cancellation cancellation);

    /** The task now works in {@code dir} (its worktree or clone); returns it updated. */
    Task workIn(Task task, String dir);

    void progress(Task task, int percent);

    /** A line in the task's run log that everyone watching sees (a step starting, a check failing…). */
    void note(Task task, String text);

    /** A question for the user that the task now waits on (shown with a continue button). */
    void ask(Task task, String text);

    /** A milestone for the chat that set the task off (a fix round under way, …): what the user would want to know. */
    void tell(Task task, String text);

    void save(Task task, HarnessState state);

    /** Ends the run: done (with the delivery as output), waiting on the user, failed or stopped. */
    void settle(Task task, TaskStatus status, int progress, String output, String error);

    /** {@code repeated}: it stops on the same question it stopped on last time, which the user was already told about. */
    default void settle(Task task, TaskStatus status, int progress, String output, String error, boolean repeated) {
        settle(task, status, progress, output, error);
    }

    /** The owner's token for a connector they authorized (GitHub, GitLab), if any. */
    java.util.Optional<String> connectorToken(String connectorId, String owner);

    /** Directories (real paths) a local repository must sit under, as the company's admin set them; empty = none. */
    java.util.List<java.nio.file.Path> localRoots();

    /** The site the owner's authorization of a connector is bound to (GitLab: the address its token was made on). */
    java.util.Optional<String> connectorSite(String connectorId, String owner);

    /** Where a company's task clones and worktrees live. */
    java.nio.file.Path workRoot();

    record Turn(String text, BigDecimal cost, boolean cancelled) {
    }

    // --- the review loop: another model looks at the work ---

    /**
     * A turn on {@code model} (null: the task's own) in a conversation that starts now, in the task's directory: a
     * review that never saw how the work came about.
     */
    default Turn reviewTurn(Task task, Model model, String prompt, String conversation, String label, Cancellation c) {
        return turn(task, prompt, conversation, true, label, c);
    }

    /** Who reviews the task's work: another model than the one it runs on where there is one. Empty: its own. */
    default java.util.Optional<com.superagent.core.task.TaskModelChoice> reviewer(Task task) {
        return java.util.Optional.empty();
    }

    /** Keeps a model decision in the task's 「模型选择」. */
    default void recordChoice(com.superagent.core.task.TaskModelChoice choice) {
    }

    /** What the task has spent so far (USD). */
    default BigDecimal spend(Task task) {
        return task.spend() == null ? BigDecimal.ZERO : task.spend();
    }

    /** The company's limits on the review loop. */
    default ReviewLimits reviewLimits() {
        return new ReviewLimits(8, 30);
    }

    // --- what the general flow needs besides ---

    /**
     * A turn on {@code model} instead of the task's own (null: the task's), in a conversation of its own that starts
     * now: an independent check that never saw how the work came about.
     */
    default Turn turnOn(Task task, Model model, String prompt, String conversation, String label, Cancellation cancellation) {
        return turn(task, prompt, conversation, true, label, cancellation);
    }

    /** The cheaper model the task's work is checked on; empty: the task's own model. */
    default java.util.Optional<Model> checkerModel(Task task) {
        return java.util.Optional.empty();
    }

    /** The task's brief as its first turn is given it (in a conversation that may hold earlier tasks: {@code first} false). */
    default String brief(Task task, boolean first) {
        return task.brief();
    }

    /** Whether the task runs without stopping for the user (a complex task and its subtasks). */
    default boolean onItsOwn(Task task) {
        return false;
    }

    /** The files the task handed over (file_write / file_save), with the text of the ones that have text. */
    default java.util.List<Produced> produced(Task task) {
        return java.util.List.of();
    }

    /** Whether the task got at its materials: the ones it was given that it never fetched, the links it couldn't read. */
    default Materials materials(Task task) {
        return new Materials(java.util.List.of(), java.util.List.of(), java.util.List.of());
    }

    // --- what flows that work on files need ---

    /** Copies a file of the task's owner from Files into {@code dir} (under uploads/); returns where it landed. */
    default Path fetch(Task task, String fileId, Path dir) {
        throw new UnsupportedOperationException("no files here");
    }

    /** Hands a file over to the owner's Files ({@code replaceId}: the earlier one it replaces); returns its id. */
    default String deliver(Task task, Path file, String name, String replaceId, java.util.List<String> tags) {
        throw new UnsupportedOperationException("no files here");
    }

    /** The project the task was made in. */
    default java.util.Optional<String> projectOf(Task task) {
        return java.util.Optional.empty();
    }

    /** What the platform spent on the task outside an agent turn (a transcription), added to its cost. */
    default void spent(Task task, BigDecimal usd) {
    }

    record Model(String model, String cli) {
    }

    record ReviewLimits(int maxRounds, double maxSpend) {
    }

    /** {@code text}: the start of its content when it is text (null otherwise). */
    record Produced(String id, String name, String mimeType, long size, String text) {
    }

    /** {@code given}: the material files the task was given; {@code unfetched}: those it never took; {@code unreadable}: links. */
    record Materials(java.util.List<String> given, java.util.List<String> unfetched, java.util.List<String> unreadable) {
    }
}
