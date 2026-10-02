package com.rwacof.cherrytrack.security;

import com.rwacof.cherrytrack.model.Permission;
import com.rwacof.cherrytrack.model.Role;
import com.rwacof.cherrytrack.model.User;

import java.util.EnumSet;
import java.util.Set;

/**
 * The authenticated principal. Built from the database on every request, never from token claims, so a
 * permission change or deactivation takes effect immediately.
 *
 * @param role        access level (administrators can use every station)
 * @param permissions what the user's role currently allows
 */
public record AuthUser(Long id, String username, Role role, Set<Permission> permissions) {

    public AuthUser {
        permissions = permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
    }

    /** Requires the user's job role and its permissions to be loaded (see UserRepository#findWithRoleById). */
    public static AuthUser of(User user) {
        return new AuthUser(user.getId(), user.getUsername(), user.getRole(), user.getJobRole().getPermissions());
    }

    /** For code with no HTTP request (seeding, tests): the standard permissions of the user's access level. */
    public static AuthUser withTemplate(User user) {
        return new AuthUser(user.getId(), user.getUsername(), user.getRole(), Permission.template(user.getRole()));
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }
}
