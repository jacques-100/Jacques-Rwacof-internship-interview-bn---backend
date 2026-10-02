package com.rwacof.cherrytrack.security;

import com.rwacof.cherrytrack.model.User;
import com.rwacof.cherrytrack.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates Bearer tokens. The user and role are re-read from the database on every request,
 * so deactivating a user or changing a role takes effect immediately, and claims in the token are never trusted.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            jwtService.parseUserId(header.substring(PREFIX.length()).trim())
                    .flatMap(userRepository::findWithRoleById)
                    .filter(User::isActive)
                    .ifPresent(user -> {
                        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                        ctx.setAuthentication(CurrentUser.authenticationFor(AuthUser.of(user)));
                        SecurityContextHolder.setContext(ctx);
                    });
        }
        chain.doFilter(request, response);
    }
}
