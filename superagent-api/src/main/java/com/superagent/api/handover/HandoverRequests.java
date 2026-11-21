package com.superagent.api.handover;

/** Admin handover request bodies. Usernames are resolved to user ids inside the company. */
public final class HandoverRequests {

    private HandoverRequests() {
    }

    public record PreviewRequest(String fromUsername, String toUsername) {
    }

    public record ExecuteRequest(String fromUsername, String toUsername, Boolean disableFromUser) {
    }
}
