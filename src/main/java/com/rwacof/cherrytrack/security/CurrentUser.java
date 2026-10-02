package com.rwacof.cherrytrack.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class CurrentUser {

    private CurrentUser() {}

    public static Optional<AuthUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static AuthUser require() {
        return get().orElseThrow(() ->
                new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("No authenticated user"));
    }

    public static Authentication authenticationFor(AuthUser user) {
        // ROLE_x is the access level; each permission is its own authority (hasAuthority('DELIVERY_PAY') etc.).
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
        user.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        return UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    }

    /** Runs code as the given user (used by the seeder and tests, which have no HTTP request). */
    public static void runAs(AuthUser user, Runnable action) {
        callAs(user, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(AuthUser user, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(authenticationFor(user));
        SecurityContextHolder.setContext(ctx);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
