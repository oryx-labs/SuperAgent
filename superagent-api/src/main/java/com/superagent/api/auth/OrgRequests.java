package com.superagent.api.auth;

/** Registering an organization. */
public final class OrgRequests {

    private OrgRequests() {
    }

    /** Both required. {@code englishName}: the organization's identifier, typed at sign-in; also the admin's username. */
    public record Register(String name, String englishName) {
    }

    /** The admin's sign-in; the password is shown once and can't be retrieved again. */
    public record Registered(String org, String name, String username, String password) {
    }
}
