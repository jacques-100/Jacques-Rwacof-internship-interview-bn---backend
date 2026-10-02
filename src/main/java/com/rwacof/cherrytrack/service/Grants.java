package com.rwacof.cherrytrack.service;

import com.rwacof.cherrytrack.exception.ForbiddenException;
import com.rwacof.cherrytrack.model.Permission;
import com.rwacof.cherrytrack.security.AuthUser;
import com.rwacof.cherrytrack.security.CurrentUser;

import java.util.Set;

/**
 * Stops privilege escalation: nobody can hand out (through a role or a role assignment) a permission they
 * do not hold themselves. Administrators hold everything, so they are never limited by this.
 */
final class Grants {

    private Grants() {}

    static void require(Set<Permission> wanted) {
        AuthUser actor = CurrentUser.get().orElse(null);
        if (actor == null) {
            return;   // no signed-in user: seeding and tests
        }
        if (!actor.permissions().containsAll(wanted)) {
            throw new ForbiddenException("CANNOT_GRANT", "You cannot grant permissions that you do not hold yourself.");
        }
    }
}
