package com.superagent.web.user;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.user.SaveUserRequest;
import com.superagent.api.user.UserDto;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only user provisioning endpoints (enforced by SecurityConfig). */
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping(ApiPaths.USERS)
    public ApiResponse<List<UserDto>> list() {
        return ApiResponse.ok(userService.list());
    }

    @PostMapping(ApiPaths.USERS)
    public ApiResponse<UserDto> create(@RequestBody SaveUserRequest request) {
        return ApiResponse.ok(userService.create(request));
    }

    @PutMapping(ApiPaths.USERS + "/{id}")
    public ApiResponse<UserDto> update(@PathVariable String id, @RequestBody SaveUserRequest request) {
        return ApiResponse.ok(userService.update(id, request));
    }

    @DeleteMapping(ApiPaths.USERS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        userService.delete(id);
        return ApiResponse.empty();
    }
}
