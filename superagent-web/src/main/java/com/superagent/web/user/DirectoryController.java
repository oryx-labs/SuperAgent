package com.superagent.web.user;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.user.DirectoryUserDto;
import com.superagent.core.user.User;
import com.superagent.platform.user.PeopleDirectory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Colleagues any signed-in user may look up (to invite them to an agent). Only names: user
 * management with roles and contact details stays admin-only under {@code /users}.
 */
@RestController
public class DirectoryController {

    private final PeopleDirectory people;

    public DirectoryController(PeopleDirectory people) {
        this.people = people;
    }

    @GetMapping(ApiPaths.V1 + "/directory/users")
    public ApiResponse<List<DirectoryUserDto>> users() {
        return ApiResponse.ok(people.enabled().stream()
                .map(u -> new DirectoryUserDto(u.id(), u.username(),
                        u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName()))
                .sorted(Comparator.comparing(DirectoryUserDto::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList());
    }
}
