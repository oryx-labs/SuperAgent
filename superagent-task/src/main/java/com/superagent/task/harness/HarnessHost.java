package com.superagent.task.harness;

import com.superagent.core.chat.Cancellation;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;

import java.math.BigDecimal;

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
}
