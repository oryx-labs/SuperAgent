package com.superagent.web.account;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.account.AccountRequests;
import com.superagent.platform.user.AccountService;
import com.superagent.platform.user.UserNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The signed-in user's own avatar and password; avatars are readable by every signed-in user. */
@RestController
public class AccountController {

    private final AccountService account;

    public AccountController(AccountService account) {
        this.account = account;
    }

    @PutMapping(ApiPaths.ACCOUNT + "/password")
    public ApiResponse<Void> changePassword(@RequestBody AccountRequests.ChangePassword request, Authentication auth) {
        account.changePassword(auth.getName(), request.current(), request.next());
        return ApiResponse.empty();
    }

    /** Returns the new avatar version. */
    @PutMapping(ApiPaths.ACCOUNT + "/avatar")
    public ApiResponse<Long> setAvatar(@RequestBody AccountRequests.SetAvatar request, Authentication auth) {
        return ApiResponse.ok(account.setAvatar(auth.getName(), request.data()));
    }

    @DeleteMapping(ApiPaths.ACCOUNT + "/avatar")
    public ApiResponse<Void> removeAvatar(Authentication auth) {
        account.removeAvatar(auth.getName());
        return ApiResponse.empty();
    }

    /** username → avatar version, for everyone who has one. */
    @GetMapping(ApiPaths.AVATARS)
    public ApiResponse<Map<String, Long>> versions() {
        return ApiResponse.ok(account.avatarVersions());
    }

    /** Clients ask with {@code ?v=<version>}, so the long private cache never shows a stale picture. */
    @GetMapping(ApiPaths.AVATARS + "/{username}")
    public ResponseEntity<byte[]> avatar(@PathVariable String username) {
        return account.avatar(username).orElseThrow(() -> new UserNotFoundException(username)).toResponse();
    }
}
