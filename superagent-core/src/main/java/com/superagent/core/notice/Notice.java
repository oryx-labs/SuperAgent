package com.superagent.core.notice;

import java.time.Instant;

/**
 * One item on a user's to-do home page: something to decide, something to do, finished work, or a
 * heads-up. {@code key} identifies what it is about (e.g. "task:&lt;id&gt;"), so one task keeps one item
 * that moves between kinds as the task does, instead of piling up. Items are kept until the user
 * ignores them; nothing expires.
 */
public record Notice(
        String id,
        String owner,
        String key,
        Kind kind,
        State state,
        String title,
        String body,
        /** Who did it or where it came from: an agent's name, "定时任务：早报", "飞书·研发群". */
        String actor,
        /** What the item opens: task | agent | superagent | todo. */
        String linkType,
        String linkId,
        Instant createdAt,
        Instant updatedAt) {

    public enum Kind {
        /** Needs the user's decision before work continues (task waiting, plan to approve, task failed). */
        CONFIRM,
        /** Something the user should do themselves (a to-do extracted from received messages). */
        ACTION,
        /** Work an agent finished in the background. */
        DONE,
        /** A heads-up that needs nothing (e.g. invited to a shared agent). */
        NOTICE,
        /** Not shown: remembers a running task's origin until it settles. */
        TRACK
    }

    public enum State { OPEN, DONE, IGNORED }

    public Notice with(Kind newKind, State newState, String newTitle, String newBody, String newActor) {
        return new Notice(id, owner, key, newKind, newState, newTitle, newBody, newActor, linkType, linkId,
                createdAt, Instant.now());
    }

    public Notice withState(State newState) {
        return new Notice(id, owner, key, kind, newState, title, body, actor, linkType, linkId, createdAt, Instant.now());
    }
}
