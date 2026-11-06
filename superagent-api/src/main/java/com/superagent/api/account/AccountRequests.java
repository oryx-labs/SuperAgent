package com.superagent.api.account;

/** What a signed-in user sends to change their own account. */
public final class AccountRequests {

    public record ChangePassword(String current, String next) {
    }

    /** {@code data}: the image, base64 (PNG, JPEG, GIF or WebP, at most 1 MB). */
    public record SetAvatar(String data) {
    }

    private AccountRequests() {
    }
}
